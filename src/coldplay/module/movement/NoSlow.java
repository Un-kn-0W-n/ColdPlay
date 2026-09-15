package coldplay.module.movement;

import coldplay.broker.SlotBounce;
import coldplay.broker.SwordBlock;
import coldplay.broker.UseHold;
import coldplay.event.EventMotion;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemSword;

public class NoSlow extends Module {
    private static final String HYPIXEL = "Hypixel";
    private static NoSlow instance;
    public final ModeSetting mode = add(new ModeSetting("Mode", "Vanilla", "Vanilla", HYPIXEL)
            .describe("Vanilla skips the slowdown for every item. Hypixel only skips it while blocking with a "
                    + "sword and flicks the held slot each tick so the server stops counting the block."));
    private EntityPlayerSP bouncePlayer;
    private int bounceTick;

    public NoSlow() {
        super("NoSlow", Category.MOVEMENT, "Removes the slowdown from blocking, eating, drinking and drawing a bow.");
        instance = this;
    }

    /** Called from EntityPlayerSP.onLivingUpdate before the item-use slowdown is applied. */
    public static boolean skipSlowdown(EntityPlayerSP player) {
        NoSlow m = instance;
        // the Blink burst's slowed tick is the one the server really processes as blocking
        // an AutoBlock block stays slowed, and the bounce would end it on Hypixel
        if (m == null || !m.isEnabled() || !player.isUsingItem() || SwordBlock.getInstance().slowedThisTick()
                || UseHold.getInstance().isHeld()) {
            return false;
        }
        if (!HYPIXEL.equals(m.mode.get())) {
            return true;
        }
        if (!(player.getItemInUse().getItem() instanceof ItemSword)) {
            return false;
        }
        m.bouncePlayer = player;
        m.bounceTick = player.ticksExisted;
        return true;
    }

    @Override
    protected void onDisable() {
        bouncePlayer = null;
    }

    @EventTarget
    public void onMotion(EventMotion event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (!event.isPre() || player == null || player != bouncePlayer || player.ticksExisted != bounceTick) {
            return;
        }
        bouncePlayer = null;
        // must reach the server before this tick's C03, which was moved without the slowdown
        SlotBounce.getInstance().bounce("NoSlow");
    }
}
