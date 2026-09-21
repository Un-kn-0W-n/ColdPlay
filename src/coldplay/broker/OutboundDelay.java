package coldplay.broker;

import net.minecraft.network.NetworkManager;

import java.util.ArrayDeque;
import java.util.function.DoubleSupplier;

/** FakeLag's hold on outgoing play packets. Each one reaches the channel once its delay has passed, in issue order. */
public final class OutboundDelay {
    private static final OutboundDelay INSTANCE = new OutboundDelay();

    private static final class Held {
        final NetworkManager connection;
        final Runnable write;
        final long due;

        Held(NetworkManager connection, Runnable write, long due) {
            this.connection = connection;
            this.write = write;
            this.due = due;
        }
    }

    private final ArrayDeque<Held> held = new ArrayDeque<Held>();
    private DoubleSupplier delay;

    private OutboundDelay() {
    }

    public static OutboundDelay getInstance() {
        return INSTANCE;
    }

    /** delay rolls one packet's delay in ms. */
    public synchronized void start(DoubleSupplier delay) {
        this.delay = delay;
    }

    public synchronized void stop() {
        delay = null;
        while (!held.isEmpty()) {
            send(held.pollFirst());
        }
    }

    /** A later packet with a shorter roll waits behind the ones before it. */
    public synchronized void release() {
        long now = System.nanoTime();
        while (!held.isEmpty() && held.peekFirst().due - now <= 0L) {
            send(held.pollFirst());
        }
    }

    /** True when the packet was taken; write puts it on the channel later. */
    public synchronized boolean hold(NetworkManager connection, Runnable write) {
        if (delay == null) {
            return false;
        }
        held.addLast(new Held(connection, write, System.nanoTime() + (long) (delay.getAsDouble() * 1.0E6)));
        return true;
    }

    private static void send(Held entry) {
        // packets held for a closed connection are dropped
        if (entry.connection.isChannelOpen()) {
            entry.write.run();
        }
    }
}
