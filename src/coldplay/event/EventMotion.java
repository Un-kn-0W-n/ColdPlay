package coldplay.event;

/** Posted around EntityPlayerSP.onUpdateWalkingPlayer; yaw and pitch set on PRE only affect the packet. */
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
