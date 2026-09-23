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
    private static final String LEGIT = "Legit";
    private static final int COMBAT_TICKS = 15; // one hurt window plus slack

    public final ModeSetting mode = add(new ModeSetting("Mode", LEGIT, LEGIT)
            .describe("Legit hops with vanilla jumps while you are hitting something."));

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
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        // an open screen releases every key in vanilla, so no jump
        if (player.onGround && mc.currentScreen == null) {
            // vanilla's jump gate consumes this later in the same tick
            player.movementInput.jump = true;
        }
    }
}
