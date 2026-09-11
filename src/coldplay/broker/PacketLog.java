package coldplay.broker;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.DataWatcher;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Fed from both NetworkManager choke points, so it runs on the netty thread as often as the client thread. */
public final class PacketLog {

    private static final PacketLog INSTANCE = new PacketLog();
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<Class<?>, Field[]>();

    // unbounded by request; a few hundred bytes a row, so a million packets is a few hundred MB
    private final Object rowLock = new Object();
    private ArrayDeque<String> rows = new ArrayDeque<String>();
    private volatile boolean enabled;
    private final ThreadLocal<String> origin = new ThreadLocal<String>();
    private final Map<Packet<?>, Prepared> origins = Collections.synchronizedMap(new WeakHashMap<Packet<?>, Prepared>());
    private long sequence;
    private volatile long tick, frame;

    private static final class Prepared {
        final String origin, producer, context;
        final long nanos;

        Prepared(String origin, String producer, String context, long nanos) {
            this.origin = origin;
            this.producer = producer;
            this.context = context;
            this.nanos = nanos;
        }
    }

    private PacketLog() {
    }

    public static PacketLog getInstance() {
        return INSTANCE;
    }

    public void setEnabled(boolean enabled) {
        if (enabled && !this.enabled) tick = frame = 0L;
        this.enabled = enabled;
    }

    public void setOrigin(String origin) {
        if (origin == null) this.origin.remove(); else this.origin.set(origin);
    }

    public void nextTick() { tick++; }
    public void nextFrame() { frame++; }

    public boolean isEnabled() { return enabled; }

    /** Capture the producer before a hold, including an explicitly unknown producer. */
    public void prepare(Packet<?> packet) {
        if (!enabled) return;
        synchronized (origins) {
            if (!origins.containsKey(packet)) origins.put(packet, captureProducer(packet, "prepare"));
        }
    }

    /** Runs {@code action} with its outbound packets attributed to {@code who}. */
    public void tagged(String who, Runnable action) {
        String previous = origin.get();
        setOrigin(who);
        try {
            action.run();
        } finally {
            setOrigin(previous);
        }
    }

    /** Never throws: an exception here would be netty's exceptionCaught, i.e. a disconnect. */
    public void record(Packet<?> packet, boolean outbound) {
        record(packet, outbound, 0);
    }

    public void record(Packet<?> packet, boolean outbound, int connection) {
        record(packet, outbound ? "OUT" : "IN", connection);
    }

    /** A rejected automated action is diagnostic evidence, never an outgoing packet row. */
    public void dropped(Packet<?> packet, int connection) {
        record(packet, "DROP", connection);
    }

    private void record(Packet<?> packet, String direction, int connection) {
        if (!enabled) {
            return;
        }
        long time = System.currentTimeMillis(), nanos = System.nanoTime();
        long atTick = tick, atFrame = frame;
        String fields;
        try {
            fields = describe(packet);
        } catch (Throwable t) {
            fields = "!" + t;
        }
        String who = "Server", producer = "NetworkManager.channelRead0";
        String context = "thread=" + Thread.currentThread().getName();
        if (!"IN".equals(direction)) {
            Prepared prepared = origins.remove(packet);
            if (prepared == null) prepared = captureProducer(packet, "dispatch-fallback");
            who = prepared.origin;
            producer = prepared.producer;
            context = prepared.context + "; dispatchThread=" + Thread.currentThread().getName()
                    + "; queueDelayNs=" + Math.max(0L, nanos - prepared.nanos);
        }
        append(time, nanos, atTick, atFrame, direction, packet.getClass().getSimpleName(), who, fields,
                connection, producer, context);
    }

