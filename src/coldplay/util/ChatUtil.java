package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Local chat feedback written directly to the GUI so callers do not need a player instance.
 * Messages are skipped until the overlay exists.
 */
public final class ChatUtil {

    private static final String PREFIX = EnumChatFormatting.WHITE + "[ " + EnumChatFormatting.AQUA
            + "ColdPlay" + EnumChatFormatting.WHITE + " ] " + EnumChatFormatting.RESET;

    private ChatUtil() {
    }

    public static void success(String body) {
        print(EnumChatFormatting.GREEN + body);
    }

    public static void error(String body) {
        print(EnumChatFormatting.RED + body);
    }

    public static void info(String body) {
        print(EnumChatFormatting.GRAY + body);
    }

    private static void print(String coloredBody) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) {
            return;
        }
        mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(PREFIX + coloredBody));
    }
}
