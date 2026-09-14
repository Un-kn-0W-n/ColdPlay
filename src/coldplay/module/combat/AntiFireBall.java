package coldplay.module.combat;

import com.google.common.base.Predicate;
import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.broker.ActionGuard;
import coldplay.broker.RotationManager;
import coldplay.util.ResourcePriority;
import coldplay.broker.SprintGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.RayPicker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

/** Emergency, one-shot deflection of an incoming large fireball. */
public final class AntiFireBall extends Module {
    static final double MOTION_FACTOR = 0.95D;
    static final double REACH = 3.0D;
    static final int PREARM_TICKS = 2;
    private static final int MAX_PREDICTION_TICKS = 1200;

    private final Set<EntityLargeFireball> handled = Collections.newSetFromMap(
            new IdentityHashMap<EntityLargeFireball, Boolean>());
    private EntityLargeFireball target;
    private World trackedWorld;
    private int updateTick;
    private int attackAfterUpdate = Integer.MIN_VALUE;

    public AntiFireBall() {
        super("AntiFireBall", Category.COMBAT,
                "Silently aims at and deflects the earliest incoming large fireball.");
    }

    @Override
    protected void onDisable() {
        clear(true);
        trackedWorld = null;
        updateTick = 0;
    }

    @EventTarget(priority = EventPriority.FALL_SAFETY)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        updateTick++;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (mc.theWorld != trackedWorld) {
            clear(true);
            trackedWorld = mc.theWorld;
        }
        if (!ready(mc, player)) {
            clear(false);
            return;
        }
        pruneHandled(mc);

