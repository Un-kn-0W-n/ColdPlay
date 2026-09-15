package coldplay.module.combat;

import coldplay.broker.ActionGuard;
import coldplay.broker.CombatManager;
import coldplay.broker.HurtClock;
import coldplay.broker.PacketLog;
import coldplay.broker.PlayerPacketState;
import coldplay.broker.SlotGuard;
import coldplay.broker.SwordBlock;
import coldplay.broker.UseHold;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Vanilla taps after completed attacks; Blink delegates packet holds to {@link SwordBlock}. Hypixel really blocks
 * while an enemy can reach us and opens only when our hurt immunity covers it or the target can take a hit.
 */
public class AutoBlock extends Module {
    private static final String VANILLA = "Vanilla";
    private static final String BLINK = "Blink";
    private static final String HYPIXEL = "Hypixel";
    private static final int IMMUNE_DRAINS = 10;
    private static final double INTERACT_REACH = 3.0D;
    private static final CombatManager.Filters THREATS =
            new CombatManager.Filters(true, false, false, true, false, 8.0, 360.0, true, false);

    public final ModeSetting mode = add(new ModeSetting("Mode", VANILLA, VANILLA, BLINK, HYPIXEL)
            .describe("Vanilla taps the block key after each hit. Blink holds the block server-side through "
                    + "delayed packet bursts, never gates attacks and slows only one tick per burst. Hypixel really "
                    + "blocks while an enemy can reach you, and lets KillAura hit only when the target can take "
                    + "damage, releasing a tick before and blocking again a tick after."));
    public final NumberSetting blockTicks = add(new NumberSetting("BlockTicks", 5.0, 1.0, 20.0, 1.0)
            .describe("How many ticks each block is held."));
    public final NumberSetting releaseTicks = add(new NumberSetting("ReleaseTicks", 2.0, 1.0, 10.0, 1.0)
            .describe("Open ticks between an attack and the next block."));
    public final NumberSetting holdTicks = add(new NumberSetting("HoldTicks", 20.0, 5.0, 100.0, 1.0)
            .describe("Ticks the block is kept up after the last completed attack."));
    public final NumberSetting blinkTicks = add(new NumberSetting("BlinkTicks", 3.0, 2.0, 5.0, 1.0)
            .describe("Ticks of packets per burst. Only one tick per burst is slowed; hits leave up to this many ticks late."));
    public final NumberSetting threatRange = add(new NumberSetting("Threat Range", 3.6, 3.0, 6.0, 0.1)
            .describe("Block while an enemy is this close, or will be by the time your packets arrive."));
    public final NumberSetting patience = add(new NumberSetting("Patience", 6.0, 0.0, 20.0, 1.0)
            .describe("Ticks an open target waits for your own hurt immunity before you hit it from an exposed "
                    + "block. Higher blocks more hits, lower hits more often."));

    private enum State { IDLE, BLOCKING, OPEN }

    private final Supplier<Entity> auraTarget;
    private final BooleanSupplier auraReach;
    private final Random random = new Random();
    private State state = State.IDLE;
    private int ticks;
    private boolean pendingAttack;
    private boolean keySpoofed;
    private int holdLeft;

    private int tick;
    private double lagAverage = 4.0D; // round trip in ticks, from our hit to the target's hurt status
    private int lag = 4;
    private Entity plannedTarget;
    private int attackAt = -1;
    private int reblockAt = -1;
    private int openSince = -1;
    private int nextPlanAt = -1;
    private int sampleFrom = -1;
    private boolean attacked;
    private boolean threatFalling;

    public AutoBlock(Supplier<Entity> auraTarget, BooleanSupplier auraReach) {
        super("AutoBlock", Category.COMBAT,
                "Blocks with your sword after completed attacks using vanilla item use.");
        this.auraTarget = auraTarget;
        this.auraReach = auraReach;
        addAutoOff();
        blockTicks.visibleWhen(this::vanilla).indent(1);
        releaseTicks.visibleWhen(this::vanilla).indent(1);
        holdTicks.visibleWhen(this::blink).indent(1);
        blinkTicks.visibleWhen(this::blink).indent(1);
        threatRange.visibleWhen(this::hypixel).indent(1);
        patience.visibleWhen(this::hypixel).indent(1);
    }

    private boolean vanilla() {
        return VANILLA.equals(mode.get());
    }

    private boolean blink() {
        return BLINK.equals(mode.get());
    }

