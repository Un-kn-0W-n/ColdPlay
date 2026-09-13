package coldplay.module.visual;

import coldplay.event.EventAttackPerformed;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.BlockPose;

public class BlockAnimation extends Module {
    public BlockAnimation() {
        super("BlockAnimation", Category.VISUAL,
                "Blockhit look while attacking: block pose with swings rolling through it. Visual only - no packets.");
    }

    @Override
    protected void onEnable() {
        BlockPose.set(true);
    }

    @Override
    protected void onDisable() {
        BlockPose.set(false);
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        BlockPose.onAttack();
    }
}
