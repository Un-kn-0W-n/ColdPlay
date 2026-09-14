package coldplay.event;

import net.minecraft.util.MovementInput;

/** Posted from EntityPlayerSP.onLivingUpdate; edited inputs are written back onto movementInput. */
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

    /** Vanilla's sneak slowdown already ran before this event, so forcing sneak reapplies it here. */
    public void applyForcedSneakSlowdown() {
        if (sneak) {
            return;
        }
        sneak = true;
        strafe = MovementInput.slowForSneak(strafe);
        forward = MovementInput.slowForSneak(forward);
    }

    /** Yaw the movement physics use this tick; the camera yaw unless a listener replaced it. */
    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }
}