    private boolean hypixel() {
        return HYPIXEL.equals(mode.get());
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getMinecraft();
        reset(mc);
        holdLeft = 0;
        SwordBlock.getInstance().disarm();
        release(mc, mc.thePlayer);
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        if (!(event.getTarget() instanceof EntityLivingBase)) {
            return; // a punched fireball is not a fight
        }
        pendingAttack = true;
        holdLeft = holdTicks.get().intValue();
        attacked = true;
        sampleFrom = tick; // windows are further apart than the echo, so only the latest hit can answer
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            tick++;
            attacked = false;
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            reset(mc);
            release(mc, null);
            return;
        }
        if (hypixel()) {
            reset(mc);
            SwordBlock.getInstance().disarm();
            hypixel(mc, player);
            return;
        }
        release(mc, player);
        if (blink()) {
            reset(mc);
            ItemStack held = player.inventory.getCurrentItem();
            SwordBlock.getInstance().setArmed(held != null && held.getItem() instanceof ItemSword
                    && mc.currentScreen == null && holdLeft > 0, blinkTicks.get().intValue());
            if (holdLeft > 0) {
                holdLeft--;
            }
            return;
        }
        SwordBlock.getInstance().disarm();
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

    // Runs after KillAura in the same PRE, so this tick's hit is already sent and the click gate set here
    // applies from the next tick. Vanilla sends the release in the input phase, after every PRE send.
    private void hypixel(Minecraft mc, EntityPlayerSP player) {
        UseHold hold = UseHold.getInstance();
        Entity target = auraTarget.get();
        sampleLag(target);
        ItemStack held = player.inventory.getCurrentItem();
        if (target == null || held == null || !(held.getItem() instanceof ItemSword) || mc.currentScreen != null
                || !mc.inGameHasFocus || player.isRiding() || !player.isEntityAlive()
                || !SlotGuard.getInstance().isFree() || !SwordBlock.getInstance().isIdle()) {
            release(mc, player);
            return;
        }
        if (player.isUsingItem() && !hold.isHeld()) {
            return; // a manual block or eat owns the item use
        }

        double range = threatRange.get() + (hold.isHeld() ? 0.4D : 0.0D);
        int threats = threats(player, range);
        boolean danger = threats > 0;
        int since = HurtClock.getInstance().ticksSince(player);
        boolean strongCover = threats <= 1 && !threatFalling && HurtClock.getInstance().damaged(player);

        if (attackAt >= 0 && (attacked || tick > attackAt || target != plannedTarget)) {
            reblockAt = attacked ? tick + 1 : tick; // the block never shares the hit's window
            nextPlanAt = attacked ? tick + lag + 2 : tick + 1;
            openSince = attacked ? -1 : tick;
            attackAt = -1;
        }
        int resistant = target instanceof EntityLivingBase ? ((EntityLivingBase) target).hurtResistantTime : 0;
        boolean open = targetOpen(resistant, lag, 1) && tick >= nextPlanAt && auraReach.getAsBoolean();
        if (!open) {
            openSince = -1;
        } else if (attackAt < 0 && tick >= reblockAt) {
            if (openSince < 0) {
                openSince = tick;
            }
            // the block after the hit can trail the release by the delay, a grace tick and one more
            int delay = random.nextInt(4) == 0 ? 2 : 1;
            if (!danger || strongCover && covered(since, lag, delay + 1)
                    || tick - openSince >= patience.get().intValue()) {
                attackAt = tick + delay;
                plannedTarget = target;
                openSince = -1;
            }
        }

        boolean block = danger && !(strongCover && covered(since, lag, 0)) && attackAt < 0 && tick >= reblockAt;
        hold.allowAttack(attackAt >= 0 && tick + 1 >= attackAt, plannedTarget);
        if (block) {
            startBlock(player);
        } else {
            hold.setHeld(false);
        }
    }

    /** True while a block sent {@code lead} ticks from now still reaches the server inside our hurt immunity. */
    static boolean covered(int since, int lag, int lead) {
        // the block has to drain by the ninth tick after the hit; one tick is kept for jitter
        return since >= 0 && since + lead + lag <= IMMUNE_DRAINS - 3;
    }

    /** True when a hit sent {@code lead} ticks from now drains after the target's immunity ends. */
    static boolean targetOpen(int resistant, int lag, int lead) {
        int since = resistant > 0 ? 2 * IMMUNE_DRAINS - resistant : 2 * IMMUNE_DRAINS;
        return since + lead + lag >= IMMUNE_DRAINS + 1;
    }

    static double smoothLag(double average, int sample) {
        return MathHelper.clamp_double(average * 0.75D + sample * 0.25D, 1.0D, 8.0D);
    }

