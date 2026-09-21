package coldplay.module.combat;

import coldplay.event.EventPreAttack;
import coldplay.event.EventPriority;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;

/** Hops with real jumps while you trade hits, so most hits land while falling and crit. */
public class Criticals extends Module {
    private static final String HYPIXEL = "Hypixel";
    private static final int COMBAT_TICKS = 15; // one hurt window plus slack

    public final ModeSetting mode = add(new ModeSetting("Mode", HYPIXEL, HYPIXEL)
            .describe("Hypixel hops with vanilla jumps while you are hitting something."));

    private int combatTicks;

    public Criticals() {
        super("Criticals", Category.COMBAT, "Jumps while you fight so your hits land as critical hits.");
    }

    @Override
    protected void onDisable() {
        combatTicks = 0;
    }

    @EventTarget
    public void onPreAttack(EventPreAttack event) {
        if (event.getTarget() instanceof EntityLivingBase) {
            combatTicks = COMBAT_TICKS;
        }
    }

    @EventTarget(priority = EventPriority.DRAIN - 1)
    public void onStrafe(EventStrafe event) {
        if (combatTicks <= 0) {
            return;
        }
        combatTicks--;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player.onGround) {
            // vanilla's jump gate consumes this later in the same tick
            player.movementInput.jump = true;
        }
    }
}
