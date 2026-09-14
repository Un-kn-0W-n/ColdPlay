package coldplay.module.combat;

import coldplay.broker.ActionGuard;
import coldplay.broker.SwordBlock;
import coldplay.event.EventAttackPerformed;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

/** Vanilla taps after completed attacks; Blink delegates packet holds to {@link SwordBlock}. */
public class AutoBlock extends Module {
    private static final String VANILLA = "Vanilla";
    private static final String BLINK = "Blink";

    public final ModeSetting mode = add(new ModeSetting("Mode", VANILLA, VANILLA, BLINK)
            .describe("Vanilla taps the block key after each hit. Blink holds the block server-side through "
                    + "delayed packet bursts, never gates attacks and slows only one tick per burst."));
    public final NumberSetting blockTicks = add(new NumberSetting("BlockTicks", 5.0, 1.0, 20.0, 1.0)
            .describe("How many ticks each block is held."));
    public final NumberSetting releaseTicks = add(new NumberSetting("ReleaseTicks", 2.0, 1.0, 10.0, 1.0)
            .describe("Open ticks between an attack and the next block."));
    public final NumberSetting holdTicks = add(new NumberSetting("HoldTicks", 20.0, 5.0, 100.0, 1.0)
            .describe("Ticks the block is kept up after the last completed attack."));
    public final NumberSetting blinkTicks = add(new NumberSetting("BlinkTicks", 3.0, 2.0, 5.0, 1.0)
            .describe("Ticks of packets per burst. Only one tick per burst is slowed; hits leave up to this many ticks late."));

    private enum State { IDLE, BLOCKING, OPEN }

    private State state = State.IDLE;
    private int ticks;
    private boolean pendingAttack;
    private boolean keySpoofed;
    private int holdLeft;

    public AutoBlock() {
        super("AutoBlock", Category.COMBAT,
                "Blocks with your sword after completed attacks using vanilla item use.");
        addAutoOff();
        blockTicks.visibleWhen(this::vanilla).indent(1);
        releaseTicks.visibleWhen(this::vanilla).indent(1);
        holdTicks.visibleWhen(() -> !vanilla()).indent(1);
        blinkTicks.visibleWhen(() -> !vanilla()).indent(1);
    }

    private boolean vanilla() {
        return VANILLA.equals(mode.get());
    }

    @Override
    protected void onDisable() {
        reset(Minecraft.getMinecraft());
        holdLeft = 0;
        SwordBlock.getInstance().setArmed(false, 0);
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        pendingAttack = true;
        holdLeft = holdTicks.get().intValue();
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            reset(mc);
            return;
        }
        if (!vanilla()) {
            reset(mc);
            ItemStack held = player.inventory.getCurrentItem();
            SwordBlock.getInstance().setArmed(held != null && held.getItem() instanceof ItemSword
                    && mc.currentScreen == null && holdLeft > 0, blinkTicks.get().intValue());
            if (holdLeft > 0) {
                holdLeft--;
            }
            return;
        }
        SwordBlock.getInstance().setArmed(false, 0);
        if (ActionGuard.getInstance().isReserved()) {
            unspoofKey(mc);
            state = State.IDLE;
            ticks = 0;
            return; // pendingAttack stays armed for the next free tick
        }

        switch (state) {
            case IDLE:
                if (pendingAttack) {
                    pendingAttack = false;
                    tryStart(mc, player);
                }
                break;
            case BLOCKING:
                // A GUI freezes vanilla's onStoppedUsingItem, so isUsingItem() would never clear.
                if (!player.isUsingItem() || mc.currentScreen != null) {
                    unspoofKey(mc);
                    state = State.OPEN;
                    ticks = 0;
                } else if (++ticks >= blockTicks.get().intValue()) {
                    unspoofKey(mc);
                    ActionGuard.getInstance().tryReserve(this);
                }
                break;
            case OPEN:
                ticks++;
                if (pendingAttack && ticks >= releaseTicks.get().intValue()) {
                    pendingAttack = false;
                    tryStart(mc, player);
                } else if (!pendingAttack && ticks >= releaseTicks.get().intValue()) {
                    state = State.IDLE;
                }
                break;
        }
    }

    private void tryStart(Minecraft mc, EntityPlayerSP player) {
        if (mc.currentScreen != null || player.isUsingItem()) {
            return;
        }
        ItemStack held = player.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemSword)
                || !ActionGuard.getInstance().tryReserve(this)) {
            return;
        }
        coldplay.broker.PacketLog.getInstance().tagged("AutoBlock", () -> mc.playerController.sendUseItem(player, mc.theWorld, held));
        if (player.isUsingItem()) {
            spoofKey(mc);
            state = State.BLOCKING;
            ticks = 0;
        }
    }

    /** Clears the vanilla tap state only; the caller disarms the packet hold. */
    private void reset(Minecraft mc) {
        unspoofKey(mc);
        state = State.IDLE;
        ticks = 0;
        pendingAttack = false;
    }

    private void spoofKey(Minecraft mc) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        keySpoofed = true;
    }

    private void unspoofKey(Minecraft mc) {
        if (!keySpoofed) {
            return;
        }
        keySpoofed = false;
        if (!GameSettings.isKeyDown(mc.gameSettings.keyBindUseItem)) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
    }
}