    private Prepared captureProducer(Packet<?> packet, String stage) {
        long nanos = System.nanoTime();
        String who = origin.get();
        String producer = "Unknown";
        String context = "stage=" + stage + "; preparedNanoTime=" + nanos + "; preparedTick=" + tick
                + "; preparedFrame=" + frame + "; preparedThread=" + Thread.currentThread().getName();
        try {
            // Only gameplay sends need a stack walk. Keep transaction/keepalive traffic cheap.
            if (packet instanceof C03PacketPlayer || packet instanceof C02PacketUseEntity
                    || packet instanceof C0APacketAnimation || packet instanceof C08PacketPlayerBlockPlacement
                    || packet instanceof C07PacketPlayerDigging || packet instanceof C0EPacketClickWindow
                    || packet instanceof C09PacketHeldItemChange || packet instanceof C0BPacketEntityAction) {
                for (StackTraceElement at : Thread.currentThread().getStackTrace()) {
                    String cls = at.getClassName();
                    if (!isProducer(at)) continue;
                    if ("Unknown".equals(producer)) producer = cls + "#" + at.getMethodName();
                    if (who == null && cls.startsWith("coldplay.module.")
                            && !cls.equals("coldplay.module.Module") && !cls.equals("coldplay.module.ModuleManager")) {
                        who = cls.substring(cls.lastIndexOf('.') + 1).split("\\$")[0];
                    }
                }
                context += gameplayContext(packet);
            } else if (packet instanceof C00PacketKeepAlive || packet instanceof C0FPacketConfirmTransaction) {
                producer = "not-captured:protocol";
            }
        } catch (Throwable failure) {
            context += "; contextError=" + failure.getClass().getSimpleName();
        }
        return new Prepared(who == null || who.isEmpty() ? "Unknown" : who, producer, context, nanos);
    }

    private static boolean isProducer(StackTraceElement at) {
        String cls = at.getClassName();
        return (cls.startsWith("coldplay.") || cls.startsWith("net.minecraft."))
                && !cls.equals(PacketLog.class.getName())
                && !cls.equals("net.minecraft.network.NetworkManager")
                && !(cls.equals("net.minecraft.client.network.NetHandlerPlayClient")
                && at.getMethodName().equals("addToSendQueue"));
    }

    /** A call site is evidence of a code path, never proof of physical mouse input. */
    public static String caller() {
        for (StackTraceElement at : Thread.currentThread().getStackTrace()) {
            if (isProducer(at) && !at.getClassName().equals("coldplay.module.ModuleManager")) {
                return at.getClassName() + "#" + at.getMethodName();
            }
        }
        return "Unknown";
    }

