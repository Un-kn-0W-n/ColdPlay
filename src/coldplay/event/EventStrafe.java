package coldplay.event;

import net.minecraft.util.MovementInput;

/**
 * Fired from {@code EntityPlayerSP.onLivingUpdate()} right after the movement input is polled and
 * before vanilla applies item-use slowdown. Listeners may overwrite the strafe/forward/sneak
 * inputs; the hook writes them back onto the player's
 * {@code movementInput}.
 *
 * <p>The producer initializes {@link #yaw} from the camera. Silent rotation may replace it with
 * server yaw so movement physics and outgoing rotation agree. {@link coldplay.util.MoveFix}
 * snaps WASD to the nearest discrete impulse under that yaw to preserve the camera-relative heading.
 */
public class EventStrafe extends Event {
    private float strafe;
    private float forward;
    private boolean sneak;
    private float yaw;

    public EventStrafe(float strafe, float forward, boolean sneak) {
        this.strafe = strafe;
        this.forward = forward;
        this.sneak = sneak;
    }

    public float getStrafe() {
        return strafe;
    }

    public void setStrafe(float strafe) {
        this.strafe = strafe;
    }

    public float getForward() {
        return forward;
    }

    public void setForward(float forward) {
        this.forward = forward;
    }

    public boolean isSneak() {
        return sneak;
    }

    public void setSneak(boolean sneak) {
        this.sneak = sneak;
    }

    /**
     * Force sneak on for this tick. Vanilla's 0.3 sneak slowdown already ran (off the real key)
     * before this event fires, so it's reapplied here — no-op if already sneaking, so multiple
     * listeners forcing sneak on the same event never double-slow.
     */
    public void applyForcedSneakSlowdown() {
        if (sneak) {
            return;
        }
        sneak = true;
        strafe = MovementInput.slowForSneak(strafe);
        forward = MovementInput.slowForSneak(forward);
    }

    /** Yaw the movement physics use this tick (defaults to the real camera yaw set by the producer). */
    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }
}
