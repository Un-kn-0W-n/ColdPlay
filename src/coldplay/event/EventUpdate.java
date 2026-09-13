package coldplay.event;

/** Posted around Minecraft.runTick; also fires at the main menu with no world loaded. */
public class EventUpdate extends PhasedEvent {
    public EventUpdate(EventPhase phase) {
        super(phase);
    }
}
