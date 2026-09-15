package coldplay.broker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/** Flicks the server's held slot away and back without moving the client's slot. */
public final class SlotBounce {
    private static final SlotBounce INSTANCE = new SlotBounce();

    private EntityPlayerSP lastPlayer;
    private int lastTick;

    private SlotBounce() {
    }

    public static SlotBounce getInstance() {
        return INSTANCE;
    }

    /** At most once per tick, from and back to the slot the server holds; the two stops in between never repeat it. */
    public void bounce(String who) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (player == null || handler == null || mc.playerController == null
                || player == lastPlayer && player.ticksExisted == lastTick) {
            return;
        }
        int slot = mc.playerController.getCurrentPlayerItem();
        if (slot < 0 || slot > 8) {
            return;
        }
        lastPlayer = player;
        lastTick = player.ticksExisted;
        int[] slots = {slot % 8 + 1, slot % 7 + 2, slot};
        PacketLog.getInstance().tagged(who, () -> {
            for (int s : slots) {
                handler.addToSendQueue(new C09PacketHeldItemChange(s));
            }
        });
    }
}
