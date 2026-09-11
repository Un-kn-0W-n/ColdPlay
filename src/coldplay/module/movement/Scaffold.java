package coldplay.module.movement;

import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventRender;
import coldplay.event.EventRender3D;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.broker.ActionGuard;
import coldplay.util.EdgeUtil;
import coldplay.util.InvUtil;
import coldplay.util.PlacementUtil;
import coldplay.util.PlacementUtil.Placement;
import coldplay.util.PlayerUtil;
import coldplay.util.RenderUtil;
import coldplay.util.ResourcePriority;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import coldplay.broker.SprintGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Normal walk-bridging and Telly sprint-jump bridging. Both modes share one pipeline: search a cell
 * each update PRE, aim the pitch onto its face after physics (so the C08 next tick is judged from the
 * exact position/look pair on the wire), fire when the sent look raytraces onto the face. Telly only
 * changes where the yaw points (forward on the ground, backward in the air) and auto sprint-jumps.
 */
public class Scaffold extends Module {

    private static final String NORMAL = "Normal";
    private static final String TELLY = "Telly";


    public final ModeSetting mode = add(new ModeSetting("Mode", NORMAL, NORMAL, TELLY)
            .describe("Normal: walk-bridge. Telly: sprint-jump bridge, auto-jumps facing forward, "
                    + "spins back mid-air and places only when the sent look raytraces onto the support."));

    private final HeaderSetting placementHeader = add(new HeaderSetting("Placement"));
    private final BooleanSetting autoSwitch = add(new BooleanSetting("AutoSwitch", true)
            .describe("Silently switch to a block in your hotbar while scaffolding."));
    private final BooleanSetting keepY = add(new BooleanSetting("KeepY", true)
            .describe("On: lock the bridge plane to the Y you last stood on, never builds upward. "
                    + "Off: bridges flat too, but holding the jump key raises the plane with you "
                    + "(+1 per jump, tower or incline on demand)."));
    private final NumberSetting expand = add(new NumberSetting("Expand", 0.0, 0.0, 6.0, 1.0)
            .describe("Also try placements up to this many blocks ahead along your heading."));
    private final BooleanSetting safety = add(new BooleanSetting("Safety", true)
            .describe("Hard no-fall: auto-sneak at the leading edge while the covering block isn't placed "
                    + "yet. Vanilla's sneak edge-clamp then makes walking off the bridge impossible."));

    private final HeaderSetting debugHeader = add(new HeaderSetting("Debug"));
    private final BooleanSetting render = add(new BooleanSetting("Render", false)
            .describe("Draw the placement raytrace: a line from your eyes to the queued scaffold hit point, "
                    + "marked with a tenth-of-a-block box."));


    /** deg/tick the Normal spoofed look eases toward the fixed backward aim; high so it tracks the camera tightly. */
    private static final double TURN_RATE = 180.0D;
    /** Normal's sent pitch while no queued face is reachable yet: mid-window, so the aimed pitch never jumps far from it. */
    private static final float FALLBACK_PITCH = 85.0F;
    /** Normal rotation tier; fall-safety and emergency requests still override it. */
    private static final int PRIORITY = ResourcePriority.NORMAL;
    /** Motion multipliers for the predicted cells along the travel line (nearest first). */
    private static final double[] LOOKAHEAD = {1.5D, 2.5D, 4.0D};
    /** Below this horizontal speed there is no usable motion heading; fall back to the keys. */
    private static final double MOTION_EPS = 1.0E-3D;
    /** Render marker half-size: the queued hit-point cube is a tenth of a block across. */
    private static final double MARKER_HALF = 0.05D;

    /** Normal-mode aim humanization. */
    private static final float AIM_YAW_JITTER_SPAN = 3.0F;
    private static final float AIM_PITCH_WANDER_STEP = 0.6F;
    private static final float AIM_PITCH_WANDER_MAX = 0.75F;

