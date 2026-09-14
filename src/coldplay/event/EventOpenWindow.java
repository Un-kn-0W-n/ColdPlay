package coldplay.event;

import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.util.IChatComponent;

/** Posted from NetHandlerPlayClient.handleOpenWindow on the main thread; cancelling skips the vanilla GUI. */
public class EventOpenWindow extends Event {
    private final S2DPacketOpenWindow packet;

    public EventOpenWindow(S2DPacketOpenWindow packet) {
        this.packet = packet;
    }

    public String getGuiId() {
        return packet.getGuiId();
    }

    public int getWindowId() {
        return packet.getWindowId();
    }

    public IChatComponent getTitle() {
        return packet.getWindowTitle();
    }

    public int getSlotCount() {
        return packet.getSlotCount();
    }
}
