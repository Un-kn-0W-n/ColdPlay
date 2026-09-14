package coldplay.module.movement;

import coldplay.broker.ActionGuard;
import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.util.InvUtil;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.world.World;

public class InvMove extends Module {
    private static final String HYPIXEL = "Hypixel";
    private static final int MAX_WAIT_TICKS = 20;
    private static InvMove instance;
    public final ModeSetting mode = add(new ModeSetting("Mode", "Vanilla", "Vanilla", HYPIXEL));
    private final ArrayDeque<int[]> clicks = new ArrayDeque<>();
    private EntityPlayerSP player;
    private World world;
    private NetHandlerPlayClient connection;
    private Container container;
    private GuiContainer screen;
    private GameSettings controlledKeys;
    private boolean allowedUserInput, closeRequested, sending;
    private int waitTicks, windowId;

    public InvMove() {
        super("InvMove", Category.MOVEMENT, "Lets you move while an inventory/container GUI is open.");
        instance = this;
    }

    public static boolean deferClick(EntityPlayer playerIn, int windowId, int slotId, int button, int mode) {
        InvMove m = instance;
        Minecraft mc = Minecraft.getMinecraft();
        if (m == null || m.sending || !m.isEnabled() || !HYPIXEL.equals(m.mode.get())
                || mc.thePlayer != playerIn || !(mc.currentScreen instanceof GuiContainer)
                || mc.playerController.isInCreativeMode() || playerIn.isRiding()
                || ((GuiContainer) mc.currentScreen).inventorySlots != playerIn.openContainer) {
            return false;
        }
        if (!m.sameSession()) {
            m.cancelPending();
            m.player = mc.thePlayer;
            m.world = mc.theWorld;
            m.connection = mc.thePlayer.sendQueue;
            m.container = playerIn.openContainer;
            m.windowId = windowId;
            m.screen = (GuiContainer) mc.currentScreen;
            m.allowedUserInput = m.screen.allowUserInput;
        }
        m.clicks.addLast(new int[] {slotId, button, mode});
        // Keep later GUI events behind this click so they see its updated cursor and slot state.
        m.screen.allowUserInput = false;
        m.updateKeys(false);
        return true;
    }

    public static boolean isInputPaused(GuiScreen screen) {
        InvMove m = instance;
        return m != null && m.isEnabled() && HYPIXEL.equals(m.mode.get())
                && m.screen == screen && m.sameSession() && !m.clicks.isEmpty();
    }

    /** Delay the close itself; window 0 remains the same container even after its GUI closes. */
    public static boolean deferClose(EntityPlayerSP player) {
        InvMove m = instance;
        if (m == null || m.player != player || !isInputPaused(Minecraft.getMinecraft().currentScreen)) {
            return false;
        }
        m.closeRequested = true;
        return true;
    }

    public static void screenClosed(GuiScreen screen) {
        InvMove m = instance;
        if (m != null && m.screen == screen) {
            m.cancelPending();
        }
    }

    @Override
    protected void onDisable() {
        EntityPlayerSP closingPlayer = closeRequested && sameSession() ? player : null;
        cancelPending();
        releaseKeys();
        if (closingPlayer != null) closingPlayer.closeScreen();
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) return;
        if (screen != null && (!sameSession() || !HYPIXEL.equals(mode.get()) || ++waitTicks > MAX_WAIT_TICKS)) {
            EntityPlayerSP closingPlayer = closeRequested && sameSession() ? player : null;
            cancelPending();
            if (closingPlayer != null) closingPlayer.closeScreen();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || !(mc.currentScreen instanceof GuiContainer)) {
            releaseKeys();
            return;
        }
        updateKeys(clicks.isEmpty());
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onStrafe(EventStrafe event) {
        if (!sameSession() || clicks.isEmpty()) return;
        event.setForward(0.0F);
        event.setStrafe(0.0F);
        event.setSneak(false);
        player.movementInput.jump = false;
    }

    @EventTarget
    public void onMotion(EventMotion event) {
        if (event.isPre() || clicks.isEmpty()) return;
        if (!isEnabled() || !HYPIXEL.equals(mode.get()) || !sameSession()) {
            cancelPending();
            return;
        }
        // Run after a real movement update, once input and residual horizontal motion have settled.
        if (InvUtil.isPlayerMoving(player) || player.motionX * player.motionX + player.motionZ * player.motionZ > 9.0E-4D
                || !ActionGuard.getInstance().tryReserve(this)) return;
        EntityPlayerSP currentPlayer = player;
        GuiContainer currentScreen = screen;
        Container currentContainer = container;
        boolean close = closeRequested;
        sending = true;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            while (!clicks.isEmpty() && sameSession()) {
                int[] click = clicks.removeFirst();
                mc.playerController.windowClick(windowId, click[0], click[1], click[2], player);
            }
        } finally {
            sending = false;
            cancelPending();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (close && mc.thePlayer == currentPlayer && mc.currentScreen == currentScreen
                && currentPlayer.openContainer == currentContainer) currentPlayer.closeScreen();
    }

    private boolean sameSession() {
        Minecraft mc = Minecraft.getMinecraft();
        return screen != null && mc.currentScreen == screen && mc.thePlayer == player && mc.theWorld == world
                && player.sendQueue == connection && player.openContainer == container && container.windowId == windowId;
    }

    private void cancelPending() {
        clicks.clear();
        if (screen != null) screen.allowUserInput = allowedUserInput;
        screen = null;
        player = null;
        world = null;
        connection = null;
        container = null;
        closeRequested = false;
        waitTicks = 0;
    }

    private void updateKeys(boolean physical) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        if (controlledKeys != null && controlledKeys != settings) releaseKeys();
        controlledKeys = settings;
        for (KeyBinding key : movementKeys(settings)) {
            KeyBinding.setKeyBindState(key.getKeyCode(), physical && GameSettings.isKeyDown(key));
        }
    }

    private void releaseKeys() {
        if (controlledKeys == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean physical = mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null
                && mc.gameSettings == controlledKeys;
        for (KeyBinding key : movementKeys(controlledKeys)) {
            KeyBinding.setKeyBindState(key.getKeyCode(), physical && GameSettings.isKeyDown(key));
        }
        controlledKeys = null;
    }

    private static KeyBinding[] movementKeys(GameSettings settings) {
        return new KeyBinding[] {settings.keyBindForward, settings.keyBindBack, settings.keyBindLeft,
                settings.keyBindRight, settings.keyBindJump, settings.keyBindSneak, settings.keyBindSprint};
    }
}