    /** A fired place is flagged as duplicated when its judged yaw delta exceeds this and bit-ties the previous place's. */
    private static final float DUP_ROT_MIN_DELTA = 2.0F;
    private static final float DUP_ROT_TIE_EPS = 1.0E-4F;
    /** Jittered inset from a face-band bound when the look ray crosses outside it, so clamped cursors don't pile up. */
    private static final double CLAMP_JITTER = 0.08D;

    /** Telly: cells walked back toward the bridge frontier each tick, covering cells crossed during the spin. */
    private static final int BACKFILL = 3;
    /** Telly: max yaw bend off the backward hold toward the clicked point. Inside MoveFix's 22.5 degree bucket, so movement is untouched. */
    private static final float AIM_CLAMP = 22.0F;


    private final Random rand = new Random();
    /** Both modes gate placements against the last two sent looks. */
    private final RotationManager.SentLook sent = new RotationManager.SentLook();
    /** Placement queued this tick; re-searched every tick so an unreachable cell cannot starve the search. */
    private Placement pending;
    /** Bridge plane Y (below the feet at the last grounded tick); mid-air it follows the feet only while jump is held with KeepY off. */
    private int planeY = Integer.MIN_VALUE;
    /** The cell last stood on: the ascend step stacks onto it, since once you drift forward it is the only raised-plane cell the locked backward look can still reach. */
    private BlockPos ground;
    /** Per-tick Normal look noise: an otherwise perfectly steady spoofed look reads as machine-like. */
    private float yawJitter;
    private float pitchWander;
    /** Yaw delta judged for the last fired place; a tie with the next holds a tick to re-roll it. NaN until the first fire. */
    private float lastFiredYawDelta = Float.NaN;

    /** Telly: the last grounded movement yaw, frozen for the arc so a mid-air key change cannot swing the spin. */
    private float baseYaw;

    public Scaffold() {
        super("Scaffold", Category.MOVEMENT,
                "Bridges beneath you: Normal walk-bridges behind a spoofed backward look, Telly sprint-jump bridges.");
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        planeY = Integer.MIN_VALUE; // re-seed from wherever the player is when they next need a cell
        lastFiredYawDelta = Float.NaN;
        sent.reset(); // NaN compares false, so no cold-start delta gate trips on a stale look
        ground = null;
    }

    @Override
    protected void onDisable() {
        pending = null;
        SlotGuard.getInstance().release(this); // restore the player's original slot; rotation self-releases
    }


    private boolean telly() {
        return TELLY.equals(mode.get());
    }

    /** Telly's backward yaw for this arc. fixed 180, no per-arc randomness for now. */
    private float holdYaw() {
        return MathHelper.wrapAngleTo180_float(baseYaw + 180.0F);
    }