    // The target's hurt status is the echo of our latest hit. A late echo is a held packet or a spike, not lag.
    private void sampleLag(Entity target) {
        if (sampleFrom < 0) {
            return;
        }
        int sample = tick - sampleFrom;
        if (target instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) target;
            if (living.hurtResistantTime == living.maxHurtResistantTime && living.hurtTime == living.maxHurtTime) {
                if (sample >= 1 && sample <= lag + 4) {
                    lagAverage = smoothLag(lagAverage, sample);
                    lag = (int) Math.round(lagAverage);
                }
                sampleFrom = -1;
                return;
            }
        }
        if (sample > 12) {
            sampleFrom = -1;
        }
    }

    private int threats(EntityPlayerSP player, double range) {
        threatFalling = false;
        int count = 0;
        AxisAlignedBB box = player.getEntityBoundingBox();
        for (EntityPlayer other : player.worldObj.playerEntities) {
            if (other == player || player.isOnSameTeam(other)
                    || !CombatManager.getInstance().isValid(player, other, THREATS)) {
                continue;
            }
            // closing speed carried over our round trip, since the server judges reach on later positions
            double dx = other.posX - other.prevPosX - (player.posX - player.prevPosX);
            double dz = other.posZ - other.prevPosZ - (player.posZ - player.prevPosZ);
            Vec3 eyes = other.getPositionEyes(1.0F);
            Vec3 ahead = eyes.addVector(dx * lag, 0.0D, dz * lag);
            if (Math.min(distance(box, eyes), distance(box, ahead)) > range) {
                continue;
            }
            count++;
            threatFalling |= !other.onGround && other.posY < other.prevPosY; // a crit outdamages our immunity
        }
        return count;
    }

    private static double distance(AxisAlignedBB box, Vec3 point) {
        double x = MathHelper.clamp_double(point.xCoord, box.minX, box.maxX);
        double y = MathHelper.clamp_double(point.yCoord, box.minY, box.maxY);
        double z = MathHelper.clamp_double(point.zCoord, box.minZ, box.maxZ);
        return point.distanceTo(new Vec3(x, y, z));
    }

    private void startBlock(EntityPlayerSP player) {
        if (player.isUsingItem()) {
            UseHold.getInstance().setHeld(true);
            return;
        }
        // another action already owns this window (a fireball release, a place, a click)
        if (!ActionGuard.getInstance().tryReserve(this)) {
            return;
        }
        UseHold.getInstance().setHeld(true);
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack held = player.inventory.getCurrentItem();
        MovingObjectPosition pointed = pointedPlayer(player);
        PacketLog.getInstance().tagged("AutoBlock", () -> {
            // a vanilla right click on a player interacts with it before using the sword
            if (pointed != null) {
                mc.playerController.isPlayerRightClickingOnEntity(player, pointed.entityHit, pointed);
                mc.playerController.interactWithEntitySendPacket(player, pointed.entityHit);
            }
            mc.playerController.sendUseItem(player, mc.theWorld, held);
        });
    }

    // The server resolves interacts against the look we last sent, not the camera.
    private static MovingObjectPosition pointedPlayer(EntityPlayerSP player) {
        PlayerPacketState.Pose pose = player.sendQueue.getNetworkManager().getPlayerPackets().getPose();
        if (!pose.isKnown()) {
            return null;
        }
        Vec3 eyes = pose.eyes(player.getEyeHeight());
        Vec3 look = pose.look();
        Vec3 end = eyes.addVector(look.xCoord * INTERACT_REACH, look.yCoord * INTERACT_REACH, look.zCoord * INTERACT_REACH);
        MovingObjectPosition wall = player.worldObj.rayTraceBlocks(eyes, end, false, false, true);
        MovingObjectPosition best = null;
        double bestDistance = wall == null ? INTERACT_REACH : eyes.distanceTo(wall.hitVec); // vanilla clicks the block
        for (EntityPlayer other : player.worldObj.playerEntities) {
            if (other == player || other.isSpectator()) {
                continue;
            }
            float border = other.getCollisionBorderSize();
            AxisAlignedBB box = other.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition ray = box.calculateIntercept(eyes, end);
            if (box.isVecInside(eyes)) {
                return new MovingObjectPosition(other, ray == null ? eyes : ray.hitVec);
            }
            if (ray != null && eyes.distanceTo(ray.hitVec) < bestDistance) {
                bestDistance = eyes.distanceTo(ray.hitVec);
                best = new MovingObjectPosition(other, ray.hitVec);
            }
        }
        return best;
    }

    /** Drops the Hypixel hold; vanilla then releases in the input phase, except under a screen where it never runs. */
    private void release(Minecraft mc, EntityPlayerSP player) {
        UseHold hold = UseHold.getInstance();
        if (hold.isHeld() && player != null && mc.currentScreen != null && player.isUsingItem()) {
            mc.playerController.onStoppedUsingItem(player);
        }
        hold.clear();
        attackAt = -1;
        reblockAt = -1;
        openSince = -1;
        plannedTarget = null;
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
        PacketLog.getInstance().tagged("AutoBlock", () -> mc.playerController.sendUseItem(player, mc.theWorld, held));
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
