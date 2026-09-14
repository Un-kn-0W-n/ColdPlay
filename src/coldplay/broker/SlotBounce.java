package coldplay.broker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/** Flicks the server's held slot away and back without moving the client's slot. */
public final class SlotBounce {
    private static final SlotBounce INSTANCE = new SlotBounce();

    private SlotBounce() {
    }

    public static SlotBounce getInstance() {
        return INSTANCE;
    }

    /** {@code slot} must be the slot the server already holds; the two stops in between never repeat it. */
    public void bounce(String who, int slot) {
        NetHandlerPlayClient handler = Minecraft.getMinecraft().getNetHandler();
        if (handler == null || slot < 0 || slot > 8) {
            return;
        }
        int[] slots = {slot % 8 + 1, slot % 7 + 2, slot};
        PacketLog.getInstance().tagged(who, () -> {
            for (int s : slots) {
                handler.addToSendQueue(new C09PacketHeldItemChange(s));
            }
        });
    }
}
