package coldplay.broker;

import net.minecraft.network.NetworkManager;

import java.util.ArrayDeque;
import java.util.function.DoubleSupplier;

/** FakeLag's pipe for the whole outgoing play stream. Every packet waits out its own delay and leaves in the order it was sent. */
public final class OutboundDelay {
    private static final OutboundDelay INSTANCE = new OutboundDelay();

    private static final class Held {
        final NetworkManager connection;
        final Runnable write;
        final long holdUntil;

        Held(NetworkManager connection, Runnable write, long holdUntil) {
            this.connection = connection;
            this.write = write;
            this.holdUntil = holdUntil;
        }
    }

    private final ArrayDeque<Held> queue = new ArrayDeque<Held>();
    private DoubleSupplier delay;

    private OutboundDelay() {
    }

    public static OutboundDelay getInstance() {
        return INSTANCE;
    }

    /** delay rolls each packet's hold in ms. */
    public synchronized void start(DoubleSupplier delay) {
        this.delay = delay;
    }

    public synchronized void stop() {
        delay = null;
        while (!queue.isEmpty()) {
            send(queue.pollFirst());
        }
    }

    /** True when the packet was taken; write puts it on the channel later. */
    public synchronized boolean hold(NetworkManager connection, Runnable write) {
        if (delay == null) {
            return false;
        }
        // the deadline is pinned here so release only has to look at the head
        queue.addLast(new Held(connection, write, System.nanoTime() + (long) (delay.getAsDouble() * 1.0E6)));
        return true;
    }

    public synchronized void release() {
        long now = System.nanoTime();
        // strictly from the head: a later packet with a shorter roll waits its turn instead of jumping ahead
        while (!queue.isEmpty() && queue.peekFirst().holdUntil - now <= 0L) {
            send(queue.pollFirst());
        }
    }

    private static void send(Held held) {
        // held for a connection that has since closed, so it must not reach the next server
        if (held.connection.isChannelOpen()) {
            held.write.run();
        }
    }
}
