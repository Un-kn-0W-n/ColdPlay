package coldplay.event;

import net.minecraft.util.BlockPos;

/**
 * Fired from local placement prediction and remote block updates:
 *
 * <p><b>Own placements</b> — {@code ItemBlock.onItemUse()}, {@code isRemote}-guarded: the vanilla
 * client applies the local player's placement immediately as a prediction, so the server's
 * confirming S23 later applies an identical state and is invisible at the packet seam. If the
 * server rejects the placement, its corrective S23 restores air and listeners can observe the
 * position turning replaceable again.
 *
 * <p><b>Other players' placements</b> — {@code WorldClient.invalidateRegionAndSetBlock()}, the
 * single funnel both S23PacketBlockChange and S22PacketMultiBlockChange resolve through (their
 * handlers are re-enqueued to the main thread first, so this always posts main-thread), gated on
 * a replaceable old state landing a non-air block. Own placements never re-fire here because the
 * predicted state makes the confirming set a no-op. Chunk data (S21) takes a different path and
 * never reaches this seam.
 *
 * <p>Both sites post after the world already holds the new state, so listeners may read it via
 * {@code getBlockState(pos)}. Cancellation is not checked at either hook site.
 */
public class EventBlockPlace extends Event {
    private final BlockPos pos;

    public EventBlockPlace(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }
}