    private static String gameplayContext(Packet<?> packet) {
        Minecraft mc = Minecraft.getMinecraft();
        // Never sample mutable world/camera state from a Netty or export thread.
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.thePlayer == null) return "; clientContext=unavailable";
        String context = "; cameraYaw=" + mc.thePlayer.rotationYaw + "; cameraPitch=" + mc.thePlayer.rotationPitch
                + "; " + RotationManager.getInstance().packetLogContext()
                + "; gui=" + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName())
                + "; focus=" + mc.inGameHasFocus + "; fps=" + Minecraft.getDebugFPS()
                + "; sensitivity=" + mc.gameSettings.mouseSensitivity
                + "; playerHealth=" + mc.thePlayer.getHealth()
                + "; heldSlot=" + mc.thePlayer.inventory.currentItem;
        if (packet instanceof C02PacketUseEntity && mc.theWorld != null) {
            Entity target = ((C02PacketUseEntity) packet).getEntityFromWorld(mc.theWorld);
            if (target != null) context += "; targetId=" + target.getEntityId()
                    + "; targetType=" + target.getClass().getSimpleName()
                    + "; targetBox=" + target.getEntityBoundingBox();
        }
        return context;
    }

    public void note(String kind, String fields) {
        note(kind, fields, false);
    }

    /** Explicit export context may be added after capture has stopped. */
    public void note(String kind, String fields, boolean whenDisabled) {
        if (enabled || whenDisabled) append(System.currentTimeMillis(), System.nanoTime(), tick, frame,
                "META", kind, "", fields, 0, "", "");
    }

    private void append(long time, long nanos, long atTick, long atFrame, String direction,
                        String packet, String who, String fields, int connection, String producer, String context) {
        String prefix = time + "," + direction + "," + packet + "," + csv(who == null ? "" : who) + "," + csv(fields);
        synchronized (rowLock) {
            // Sequence orders enqueue, not server arrival. Concurrent entry times may run backwards.
            rows.add(prefix + "," + (++sequence) + "," + nanos + "," + atTick + "," + atFrame + "," + connection
                    + "," + csv(producer) + "," + csv(context));
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ') + '"';
    }

    /** Preserve small reconstruction payloads; chunk bytes remain summarized. */
    private static String describe(Object packet) throws IllegalAccessException {
        StringBuilder out = new StringBuilder();
        for (Field field : fields(packet.getClass())) {
            Object value = field.get(packet);
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(field.getName()).append('=');
            appendValue(out, value);
        }
        return out.toString().replaceAll("[\r\n]+", " ");
    }

    private static void appendValue(StringBuilder out, Object value) {
        if (value instanceof ItemStack) {
            ItemStack stack = (ItemStack) value;
            out.append("{item=").append(Item.getIdFromItem(stack.getItem())).append(",count=")
                    .append(stack.stackSize).append(",damage=").append(stack.getItemDamage())
                    .append(",nbt=").append(stack.getTagCompound()).append('}');
        } else if (value instanceof DataWatcher) {
            appendValue(out, ((DataWatcher) value).getAllWatched());
        } else if (value instanceof DataWatcher.WatchableObject) {
            DataWatcher.WatchableObject watch = (DataWatcher.WatchableObject) value;
            out.append("{id=").append(watch.getDataValueId()).append(",type=").append(watch.getObjectType()).append(",value=");
            appendValue(out, watch.getObject());
            out.append('}');
        } else if (value instanceof ByteBuf) {
            ByteBuf bytes = (ByteBuf) value;
            int count = Math.min(bytes.readableBytes(), 65536);
            out.append("hex[").append(bytes.readableBytes()).append("]=")
                    .append(ByteBufUtil.hexDump(bytes, bytes.readerIndex(), count));
            if (count < bytes.readableBytes()) out.append("...truncated");
        } else if (value instanceof IChatComponent) {
            // Component iteration includes the component itself; recurse through its JSON serializer instead.
            out.append(IChatComponent.Serializer.componentToJson((IChatComponent) value));
        } else if (value instanceof Iterable<?>) {
            out.append('[');
            boolean comma = false;
            for (Object element : (Iterable<?>) value) {
                if (comma) out.append(',');
                appendValue(out, element);
                comma = true;
            }
            out.append(']');
        } else if (value instanceof int[] || value instanceof ItemStack[]) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) out.append(',');
                appendValue(out, Array.get(value, i));
            }
            out.append(']');
        } else if (value != null && value.getClass().isArray()) {
            out.append(value.getClass().getSimpleName()).append('[').append(Array.getLength(value)).append(']');
        } else {
            out.append(value);
        }
    }

    private static Field[] fields(Class<?> type) {
        Field[] cached = FIELDS.get(type);
        if (cached == null) {
            List<Field> found = new ArrayList<Field>();
            for (Class<?> c = type; c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        found.add(field);
                    }
                }
            }
            cached = found.toArray(new Field[0]);
            FIELDS.put(type, cached);
        }
        return cached;
    }

    /** Detach atomically; arrivals during I/O belong to the next export. Failed writes restore order. */
    public synchronized int writeCsv(Writer out) throws IOException {
        ArrayDeque<String> batch;
        synchronized (rowLock) {
            batch = rows;
            rows = new ArrayDeque<String>();
        }
        try {
            out.write("timeMs,direction,packet,origin,fields,sequence,nanoTime,tick,frame,connection,producer,context\n");
            for (String row : batch) {
                out.write(row);
                out.write('\n');
            }
            out.flush();
        } catch (IOException | RuntimeException failure) {
            synchronized (rowLock) {
                batch.addAll(rows);
                rows = batch;
            }
            throw failure;
        }
        return batch.size();
    }
}
