package coldplay.event;

import net.minecraft.util.BlockPos;

/**
 * Fired from {@code PlayerControllerMP.clickBlock()} (fresh dig target) and
 * {@code PlayerControllerMP.onPlayerDamageBlock()} (every mining tick) — the funnel every block dig
 * in the client flows through, whether driven by the attack keybind or injected by a module
 * (Breaker). Posted before vanilla captures {@code currentItemHittingBlock} and before the C07
 * digging packets are queued, and the hook site flushes {@code syncCurrentPlayItem} right after
 * dispatch — so a listener that changes {@code inventory.currentItem} (AutoTool) gets its C09 on
 * the wire ahead of the dig's C07s. Cancellation is not checked at the hook sites.
 */
public class EventDig extends Event {
    private final BlockPos pos;

    public EventDig(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }
}
