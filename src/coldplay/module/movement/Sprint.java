package coldplay.module.movement;

import coldplay.event.EventTarget;
import coldplay.event.EventStrafe;
import coldplay.module.Category;
import coldplay.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class Sprint extends Module {
    public Sprint() {
        super("Sprint", Category.MOVEMENT, "Keeps you sprinting automatically whenever you move forward.");
    }

    @EventTarget
    public void onStrafe(EventStrafe event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        if (coldplay.util.PlayerUtil.canVanillaSprint(player, event.getForward())) {
            player.setSprinting(true);
        }
    }
}
