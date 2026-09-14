package coldplay.event;

import net.minecraft.util.BlockPos;

/** Posted from PlayerControllerMP.clickBlock and onPlayerDamageBlock before the dig packets are queued. */
public class EventDig extends Event {
    private final BlockPos pos;

    public EventDig(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }
}
