package coldplay.module.movement;

import coldplay.event.EventPriority;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import coldplay.util.EdgeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

public class BridgeAssist extends Module {

    private final NumberSetting edgeDistance = add(new NumberSetting(
            "EdgeDistance", EdgeUtil.MIN_PROBE, EdgeUtil.MIN_PROBE, 0.5, 0.01)
            .describe("Edge probe distance in blocks; extended automatically for faster movement."));
    private EntityPlayerSP bridgingPlayer;
    private int sneakHoldTicks; // ticks still owed past the edge

    public BridgeAssist() {
        super("BridgeAssist", Category.MOVEMENT,
                "Sneaks at block edges while holding blocks, unless moving forward. Keeps protecting after the last block is used.");
    }

    @Override
    protected void onDisable() {
        bridgingPlayer = null;
        sneakHoldTicks = 0;
    }

    @EventTarget(priority = EventPriority.FALL_SAFETY)
    public void onStrafe(EventStrafe event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;

        if (player == null || world == null || !player.onGround) {
            bridgingPlayer = null;
            sneakHoldTicks = 0;
            return;
        }
        boolean holdingBlock = isHoldingBlock(player);
        if (!holdingBlock && (bridgingPlayer != player || player.getHeldItem() != null)) {
            bridgingPlayer = null;
            sneakHoldTicks = 0;
            return;
        }
        boolean atEdge = EdgeUtil.isAtAnyEdge(player, world, edgeDistance.get())
                || EdgeUtil.isApproachingEdge(mc, player, edgeDistance.get());
        // An owed sneak keeps the session alive through the pulse.
        bridgingPlayer = holdingBlock || atEdge || sneakHoldTicks > 0 ? player : null;
        // movementInput still holds the polled keys; WTap may already have rewritten the event.
        if ((atEdge || sneakHoldTicks > 0) && player.movementInput.moveForward <= 0.0F) {
            // A real sneak tap outlasts the edge by a beat, and not always the same one.
            if (atEdge) {
                sneakHoldTicks = ThreadLocalRandom.current().nextInt(2, 4);
            } else {
                sneakHoldTicks--;
            }
            event.applyForcedSneakSlowdown();
        } else {
            sneakHoldTicks = 0; // stepping forward ends the pulse
        }
    }
    private boolean isHoldingBlock(EntityPlayerSP player) {
        ItemStack stack = player.getHeldItem();
        return stack != null && stack.getItem() instanceof ItemBlock;
    }
}