        Threat best = null;
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityLargeFireball)) {
                continue;
            }
            EntityLargeFireball fireball = (EntityLargeFireball) object;
            if (fireball.isDead || handled.contains(fireball)) {
                continue;
            }
            Threat threat = predict(mc, player, fireball);
            if (threat != null && (best == null || earlier(threat.impactTick, fireball.getEntityId(),
                    best.impactTick, best.fireball.getEntityId()))) {
                best = threat;
            }
        }

        if (best == null || !shouldPrearm(best.reachTick, best.impactTick)) {
            clear(false);
            return;
        }
        if (!ActionGuard.getInstance().tryReserve(this)) {
            clear(false);
            return;
        }
        if (target != best.fireball) {
            target = best.fireball;
            attackAfterUpdate = Integer.MIN_VALUE;
        }
        if (player.isUsingItem() && mc.playerController != null) {
            mc.playerController.onStoppedUsingItem(player);
            attackAfterUpdate = Math.max(attackAfterUpdate, updateTick + 1);
        }
    }

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        EntityLargeFireball fireball = target;
        if (!ready(mc, player) || !live(mc, fireball)) {
            return;
        }
        Vec3 center = fireball.getEntityBoundingBox().getCenter();
        Vec3 eyes = player.getPositionEyes(1.0F);
        float[] angles = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                center.xCoord, center.yCoord, center.zCoord);
        RotationManager.getInstance().request(this, angles[0], angles[1], ResourcePriority.FALL_SAFETY, 180.0D);
    }

    /** Stops movement during the reach window. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onStrafe(EventStrafe event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (!ready(mc, player) || !live(mc, target) || !reachableTowardCenter(mc, player, target)) {
            return;
        }
        event.setForward(0.0F);
        event.setStrafe(0.0F);
        player.movementInput.jump = false;
        SprintGuard.getInstance().suppress();
        if (player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    /** DRAIN priority, after RotationManager has written the look this packet carries. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        EntityLargeFireball fireball = target;
        RotationManager rotations = RotationManager.getInstance();
        if (!ready(mc, player) || !live(mc, fireball) || updateTick < attackAfterUpdate
                || !rotations.owns(this) || !ActionGuard.getInstance().tryReserve(this)) {
            return;
        }
        if (predict(mc, player, fireball) == null) {
            clear(false);
            return;
        }
        Vec3 sentLook = RotationManager.lookVec(event.getYaw(), event.getPitch());
        if (!rayHits(mc, player, fireball, sentLook)) {
            return;
        }
        handled.add(fireball);
        try {
            coldplay.broker.PacketLog.getInstance().tagged("AntiFireBall", () -> {
                player.swingItem();
                mc.playerController.attackEntity(player, fireball);
            });
        } finally {
            clear(false);
        }
    }

    private static boolean ready(Minecraft mc, EntityPlayerSP player) {
        return player != null && mc.theWorld != null && mc.playerController != null
                && mc.currentScreen == null && mc.inGameHasFocus && player.getHealth() > 0.0F
                && !player.isRiding();
    }

    private static boolean live(Minecraft mc, EntityLargeFireball fireball) {
        return fireball != null && !fireball.isDead
                && mc.theWorld.getEntityByID(fireball.getEntityId()) == fireball;
    }

    private void pruneHandled(Minecraft mc) {
        for (Iterator<EntityLargeFireball> it = handled.iterator(); it.hasNext();) {
            if (!live(mc, it.next())) {
                it.remove();
            }
        }
    }

    private void clear(boolean clearHandled) {
        target = null;
        attackAfterUpdate = Integer.MIN_VALUE;
        if (clearHandled) {
            handled.clear();
        }
    }

    private static Threat predict(Minecraft mc, EntityPlayerSP player, EntityLargeFireball fireball) {
        AxisAlignedBB playerBox = player.getEntityBoundingBox().expand(0.3D, 0.3D, 0.3D);
        AxisAlignedBB collision = playerBox;
        AxisAlignedBB rawReach = fireball.getEntityBoundingBox();
        double border = fireball.getCollisionBorderSize();
        AxisAlignedBB reachBox = rawReach.expand(border, border, border);
        Vec3 eyes = player.getPositionEyes(1.0F);

        double startX = fireball.posX, startY = fireball.posY, startZ = fireball.posZ;
        double vx = fireball.motionX, vy = fireball.motionY, vz = fireball.motionZ;
        double ax = fireball.accelerationX, ay = fireball.accelerationY, az = fireball.accelerationZ;
        Vec3 direction = new Vec3(vx, vy, vz);
        if (direction.lengthVector() < 1.0E-8D) {
            direction = new Vec3(ax, ay, az);
        }
        if (direction.lengthVector() < 1.0E-8D) {
            return null;
        }
        direction = direction.normalize();
        Vec3 start = new Vec3(startX, startY, startZ);
        Vec3 center = collision.getCenter();
        double halfX = (collision.maxX - collision.minX) * 0.5D;
        double halfY = (collision.maxY - collision.minY) * 0.5D;
        double halfZ = (collision.maxZ - collision.minZ) * 0.5D;
        double rayLength = start.distanceTo(center)
                + Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ) + 1.0D;
        Vec3 rayEnd = start.addVector(direction.xCoord * rayLength,
                direction.yCoord * rayLength, direction.zCoord * rayLength);
        MovingObjectPosition entityHit = collision.calculateIntercept(start, rayEnd);
        Vec3 impact = distanceSquared(start, collision) == 0.0D
                ? start : entityHit == null ? null : entityHit.hitVec;
        if (impact == null) {
            return null;
        }

        MovingObjectPosition block = mc.theWorld.rayTraceBlocks(start, impact);
        if (block != null && blockedBefore(start, impact, block.hitVec)) {
            return null;
        }

        double impactDistance = start.distanceTo(impact);
        double ux = direction.xCoord, uy = direction.yCoord, uz = direction.zCoord;
        double x = startX, y = startY, z = startZ;
        int reachTick = Integer.MAX_VALUE;
        for (int tick = 0; tick <= MAX_PREDICTION_TICKS; tick++) {
            if (reachTick == Integer.MAX_VALUE
                    && distanceSquared(eyes, reachBox.offset(x - startX, y - startY, z - startZ))
                    <= REACH * REACH + 1.0E-9D) {
                reachTick = tick;
            }

            double travelled = (x - startX) * ux + (y - startY) * uy + (z - startZ) * uz;
            if (travelled + 1.0E-9D >= impactDistance) {
                return new Threat(fireball, tick, reachTick);
            }
            double nx = x + vx, ny = y + vy, nz = z + vz;
            double nextTravel = (nx - startX) * ux + (ny - startY) * uy + (nz - startZ) * uz;
            if (nextTravel + 1.0E-9D >= impactDistance) {
                double step = nextTravel - travelled;
                double fraction = step > 1.0E-9D
                        ? MathHelper.clamp_double((impactDistance - travelled) / step, 0.0D, 1.0D)
                        : 1.0D;
                return new Threat(fireball, tick + fraction, reachTick);
            }
            x = nx;
            y = ny;
            z = nz;
            vx = (vx + ax) * MOTION_FACTOR;
            vy = (vy + ay) * MOTION_FACTOR;
            vz = (vz + az) * MOTION_FACTOR;
        }
        return null;
    }

    private static boolean reachableTowardCenter(Minecraft mc, EntityPlayerSP player,
                                                  EntityLargeFireball fireball) {
        AxisAlignedBB box = fireball.getEntityBoundingBox();
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 center = box.getCenter();
        Vec3 look = center.subtract(eyes);
        if (look.lengthVector() == 0.0D) {
            return true;
        }
        return rayHits(mc, player, fireball, look.normalize());
    }

    private static boolean rayHits(Minecraft mc, EntityPlayerSP player, final EntityLargeFireball fireball,
                                   Vec3 look) {
        RayPicker.Result result = RayPicker.pick(
                mc.theWorld, player, player.getPositionEyes(1.0F), look,
                REACH, REACH, 1.0D,
                new Predicate<Entity>() {
                    @Override
                    public boolean apply(Entity candidate) {
                        return candidate == fireball;
                    }
                },
                false, false, false);
        return result.getEntity() == fireball;
    }

    static boolean shouldPrearm(double reachTick, double impactTick) {
        return (reachTick >= 0.0D && reachTick <= PREARM_TICKS)
                || (impactTick >= 0.0D && impactTick <= PREARM_TICKS);
    }

    static boolean earlier(double candidateTick, int candidateId, double bestTick, int bestId) {
        return candidateTick < bestTick || (candidateTick == bestTick && candidateId < bestId);
    }

    static double firstImpactTick(double x, double y, double z,
                                  double vx, double vy, double vz,
                                  double ax, double ay, double az,
                                  AxisAlignedBB box, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            Vec3 start = new Vec3(x, y, z);
            Vec3 end = new Vec3(nx, ny, nz);
            if (distanceSquared(start, box) == 0.0D) {
                return tick;
            }
            MovingObjectPosition hit = box.calculateIntercept(start, end);
            if (hit != null) {
                double step = start.distanceTo(end);
                return tick + (step == 0.0D ? 0.0D : start.distanceTo(hit.hitVec) / step);
            }
            x = nx;
            y = ny;
            z = nz;
            vx = (vx + ax) * MOTION_FACTOR;
            vy = (vy + ay) * MOTION_FACTOR;
            vz = (vz + az) * MOTION_FACTOR;
        }
        return -1.0D;
    }

    static double distanceSquared(Vec3 point, AxisAlignedBB box) {
        return box.squareDistanceTo(point);
    }

    static boolean blockedBefore(Vec3 start, Vec3 hit, Vec3 blockHit) {
        return start.squareDistanceTo(blockHit) + 1.0E-9D < start.squareDistanceTo(hit);
    }

    private static final class Threat {
        final EntityLargeFireball fireball;
        final double impactTick;
        final int reachTick;

        Threat(EntityLargeFireball fireball, double impactTick, int reachTick) {
            this.fireball = fireball;
            this.impactTick = impactTick;
            this.reachTick = reachTick;
        }
    }
}
