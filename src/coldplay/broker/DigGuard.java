package coldplay.broker;

import net.minecraft.client.Minecraft;

public final class DigGuard {

    private static boolean claimed;

    private DigGuard() {
    }

    public static void claim() {
        claimed = true;
    }

    public static void release() {
        claimed = false;
    }

    public static boolean isOwned() {
        Minecraft mc = Minecraft.getMinecraft();
        return claimed && mc.playerController != null && mc.playerController.getIsHittingBlock();
    }
}
