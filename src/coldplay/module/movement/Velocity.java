package coldplay.module.movement;

import coldplay.event.EventHurt;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.Random;

public class Velocity extends Module {
    public final NumberSetting chance = add(new NumberSetting("Chance", 100.0, 10.0, 100.0, 1.0)
            .describe("% chance to auto-jump on each knockback you take."));

    private final Random random = new Random();
    private boolean pendingHit;

    public Velocity() {
        super("Velocity", Category.MOVEMENT,
                "Auto-jumps when you take knockback to reduce it. Pure vanilla input, no packets.");
    }

    @Override
    public String getSuffix() {
        return Math.round(chance.get()) + "%";
    }

    @Override
    protected void onDisable() {
        pendingHit = false;
    }

    @EventTarget
    public void onHurt(EventHurt event) {
        pendingHit = true;
    }

    @EventTarget
    public void onStrafe(EventStrafe event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        boolean freshHit = pendingHit;
        pendingHit = false;
        // Knockback leaves motionY ~+0.4; fall damage fires EventHurt too, but with motionY already zeroed.
        if (player == null || !freshHit || !player.onGround || player.motionY <= 0.0) {
            return;
        }
        boolean moving = event.getForward() != 0.0F || event.getStrafe() != 0.0F;
        if (!moving) {
            return;
        }
        if (random.nextDouble() * 100.0 < chance.get()) {
            // vanilla's jump gate consumes this later in the same tick
            player.movementInput.jump = true;
        }
    }
}
