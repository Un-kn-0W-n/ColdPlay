package coldplay.module.visual;

import coldplay.event.EventAttackPerformed;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.util.BlockPose;

public class BlockAnimation extends Module {
    private static final String MODE_17 = "1.7";
    private static final String MODE_PUSH = "Push";

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_17, MODE_17, MODE_PUSH)
            .describe("1.7 is the real right-click block pose. Push holds the plain block pose and jabs the sword forward on each swing, also while really blocking."));

    public BlockAnimation() {
        super("BlockAnimation", Category.VISUAL,
                "Blockhit look while attacking: the sword stays in a block pose through your swings. Visual only - no packets.");
    }

    @Override
    protected void onEnable() {
        BlockPose.enable(() -> MODE_PUSH.equals(mode.get()));
    }

    @Override
    protected void onDisable() {
        BlockPose.disable();
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        BlockPose.onAttack();
    }
}
