package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import java.util.ArrayDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Delays applying a BackTrack target's incoming movement updates by a configured window. */
public final class PositionGuard {
    private static final PositionGuard INSTANCE = new PositionGuard();
    private static final int MAX_PENDING = 128;

    private static final class Pending {
        final double x, y, z;
        final float yaw, pitch;
        final boolean hasLook, onGround, teleport;
        final long releaseAtNanos;

        Pending(double x, double y, double z, float yaw, float pitch,
                boolean hasLook, boolean onGround, boolean teleport, long releaseAtNanos) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.hasLook = hasLook;
            this.onGround = onGround;
            this.teleport = teleport;
            this.releaseAtNanos = releaseAtNanos;
        }
    }

    private Entity target;
    private long delayLo;
    private long delayHi;
    private final ArrayDeque<Pending> queue = new ArrayDeque<Pending>();
    private long lastReleaseAtNanos;

    private PositionGuard() {
    }

    public static PositionGuard getInstance() {
        return INSTANCE;
    }

    public boolean interceptTeleport(Entity entity, double x, double y, double z,
                                     float yaw, float pitch, boolean onGround) {
        return intercept(entity, x, y, z, yaw, pitch, true, onGround, true);
    }

    public boolean interceptRelative(Entity entity, double x, double y, double z,
                                     float yaw, float pitch, boolean hasLook, boolean onGround) {
        return intercept(entity, x, y, z, yaw, pitch, hasLook, onGround, false);
    }

    private boolean intercept(Entity entity, double x, double y, double z, float yaw, float pitch,
                              boolean hasLook, boolean onGround, boolean teleport) {
        if (target == null || entity != target) {
            return false;
        }
        Entity player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return false;
        }
        if (player.getDistanceSq(x, y, z) < player.getDistanceSq(entity.posX, entity.posY, entity.posZ)) {
            queue.clear();
            lastReleaseAtNanos = 0L;
            return false;
        }
        if (queue.size() >= MAX_PENDING) {
            release();
            return false;
        }

        long delay = delayLo == delayHi ? delayLo
                : ThreadLocalRandom.current().nextLong(delayLo, delayHi + 1L);
        long releaseAt = Math.max(lastReleaseAtNanos,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay));
        lastReleaseAtNanos = releaseAt;
        queue.addLast(new Pending(x, y, z, yaw, pitch, hasLook, onGround, teleport, releaseAt));
        return true;
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre() || target == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || target.isDead || target.worldObj != mc.theWorld) {
            clear();
            return;
        }

        long now = System.nanoTime();
        Pending newestDue = null;
        while (!queue.isEmpty() && queue.peekFirst().releaseAtNanos <= now) {
            newestDue = queue.removeFirst();
        }
        if (newestDue != null) {
            apply(newestDue);
        }
    }

    private void apply(Pending pending) {
        double x = pending.x;
        double y = pending.y;
        double z = pending.z;
        if (pending.teleport
                && Math.abs(target.posX - x) < 0.03125D
                && Math.abs(target.posY - y) < 0.015625D
                && Math.abs(target.posZ - z) < 0.03125D) {
            x = target.posX;
            y = target.posY;
            z = target.posZ;
        }
        target.setPositionAndRotation2(x, y, z,
                pending.hasLook ? pending.yaw : target.rotationYaw,
                pending.hasLook ? pending.pitch : target.rotationPitch,
                3, pending.teleport);
        target.onGround = pending.onGround;
    }

    public void setTarget(Entity entity, long lo, long hi) {
        if (entity != target) {
            release();
        }
        target = entity;
        delayLo = Math.max(0L, Math.min(lo, hi));
        delayHi = Math.max(delayLo, Math.max(lo, hi));
    }

    public void release() {
        if (target != null && !target.isDead && !queue.isEmpty()
                && target.worldObj == Minecraft.getMinecraft().theWorld) {
            apply(queue.peekLast());
        }
        clear();
    }

    private void clear() {
        queue.clear();
        target = null;
        lastReleaseAtNanos = 0L;
    }

    public double[] getRealPos() {
        Pending newest = queue.peekLast();
        return newest == null ? null : new double[]{newest.x, newest.y, newest.z};
    }

    public Entity getTarget() {
        return target;
    }
}
