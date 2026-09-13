package coldplay.module.visual;

import com.google.common.base.Predicate;

import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ColorSetting;
import coldplay.setting.ItemGridSetting;
import coldplay.util.EntityTargets;
import coldplay.util.RenderUtil;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectilePhysics;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Predicts the held projectile with shared vanilla physics; an undrawn bow previews full charge. */
public class Trajectory extends Module {
    private final ItemGridSetting.Entry bowEntry = new ItemGridSetting.Entry("Bow", new ItemStack(Items.bow), true);
    private final ItemGridSetting.Entry snowballEntry = new ItemGridSetting.Entry("SnowBall", new ItemStack(Items.snowball), true);
    private final ItemGridSetting.Entry eggEntry = new ItemGridSetting.Entry("Egg", new ItemStack(Items.egg), true);
    private final ItemGridSetting.Entry pearlEntry = new ItemGridSetting.Entry("EnderPearl", new ItemStack(Items.ender_pearl), true);
    @SuppressWarnings("unused") // registered for the GUI; the entries are read directly
    private final ItemGridSetting items = add(new ItemGridSetting("Items", bowEntry, snowballEntry, eggEntry, pearlEntry)
            .describe("Which held projectiles get a predicted path."));

    private final ColorSetting color = add(new ColorSetting("Color", 0x64C8FF)
            .describe("Path line and landing-block colour."));
    private final ColorSetting targetColor = add(new ColorSetting("Target Color", 0xFF3C3C)
            .describe("Highlight colour when the path hits an entity."));

    private static final int MAX_SIM_TICKS = 200;
    private static final int LANDING_FILL_ALPHA = 90;

    private final Predicate<Entity> livingTargets = new Predicate<Entity>() {
        public boolean apply(Entity entity) {
            return EntityTargets.isLivingTarget(Minecraft.getMinecraft().thePlayer, entity, true);
        }
    };

    public Trajectory() {
        super("Trajectory", Category.VISUAL,
                "Shows where the held bow shot or throwable will land; highlights the entity it would hit.");
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        ItemStack held = player.getHeldItem();
        double speed = launchSpeed(player, held);
        if (speed <= 0.0) {
            return;
        }

        float partialTicks = event.getPartialTicks();
        boolean arrow = held.getItem() == Items.bow;
        Flight flight = simulate(player, world, speed, arrow, partialTicks);

        RenderUtil.beginWorldOverlay(2.0F);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(3, DefaultVertexFormats.POSITION_COLOR); // 3 = GL_LINE_STRIP
        for (Vec3 point : flight.path) {
            wr.pos(point.xCoord, point.yCoord, point.zCoord)
                    .color(color.red(), color.green(), color.blue(), 255).endVertex();
        }
        tessellator.draw();

        if (flight.hitEntity != null) {
            Entity victim = flight.hitEntity;
            double ex = RenderUtil.interp(victim.lastTickPosX, victim.posX, partialTicks) - victim.posX;
            double ey = RenderUtil.interp(victim.lastTickPosY, victim.posY, partialTicks) - victim.posY;
            double ez = RenderUtil.interp(victim.lastTickPosZ, victim.posZ, partialTicks) - victim.posZ;
            AxisAlignedBB box = victim.getEntityBoundingBox().offset(ex, ey, ez);
            RenderUtil.drawFilledBox(box, targetColor.red(), targetColor.green(), targetColor.blue(), LANDING_FILL_ALPHA);
            RenderGlobal.drawOutlinedBoundingBox(box, targetColor.red(), targetColor.green(), targetColor.blue(), 255);
        } else if (flight.hitBlock != null) {
            Block block = world.getBlockState(flight.hitBlock).getBlock();
            block.setBlockBoundsBasedOnState(world, flight.hitBlock);
            AxisAlignedBB quad = faceQuad(block.getSelectedBoundingBox(world, flight.hitBlock), flight.hitFace);
            RenderUtil.drawFilledBox(quad, color.red(), color.green(), color.blue(), LANDING_FILL_ALPHA);
            RenderGlobal.drawOutlinedBoundingBox(quad, color.red(), color.green(), color.blue(), 255);
        }

        RenderUtil.endWorldOverlay();
    }

    private double launchSpeed(EntityPlayerSP player, ItemStack held) {
        if (held == null) {
            return 0.0;
        }
        Item item = held.getItem();
        if (item == Items.bow && bowEntry.isEnabled()) {
            float charge = 1.0F;
            if (player.isUsingItem() && player.getItemInUse() == held) {
                charge = ProjectilePhysics.bowCharge(player.getItemInUseDuration());
                if (charge < 0.1F) {
                    return 0.0; // vanilla discards shots under this charge
                }
            }
            return ProjectilePhysics.bowLaunchSpeed(charge);
        }
        if ((item == Items.snowball && snowballEntry.isEnabled())
                || (item == Items.egg && eggEntry.isEnabled())
                || (item == Items.ender_pearl && pearlEntry.isEnabled())) {
            return ProjectilePhysics.THROWABLE_SPEED;
        }
        return 0.0;
    }

    private static final class Flight {
        final List<Vec3> path = new ArrayList<>();
        Entity hitEntity;
        BlockPos hitBlock;
        EnumFacing hitFace;
    }

    private static AxisAlignedBB faceQuad(AxisAlignedBB box, EnumFacing face) {
        switch (face) {
            case UP:    return new AxisAlignedBB(box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ);
            case DOWN:  return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ);
            case NORTH: return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ);
            case SOUTH: return new AxisAlignedBB(box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ);
            case WEST:  return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ);
            case EAST:
            default:    return new AxisAlignedBB(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }
    }

    private Flight simulate(final EntityPlayerSP player, WorldClient world, double speed,
                            boolean arrow, float partialTicks) {
        Flight flight = new Flight();
        final ProjectilePhysics.Profile profile = arrow
                ? ProjectilePhysics.ARROW_PROFILE : ProjectilePhysics.THROWABLE_PROFILE;
        Vec3 position = ProjectilePhysics.spawnPosition(player, partialTicks);
        Vec3 motion = ProjectilePhysics.scale(
                ProjectilePhysics.lookDirection(player.rotationYaw, player.rotationPitch), speed);
        ProjectilePhysics.StepState state = new ProjectilePhysics.StepState(
                position, motion, profile.boundsAt(position));

        flight.path.add(position);
        for (int tick = 0; tick < MAX_SIM_TICKS; tick++) {
            boolean inWater = ProjectilePhysics.isInWater(world, state.getBounds());
            ProjectilePhysics.StepResult step = ProjectilePhysics.step(
                    world,
                    state,
                    profile,
                    player,
                    livingTargets,
                    true,
                    inWater);

            MovingObjectPosition entityHit = step.getEntityHit();
            if (entityHit != null) {
                flight.path.add(entityHit.hitVec);
                flight.hitEntity = entityHit.entityHit;
                return flight;
            }
            MovingObjectPosition blockHit = step.getBlockHit();
            if (blockHit != null) {
                flight.path.add(blockHit.hitVec);
                flight.hitBlock = blockHit.getBlockPos();
                flight.hitFace = blockHit.sideHit;
                return flight;
            }

            state = step.getNextState();
            flight.path.add(state.getPosition());
            if (state.getPosition().yCoord < 0.0) {
                break; // fell into the void
            }
        }
        return flight;
    }

}
