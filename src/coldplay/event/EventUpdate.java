package coldplay.event;

/**
 * The master client tick, fired around {@code Minecraft.runTick()} (~20 times per second).
 * {@link EventPhase#PRE} is the standard place for per-tick module logic (target selection, timers,
 * movement decisions); {@link EventPhase#POST} runs after the world and player have ticked.
 *
 * <p>Fires even at the main menu, so listeners must null-check
 * {@code Minecraft.getMinecraft().thePlayer} / {@code theWorld}.
 */
public class EventUpdate extends PhasedEvent {
    public EventUpdate(EventPhase phase) {
        super(phase);
    }
}
