package coldplay;

import coldplay.event.Event;
import coldplay.event.EventTarget;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches annotated handlers per class and atomically replaces subscription arrays on registration
 * changes, keeping dispatch lock-free. Active flags let removal take effect during dispatch.
 */
public class EventHandler {

    private static final class MethodMeta {
        final Method method;
        final Class<? extends Event> eventType;
        final int priority;

        MethodMeta(Method method, Class<? extends Event> eventType, int priority) {
            this.method = method;
            this.eventType = eventType;
            this.priority = priority;
            method.setAccessible(true);
        }
    }

    private static final class Subscription {
        final Object listener;
        final Method method;
        final int priority;
        volatile boolean active = true;

        Subscription(Object listener, MethodMeta meta) {
            this.listener = listener;
            this.method = meta.method;
            this.priority = meta.priority;
        }

        void invoke(Event event) {
            try {
                method.invoke(listener, event);
            } catch (Throwable t) {
                // Escaping a listener failure on the Netty thread would tear down the connection.
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                System.err.println("[ColdPlay] handler " + listener.getClass().getSimpleName() + "#"
                        + method.getName() + " threw: " + cause);
                cause.printStackTrace();
            }
        }
    }

    private static final Map<Class<?>, List<MethodMeta>> CLASS_CACHE = new ConcurrentHashMap<>();

    private final Map<Class<? extends Event>, Subscription[]> dispatch = new ConcurrentHashMap<>();

    /** Identity registry: equal-but-distinct listeners remain independently subscribable. */
    private final Set<Object> listeners = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    public synchronized void register(Object listener) {
        if (listener == null || !listeners.add(listener)) {
            return;
        }
        for (MethodMeta meta : metaFor(listener.getClass())) {
            addSubscription(meta.eventType, new Subscription(listener, meta));
        }
    }

    public synchronized void unregister(Object listener) {
        if (!listeners.remove(listener)) {
            return;
        }
        for (MethodMeta meta : metaFor(listener.getClass())) {
            removeSubscriptions(meta.eventType, listener);
        }
    }

    /**
     * Dispatch to handlers of the exact runtime type, highest priority first.
     */
    public void post(Event event) {
        Subscription[] subs = dispatch.get(event.getClass());
        if (subs == null) {
            return;
        }
        for (int i = 0; i < subs.length; i++) {
            if (event.isCancelled()) {
                break;
            }
            if (subs[i].active) {
                subs[i].invoke(event);
            }
        }
    }

    private void addSubscription(Class<? extends Event> type, Subscription sub) {
        Subscription[] current = dispatch.get(type);
        List<Subscription> next = new ArrayList<>();
        if (current != null) {
            for (Subscription s : current) {
                next.add(s);
            }
        }
        next.add(sub);
        next.sort(Comparator.comparingInt((Subscription s) -> s.priority).reversed());
        dispatch.put(type, next.toArray(new Subscription[0]));
    }

    private void removeSubscriptions(Class<? extends Event> type, Object listener) {
        Subscription[] current = dispatch.get(type);
        if (current == null) {
            return;
        }
        List<Subscription> next = new ArrayList<>();
        for (Subscription s : current) {
            if (s.listener == listener) {
                // A post() already walking the old array sees this flag and skips the listener.
                s.active = false;
            } else {
                next.add(s);
            }
        }
        if (next.isEmpty()) {
            dispatch.remove(type);
        } else {
            dispatch.put(type, next.toArray(new Subscription[0]));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<MethodMeta> metaFor(Class<?> listenerClass) {
        return CLASS_CACHE.computeIfAbsent(listenerClass, cls -> {
            List<MethodMeta> found = new ArrayList<>();
            for (Method m : cls.getDeclaredMethods()) {
                EventTarget tag = m.getAnnotation(EventTarget.class);
                if (tag == null) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1 || !Event.class.isAssignableFrom(params[0])
                        || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                found.add(new MethodMeta(m, (Class<? extends Event>) params[0], tag.priority()));
            }
            return found;
        });
    }
}