    /** The hold bent toward the queued hit by at most {@link #AIM_CLAMP}: zero bend unless the band clamp moved the hit off the hold ray. */
    static float aimYaw(float hold, Vec3 eyes, Placement p) {
        float[] aim = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                p.hitVec.xCoord, p.hitVec.yCoord, p.hitVec.zCoord);
        return MathHelper.wrapAngleTo180_float(hold + MathHelper.clamp_float(
                MathHelper.wrapAngleTo180_float(aim[0] - hold), -AIM_CLAMP, AIM_CLAMP));
    }

    /** Per frame: the yaw request (Normal: fixed backward spoof; Telly: forward on the ground, the arc hold in the air). {@link #onMotionAim} owns the pitch. */
    @EventTarget
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        RotationManager rm = RotationManager.getInstance();
        float pitch = rm.isActive() ? rm.getServerPitch() : player.rotationPitch;
        float move = PlayerUtil.movementYaw(mc, player);
        if (!telly()) {
            // The per-tick yaw jitter rides these frames: that is the look change wireLookReady needs.
            rm.request(this, MathHelper.wrapAngleTo180_float(move + 180.0F + yawJitter), pitch, PRIORITY, TURN_RATE);
        } else if (player.onGround) {
            rm.request(this, move, pitch, PRIORITY, TURN_RATE);
        } else {
            Placement p = pending;
            float yaw = p == null ? holdYaw() : aimYaw(holdYaw(), player.getPositionEyes(1.0F), p);
            rm.request(this, yaw, pitch, PRIORITY, TURN_RATE);
        }
    }

    /**
     * Motion PRE, after physics and before the C03: aim the pitch from the position this packet
     * carries, along the yaw it carries, onto the queued face, so the fire at the next tick's update
     * PRE raytraces from exactly the (position, look) pair the server holds. Normal's wander rides
     * the fallback only: near-vertical, a fraction of a degree moves the hit by whole blocks.
     */
    @EventTarget(priority = EventPriority.NORMAL)
    public void onMotionAim(EventMotion event) {
        RotationManager rm = RotationManager.getInstance();
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        // Reserved: a place went out this tick, and the packet after it must repeat the place look.
        if (!event.isPre() || player == null || !rm.owns(this) || ActionGuard.getInstance().isReserved()) {
            return;
        }
        float pitch = pending == null ? Float.NaN
                : aimPitch(player.getPositionEyes(1.0F), pending, rm.getServerYaw());
        if (Float.isNaN(pitch)) {
            pitch = telly() ? rm.getServerPitch() // mid-spin: hold the pitch, the yaw is still converging
                    : MathHelper.clamp_float(FALLBACK_PITCH + pitchWander, 0.0F, 90.0F);
        }
        rm.reaim(this, rm.getServerYaw(), pitch, TURN_RATE);
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        Minecraft mc = Minecraft.getMinecraft();
        Placement placement = pending;
        if (!render.get() || mc.thePlayer == null || mc.theWorld == null || placement == null) {
            return;
        }
        RenderUtil.drawTracerMarker(mc.thePlayer.getPositionEyes(event.getPartialTicks()), placement.hitVec,
                MARKER_HALF, 255, 60, 60, 180, 90, 255, 2.0F);
    }


    /** Telly's auto sprint-jump, the Normal sprint veto and the hard no-fall sneak clamp, all before MoveFix and physics. */
    @EventTarget
    public void onStrafe(EventStrafe event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        boolean jumping = telly() && autoJump(event, mc, player);
        // MoveFix can hand vanilla's sprint gate forward=+1 when the aim points along travel; veto it,
        // since sprinting while placing is its own flag. Consumed later this same tick. (Telly sprints.)
        if (!telly()) {
            SprintGuard.getInstance().suppress();
        }
        // Force sneak before MoveFix so vanilla's grounded edge-clamp engages this tick, but never on the
        // jump tick: the sneak slowdown drops forward under vanilla's sprint threshold and the jump goes
        // out un-sprinted. applyForcedSneakSlowdown is idempotent, so a co-enabled BridgeAssist never double-slows.
        if (!jumping && safetyCritical()) {
            event.applyForcedSneakSlowdown();
        }
    }

    /** Sprint-jump every grounded tick a move key is held. no look-alignment gate or dwell for now. */
    private boolean autoJump(EventStrafe event, Minecraft mc, EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        if (!player.onGround || !PlayerUtil.anyMoveKeyDown(mc)
                || held == null || !(held.getItem() instanceof ItemBlock)) {
            return false;
        }
        if (PlayerUtil.canVanillaSprint(player, event.getForward())) {
            player.setSprinting(true);
        }
        player.movementInput.jump = true;
        return true;
    }

    /**
     * True when un-clamped movement could drop the player off the bridge: grounded, travelling (or a
     * move key held), and no ground along the actual travel direction within the probe distance. Turns
     * false the tick the covering block lands, since the fire runs first at update PRE.
     */
    private boolean safetyCritical() {
        if (!safety.get()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return EdgeUtil.isApproachingEdge(mc, mc.thePlayer, EdgeUtil.MIN_PROBE);
    }


    /** Search a cell every tick and fire it immediately if its gate is open. */
    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        if (player.onGround) {
            baseYaw = PlayerUtil.movementYaw(mc, player);
            ground = new BlockPos(player).down();
        }
        // A vanilla client cannot turn, switch or place with a screen open, freeze the tick and drop
        // the queued placement, whose hit point came from the now-released spoof. The slot hold stays.
        if (mc.currentScreen != null) {
            pending = null;
            return;
        }
        rollLookNoise();
        if (autoSwitch.get()) {
            equipBlock(player);
        }
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            pending = null;
            return; // no block to place with
        }
        // Fire the cell onMotionAim actually aimed the sent look at, BEFORE the re-search can replace
        // it. The gate judges the old sent look, so a cell that changed between aim and fire (a yaw
        // swing flipping which face wins aimSwing, mid-air drift moving the foot cell) never matched.
        boolean fired = pending != null && firePending(mc, player, world, held);
        pending = findPlacement(player, world);
        // A cell's edge window may close before the next tick, so fire now rather than at the next PRE.
        if (!fired && pending != null && firePending(mc, player, world, held)) {
            pending = null;
        }
    }

    /** Per-tick Normal look noise. */
    private void rollLookNoise() {
        // Re-roll until the yaw step survives GCD snapping; wireLookReady needs a changed look.
        float prev = yawJitter;
        do {
            yawJitter = (rand.nextFloat() - 0.5F) * AIM_YAW_JITTER_SPAN;
        } while (RotationManager.gcdSnap(yawJitter - prev) == 0.0F);
        pitchWander = MathHelper.clamp_float(
                pitchWander + (rand.nextFloat() - 0.5F) * AIM_PITCH_WANDER_STEP,
                -AIM_PITCH_WANDER_MAX, AIM_PITCH_WANDER_MAX);
    }

    /**
     * Fire the queued placement. The gates run before the ActionGuard reserve: the gates are free,
     * the reserve a consumable a merely-held placement must not burn from another module.
     */
    private boolean firePending(Minecraft mc, EntityPlayerSP player, WorldClient world, ItemStack held) {
        Placement p = pending;
        RotationManager rm = RotationManager.getInstance();
        if (!wireLookReady(rm)) {
            return false;
        }
        // Holding one tick shifts the judged pair of sent looks, so consecutive fires cannot bit-tie.
        float judged = sent.yawDelta();
        if (judged > DUP_ROT_MIN_DELTA && Math.abs(judged - lastFiredYawDelta) < DUP_ROT_TIE_EPS) {
            return false;
        }
        // Raytrace against the look already on the wire (sent, not the live spoof): the C08 goes
        // out before this tick's look packet, so the server judges it against the last look it got,
        // which onMotionAim aimed from the very position the player still stands at.
        MovingObjectPosition mop = PlacementUtil.rayTraceGate(p, player, world, mc, sent.lookVec());
        if (mop == null || player.getPositionEyes(1.0F).distanceTo(mop.hitVec) > PlacementUtil.SERVER_REACH) {
            return false; // not looking at the face yet, or creative reach (5.0) hit past the server's 4.5
        }
        if (!ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return false; // the player's own interaction (or another module) owns this window
        }
        // Flushes the held-item switch (from AutoSwitch) and then the place itself.
        coldplay.broker.PacketLog.getInstance().tagged("Scaffold", () -> {
            mc.playerController.onPlayerRightClick(player, world, held, p.support, p.face, mop.hitVec);
            player.swingItem();
        });
        lastFiredYawDelta = judged;
        return true;
    }

    /** Record the look each outgoing look packet actually carried, after RotationManager's drain-stage rewrite. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        if (event.isPre()) {
            sent.record(event.getYaw(), event.getPitch());
        }
    }

    /**
     * The wire look is ours this frame AND this tick's packet will carry a look change. A queued place's
     * rotation is re-read only from a flying packet that changed the look, so firing on a no-change tick
     * would have it judged against a stale one.
     */
    private boolean wireLookReady(RotationManager rm) {
        return rm.owns(this) && sent.changedTo(rm.getServerYaw(), rm.getServerPitch());
    }


    /** Switch before the held stack runs dry to avoid a gap between placements. */
    private void equipBlock(EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemBlock && held.stackSize > 1) {
            return;
        }
        int slot = InvUtil.bestHotbarBlockSlot(player);
        if (slot != -1) {
            SlotGuard.getInstance().request(this, slot); // idempotent for the already-held slot
        }
    }


    private Placement findPlacement(EntityPlayerSP player, WorldClient world) {
        RotationManager rm = RotationManager.getInstance();
        Vec3 eyes = player.getPositionEyes(1.0F);
        // Telly's hit points come from the settled hold ray, not the live (still forward / mid-spin) look.
        Vec3 look = !rm.isActive() ? null
                : telly() ? RotationManager.lookVec(holdYaw(), rm.getServerPitch()) : rm.getServerLookVec();
        for (BlockPos target : candidateCells(player, world)) {
            if (!world.getBlockState(target).getBlock().isReplaceable(world, target)) {
                continue;
            }
            // Never fill a cell the player's own box overlaps. Vanilla's placement check excludes the
            // placing player, so after a missed cell drops you below the frozen plane the next cell lands
            // inside you, shoves you up onto it, and every later plane re-seed rides that level: the
            // upward bridge. Towering (KeepY off) aims at the apex cell, which never overlaps.
            if (new AxisAlignedBB(target, target.add(1, 1, 1)).intersectsWith(player.getEntityBoundingBox())) {
                continue;
            }
            Placement best = null;
            float bestSwing = Float.MAX_VALUE;
            for (EnumFacing dir : PlacementUtil.SUPPORT_ORDER) {
                BlockPos support = target.offset(dir);
                if (!world.getBlockState(support).getBlock().getMaterial().isSolid()) {
                    continue;
                }
                EnumFacing face = dir.getOpposite();
                if (!PlacementUtil.sideClickLegal(face, support, eyes)) {
                    continue;
                }
                Placement candidate = new Placement(target, support, face, lockedHitVec(support, face, eyes, look));
                // Telly's yaw is pinned to the hold: a face its bent ray cannot reach yields to the next
                // candidate. Normal's is pinned backward, so it yields too: else the wrong corner cell of a
                // diagonal step is queued forever and the reachable one is never tried.
                if (telly() && Float.isNaN(PlacementUtil.facePitch(eyes, candidate, aimYaw(holdYaw(), eyes, candidate)))) {
                    continue;
                }
                if (!telly() && look != null && Float.isNaN(aimPitch(eyes, candidate, rm.getServerYaw()))) {
                    continue;
                }
                // Within a cell both modes take the legal face angularly closest to the server look:
                // the far face costs whole ease ticks exactly when the edge race is lost.
                float swing = aimSwing(eyes, candidate.hitVec);
                if (swing < bestSwing) {
                    bestSwing = swing;
                    best = candidate;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * Normal's pitch onto a face along its backward yaw, NaN when that yaw cannot reach it. Top faces
     * (towering, bridging over ground) aim straight at the hit point instead of the 85 degree fallback,
     * which only lands on the block a quarter-block behind the feet.
     * no check that the backward ray actually crosses the support's top; walking straight it
     * does, on a diagonal the fire just holds. Add the AABB intercept from facePitch if that matters.
     */
    private static float aimPitch(Vec3 eyes, Placement p, float yaw) {
        if (p.face != EnumFacing.UP) {
            return PlacementUtil.facePitch(eyes, p, yaw);
        }
        return RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                p.hitVec.xCoord, p.hitVec.yCoord, p.hitVec.zCoord)[1];
    }

    /**
     * Degrees of server-look travel needed to aim at {@code hit}: |wrapped yaw delta| + |pitch delta|.
     * Zero when the spoof isn't live, so the first legal face wins and this only reorders once easing.
     */
    private static float aimSwing(Vec3 eyes, Vec3 hit) {
        RotationManager rm = RotationManager.getInstance();
        if (!rm.isActive()) {
            return 0.0F;
        }
        float[] aim = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                hit.xCoord, hit.yCoord, hit.zCoord);
        return Math.abs(MathHelper.wrapAngleTo180_float(aim[0] - rm.getServerYaw()))
                + Math.abs(aim[1] - rm.getServerPitch());
    }

    /** Foot, Telly backfill, motion predictions, then Expand; diagonal cells get support corners first. */
    private List<BlockPos> candidateCells(EntityPlayerSP player, WorldClient world) {
        // KeepY off still bridges flat: mid-air the plane follows the feet ONLY while the jump key is
        // physically held (keyBindJump, not movementInput.jump, which auto-jump and Velocity write), so
        // a held jump gains +1 per arc and everything else stays on the last grounded plane.
        boolean ascend = !keepY.get() && Minecraft.getMinecraft().gameSettings.keyBindJump.isKeyDown();
        if (ascend || player.onGround || planeY == Integer.MIN_VALUE) {
            planeY = new BlockPos(player).down().getY();
        }
        List<BlockPos> cells = new ArrayList<>();
        // Ascend step first: moving, you have drifted off the block you jumped from, so the cell under you
        // is supported only from ahead and the locked backward look can never raytrace onto that top face.
        // The cell above the block you left is straight behind you, so it is reachable. Below the apex
        // (planeY still the old plane) this is that solid block itself and drops out as non-replaceable.
        if (ascend && ground != null) {
            cells.add(new BlockPos(ground.getX(), planeY, ground.getZ()));
        }
        BlockPos foot = new BlockPos(player.posX, planeY, player.posZ);
        cells.add(foot);
        // a foot with no support (over a diagonal corner, or the raised plane mid-jump) gets
        // a stepping stone beside it: the neighbour touching the block behind, or sat on the old plane.
        if (!telly() && needsCorner(world, foot)) {
            for (EnumFacing dir : PlacementUtil.SUPPORT_ORDER) {
                if (dir != EnumFacing.DOWN) {
                    cells.add(foot.offset(dir));
                }
            }
        }

        // Cells crossed during the spin cannot fire yet, leaving the next bridge support behind the player.
        if (telly()) {
            double[] back = unitTravel(player, -1.0D);
            if (back != null) {
                BlockPos backPrev = foot;
                for (int i = 1; i <= BACKFILL; i++) {
                    backPrev = addWithCorners(world, cells, cell(player, back[0] * i, back[1] * i), backPrev);
                }
            }
        }
        BlockPos prev = foot;
        for (double m : LOOKAHEAD) {
            prev = addWithCorners(world, cells, cell(player, player.motionX * m, player.motionZ * m), prev);
        }
        int ahead = expand.get().intValue();
        if (ahead > 0) {
            double[] dir = unitTravel(player, 1.0D);
            if (dir == null) {
                dir = PlayerUtil.headingVec(player.rotationYaw);
            }
            for (int i = 1; i <= ahead; i++) {
                prev = addWithCorners(world, cells, cell(player, dir[0] * i, dir[1] * i), prev);
            }
        }
        return cells;
    }

    private BlockPos cell(EntityPlayerSP player, double offsetX, double offsetZ) {
        return new BlockPos(player.posX + offsetX, planeY, player.posZ + offsetZ);
    }

    private static double[] unitTravel(EntityPlayerSP player, double sign) {
        double len = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
        return len < MOTION_EPS ? null
                : new double[] {player.motionX / len * sign, player.motionZ / len * sign};
    }

    /**
     * Diagonal cells need a face-sharing corner. Check both ends because Telly backfill walks
     * backward; adding corners after neither end needs one wastes placements.
     */
    private BlockPos addWithCorners(WorldClient world, List<BlockPos> cells, BlockPos pred, BlockPos prev) {
        if (pred.getX() != prev.getX() && pred.getZ() != prev.getZ()
                && (needsCorner(world, pred) || needsCorner(world, prev))) {
            addOnce(cells, new BlockPos(pred.getX(), planeY, prev.getZ()));
            addOnce(cells, new BlockPos(prev.getX(), planeY, pred.getZ()));
        }
        addOnce(cells, pred);
        return pred;
    }

    private static void addOnce(List<BlockPos> cells, BlockPos cell) {
        if (!cells.contains(cell)) {
            cells.add(cell);
        }
    }

    /** Still replaceable with no solid support neighbour: filling it needs a corner stepping stone. */
    private static boolean needsCorner(WorldClient world, BlockPos cell) {
        if (!world.getBlockState(cell).getBlock().isReplaceable(world, cell)) {
            return false;
        }
        for (EnumFacing dir : PlacementUtil.SUPPORT_ORDER) {
            if (world.getBlockState(cell.offset(dir)).getBlock().getMaterial().isSolid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Both modes' aim point: the spot on the face nearest {@code dir} (the look ray intersected with the
     * face plane), clamped into the central legal band so consecutive placements need near-zero rotation
     * (and mid-face height leaves the most slack for the GCD-snapped pitch). Random when there is no ray.
     */
    private Vec3 lockedHitVec(BlockPos support, EnumFacing face, Vec3 eyes, Vec3 dir) {
        if (dir == null) {
            return PlacementUtil.randomHitVec(rand, support, face);
        }
        double pin = 0.5D + (face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE
                ? PlacementUtil.FACE_PIN : -PlacementUtil.FACE_PIN);
        double eyeAxis, dirAxis; // eye offset from the support corner / ray component, along the face axis
        switch (face.getAxis()) {
            case X: eyeAxis = eyes.xCoord - support.getX(); dirAxis = dir.xCoord; break;
            case Y: eyeAxis = eyes.yCoord - support.getY(); dirAxis = dir.yCoord; break;
            default: eyeAxis = eyes.zCoord - support.getZ(); dirAxis = dir.zCoord; break;
        }
        double t = (pin - eyeAxis) / dirAxis;
        if (!(t > 0.0D) || Double.isInfinite(t)) {
            return PlacementUtil.randomHitVec(rand, support, face); // looking away from / parallel to the plane
        }
        double x = eyes.xCoord + dir.xCoord * t;
        double y = eyes.yCoord + dir.yCoord * t;
        double z = eyes.zCoord + dir.zCoord * t;
        switch (face.getAxis()) {
            case X: x = support.getX() + pin; y = clampFace(y, support.getY()); z = clampFace(z, support.getZ()); break;
            case Y: y = support.getY() + pin; x = clampFace(x, support.getX()); z = clampFace(z, support.getZ()); break;
            case Z: z = support.getZ() + pin; x = clampFace(x, support.getX()); y = clampFace(y, support.getY()); break;
        }
        return new Vec3(x, y, z);
    }

    /** Clamp into the face's legal band; out-of-band lands a jittered inset from the bound, never on it. */
    private double clampFace(double v, int base) {
        if (v < base + PlacementUtil.HIT_BAND_MIN) {
            return base + PlacementUtil.HIT_BAND_MIN + rand.nextDouble() * CLAMP_JITTER;
        }
        if (v > base + PlacementUtil.HIT_BAND_MAX) {
            return base + PlacementUtil.HIT_BAND_MAX - rand.nextDouble() * CLAMP_JITTER;
        }
        return v;
    }
}
