package coldplay.event;

import net.minecraft.util.BlockPos;

/** Posted from ItemBlock.onItemUse and WorldClient.invalidateRegionAndSetBlock after the block is set. */
public class EventBlockPlace extends Event {
    private final BlockPos pos;

    public EventBlockPlace(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }
}
