package coldplay.module.utility;

import coldplay.event.EventMouse;
import coldplay.event.EventTarget;
import coldplay.friend.FriendManager;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.ChatUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

public class MiddleClickFriend extends Module {

    private static final int MIDDLE_BUTTON = 2;
    private final Runnable persistence;

    public MiddleClickFriend(Runnable persistence) {
        super("MiddleClickFriend", Category.UTILITY,
                "Middle-click a player to add/remove them from your friend list.");
        this.persistence = persistence;
    }

    @EventTarget
    public void onMouse(EventMouse event) {
        if (event.getButton() != MIDDLE_BUTTON || !event.isPressed()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        MovingObjectPosition target = mc.objectMouseOver;
        if (target == null
                || target.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                || !(target.entityHit instanceof EntityPlayer)) {
            return;
        }

        String name = target.entityHit.getName();
        boolean nowFriend = FriendManager.getInstance().toggle(name);
        persistence.run();
        if (nowFriend) {
            ChatUtil.success("Added " + name + " to friend list!");
        } else {
            ChatUtil.error("Removed " + name + " from friend list!");
        }
    }
}
