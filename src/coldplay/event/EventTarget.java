package coldplay.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code public void method(SomeEvent e)} as an event handler. The owning object must be
 * passed to {@link coldplay.EventHandler#register(Object)} for the method to receive events.
 * {@link coldplay.module.ModuleManager} registers enabled modules.
 *
 * <p>{@link #priority()} orders handlers for the same event type: higher runs first (default 0).
 * Use it when one listener must cancel or rewrite an event before another reads it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventTarget {
    int priority() default 0;
}
