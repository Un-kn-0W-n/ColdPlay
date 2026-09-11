package coldplay.broker;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Per-connection pose at the final dispatch boundary, after optional holds/reordering. */
public final class PlayerPacketState {
    public static final class Pose {
        public final double x, y, z;
        public final float yaw, pitch;
        public final boolean hasPosition, hasLook;
        public final long epoch;

        private Pose(double x, double y, double z, float yaw, float pitch,
                     boolean hasPosition, boolean hasLook, long epoch) {
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.hasPosition = hasPosition; this.hasLook = hasLook; this.epoch = epoch;
        }

        public boolean isKnown() { return hasPosition && hasLook; }
        public Vec3 eyes(float height) { return new Vec3(x, y + height, z); }
        public Vec3 look() { return RotationManager.lookVec(yaw, pitch); }

    }

    private static final class Validation {
        final long epoch;
        final Predicate<Pose> ray;
        Validation(Pose pose, Predicate<Pose> ray) { this.epoch = pose.epoch; this.ray = ray; }
    }

    private volatile Pose pose = new Pose(0, 0, 0, 0, 0, false, false, 0);
    private boolean actionInWindow;
    private final ThreadLocal<Validation> validating = new ThreadLocal<Validation>();
    // Weak keys also release contexts for packets discarded by a world/connection transition.
    private final Map<Packet<?>, Validation> expected = Collections.synchronizedMap(new WeakHashMap<Packet<?>, Validation>());

    public Pose getPose() { return pose; }

    public synchronized void reset() {
        pose = new Pose(0, 0, 0, 0, 0, false, false, pose.epoch + 1);
        actionInWindow = false;
    }

    /** A role change invalidates prepared intent without changing the last dispatched pose. */
    public synchronized void invalidateActions() {
        Pose old = pose;
        pose = new Pose(old.x, old.y, old.z, old.yaw, old.pitch,
                old.hasPosition, old.hasLook, old.epoch + 1);
    }

    /** Scope the exact pose an automated ray gate accepted to its swing and attack packets. */
    public void atPose(Pose pose, Predicate<Pose> ray, Runnable action) {
        Validation previous = validating.get();
        validating.set(new Validation(pose, ray));
        try {
            action.run();
        } finally {
            if (previous == null) validating.remove(); else validating.set(previous);
        }
    }

    /** Called before a packet may be buffered; never replaces an already captured context. */
    public void prepare(Packet<?> packet) {
        Validation accepted = validating.get();
        if (accepted != null && (packet instanceof C02PacketUseEntity || packet instanceof C0APacketAnimation)) {
            synchronized (expected) {
                if (!expected.containsKey(packet)) expected.put(packet, accepted);
            }
        }
    }

    /** Do not dispatch a delayed automated action on a pose or action window it did not validate. */
    public synchronized boolean accept(Packet<?> packet) {
        Validation accepted = expected.remove(packet);
        if (accepted != null && (!pose.isKnown() || accepted.epoch != pose.epoch
                || actionInWindow || !accepted.ray.test(pose))) return false;
        if (packet instanceof C03PacketPlayer) {
            C03PacketPlayer movement = (C03PacketPlayer) packet;
            Pose old = pose;
            boolean position = movement.isMoving() && movement.getPositionY() != -999.0D;
            boolean riding = movement.isMoving() && movement.getPositionY() == -999.0D;
            pose = new Pose(position ? movement.getPositionX() : old.x,
                    position ? movement.getPositionY() : old.y,
                    position ? movement.getPositionZ() : old.z,
                    movement.getRotating() ? movement.getYaw() : old.yaw,
                    movement.getRotating() ? movement.getPitch() : old.pitch,
                    !riding && (position || old.hasPosition), movement.getRotating() || old.hasLook, old.epoch);
            actionInWindow = false;
        } else if (packet instanceof C02PacketUseEntity || packet instanceof C08PacketPlayerBlockPlacement
                || packet instanceof C0EPacketClickWindow
                || packet instanceof C07PacketPlayerDigging
                && ((C07PacketPlayerDigging) packet).getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
            actionInWindow = true;
        }
        return true;
    }
}
