package coldplay.event;

/**
 * Fired around {@code EntityPlayerSP.onUpdateWalkingPlayer()}, the method that builds the
 * player's outgoing position/look packets each tick.
 *
 * <p>On {@link EventPhase#PRE} a listener may overwrite {@link #setYaw(float)} and
 * {@link #setPitch(float)}. The hook serializes those values straight into this tick's packet
 * and never assigns the player's {@code rotationYaw}/{@code rotationPitch} - that is what makes
 * the rotation silent, and why there is nothing to restore afterwards.
 * {@link EventPhase#POST} carries the values chosen for queuing, before any optional network hold.
 */
public class EventMotion extends PhasedEvent {
    private float yaw;
    private float pitch;

    public EventMotion(float yaw, float pitch, EventPhase phase) {
        super(phase);
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }
}
