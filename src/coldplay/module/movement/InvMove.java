package coldplay.module.movement;

import coldplay.broker.ActionGuard;
import coldplay.broker.SprintGuard;
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
    private static final String VANILLA = "Vanilla";
    private static final String HYPIXEL = "Hypixel";
    private static final int MAX_WAIT_TICKS = 20;
    private static InvMove instance;
    public final ModeSetting mode = add(new ModeSetting("Mode", VANILLA, VANILLA, HYPIXEL)
            .describe("Vanilla: move and click straight away. Hypixel: hold each click until you have "
                    + "come to a complete stop, then send it."));
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
        if (m == null || m.sending || !m.isEnabled() || !m.defers()
                || mc.thePlayer != playerIn || !(mc.currentScreen instanceof GuiContainer)
                || mc.playerController.isInCreativeMode() || playerIn.isRiding()
                || ((GuiContainer) mc.currentScreen).inventorySlots != playerIn.openContainer) {
            return false;
        }
        if (!m.sameSession()) {
            m.beginSession(mc.thePlayer, (GuiContainer) mc.currentScreen, playerIn.openContainer, windowId);
        }
        m.clicks.addLast(new int[] {slotId, button, mode});
        // Keep later GUI events behind this click so they see its updated cursor and slot state.
        m.screen.allowUserInput = false;
        m.updateKeys(false); // stand still for the pause
        return true;
    }

    public static boolean isInputPaused(GuiScreen screen) {
        InvMove m = instance;
        return m != null && m.isEnabled() && m.defers()
                && m.screen == screen && m.sameSession() && !m.clicks.isEmpty();
    }

    /**
     * True while a container GUI is open and this mode says now is not a safe moment to act on it.
     * Automation clicks through {@link net.minecraft.client.multiplayer.PlayerControllerMP
     * #automatedWindowClick} instead of the manual path, so it never reaches {@link #deferClick} and
     * would otherwise send while the player is walking - the one thing the deferred modes exist to
     * avoid. Callers hold their click and retry; the state is replanned from live slots anyway.
     */
    public static boolean holdsAutomation() {
        return holdsAutomation(Minecraft.getMinecraft().thePlayer);
    }

    /** Mirrors {@link #deferClick}'s eligibility, so automation waits exactly where a click would. */
    public static boolean holdsAutomation(EntityPlayer playerIn) {
        InvMove m = instance;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP self = mc.thePlayer;
        if (m == null || !m.isEnabled() || !m.defers() || self == null || self != playerIn
                || mc.theWorld == null || !(mc.currentScreen instanceof GuiContainer)
                || mc.playerController.isInCreativeMode() || self.isRiding()
                || ((GuiContainer) mc.currentScreen).inventorySlots != self.openContainer) {
            return false;
        }
        return !m.readyToSend(self);
    }

    /**
     * Delay the close itself; window 0 remains the same container even after its GUI closes. Only a
     * close that would overtake a queued click waits here: automation holds its own close through
     * {@link #holdsAutomation()}, and a close the player asked for has to happen when they ask.
     */
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
        if (screen != null && (!sameSession() || !defers() || ++waitTicks > MAX_WAIT_TICKS)) {
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
        if (!inContainer()) return;
        // Vanilla unpresses every key when a GUI opens, so a client with a container on screen can
        // never toggle sprint or sneak. Moving itself is ordinary - ice, pistons and leftover
        // momentum all do it - but onUpdateWalkingPlayer turns a sprint or sneak change into a
        // C0BPacketEntityAction, and that is a state the server knows the vanilla client cannot
        // reach. Keep both off the wire in every mode, not only while a click is queued.
        SprintGuard.getInstance().suppress();
        event.setSneak(false);
        if (!sameSession() || clicks.isEmpty()) return;
        event.setForward(0.0F);
        event.setStrafe(0.0F);
        player.movementInput.jump = false;
    }

    private static boolean inContainer() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && mc.theWorld != null && mc.currentScreen instanceof GuiContainer;
    }

    @EventTarget
    public void onMotion(EventMotion event) {
        if (event.isPre() || clicks.isEmpty()) return;
        if (!isEnabled() || !defers() || !sameSession()) {
            cancelPending();
            return;
        }
        // Run after a real movement update, so the click lands on a position the server has seen.
        if (!readyToSend(player) || !ActionGuard.getInstance().tryReserve(this)) return;
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

    /** Vanilla sends a click straight away; Hypixel holds it back for a safer moment. */
    private boolean defers() {
        return HYPIXEL.equals(mode.get());
    }

    /**
     * Hypixel wants a dead stop before the click goes out. Takes the player rather than reading the
     * session field, because automation is gated by the same test before any session exists.
     */
    private boolean readyToSend(EntityPlayerSP p) {
        return !InvUtil.isPlayerMoving(p)
                && p.motionX * p.motionX + p.motionZ * p.motionZ <= 9.0E-4D;
    }

    private boolean sameSession() {
        Minecraft mc = Minecraft.getMinecraft();
        return screen != null && mc.currentScreen == screen && mc.thePlayer == player && mc.theWorld == world
                && player.sendQueue == connection && player.openContainer == container && container.windowId == windowId;
    }

    /** Binds the pending work to one player/screen/container, so a swap of any of them drops it. */
    private void beginSession(EntityPlayerSP owner, GuiContainer gui, Container open, int window) {
        cancelPending();
        player = owner;
        world = Minecraft.getMinecraft().theWorld;
        connection = owner.sendQueue;
        container = open;
        windowId = window;
        screen = gui;
        allowedUserInput = gui.allowUserInput;
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
        for (KeyBinding key : drivenKeys(settings)) {
            KeyBinding.setKeyBindState(key.getKeyCode(), physical && GameSettings.isKeyDown(key));
        }
    }

    private void releaseKeys() {
        if (controlledKeys == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean physical = mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null
                && mc.gameSettings == controlledKeys;
        for (KeyBinding key : restoredKeys(controlledKeys)) {
            KeyBinding.setKeyBindState(key.getKeyCode(), physical && GameSettings.isKeyDown(key));
        }
        controlledKeys = null;
    }

    /**
     * Walking keys only. Sprint and sneak are deliberately absent: vanilla unpresses every key when
     * a GUI opens, so holding either down with a container on screen is a state the client cannot
     * reach, and {@link net.minecraft.client.entity.EntityPlayerSP#onUpdateWalkingPlayer()} would
     * announce the change as a C0BPacketEntityAction.
     */
    private static KeyBinding[] drivenKeys(GameSettings settings) {
        return new KeyBinding[] {settings.keyBindForward, settings.keyBindBack, settings.keyBindLeft,
                settings.keyBindRight, settings.keyBindJump};
    }

    /** Sprint and sneak are restored on the way out even though they are never driven, because the
     * GUI opening unpressed them and only a fresh key event would otherwise bring them back. */
    private static KeyBinding[] restoredKeys(GameSettings settings) {
        return new KeyBinding[] {settings.keyBindForward, settings.keyBindBack, settings.keyBindLeft,
                settings.keyBindRight, settings.keyBindJump, settings.keyBindSneak, settings.keyBindSprint};
    }
}
