package coldplay.module.visual;

import coldplay.broker.BotTracker;
import coldplay.friend.FriendManager;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.EntityTargets;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame entity selection shared by ESP, Tracers, Arrows and NameTags. The event bus only sees
 * declared methods, so each subclass declares its own handlers and calls collectTargets.
 */
abstract class EntityVisual extends Module {
    private final BooleanSetting players = new BooleanSetting("Players", true).describe("Show other players.");
    private final BooleanSetting mobs = new BooleanSetting("Mobs", true).describe("Show hostile mobs.");
    private final BooleanSetting animals = new BooleanSetting("Animals", true).describe("Show passive animals.");
    private final BooleanSetting invisible = new BooleanSetting("Invisible", false).describe("Also show invisible entities.");
    private final NumberSetting range = new NumberSetting("Range", 64.0, 8.0, 256.0, 8.0).describe("Max distance to highlight entities, in blocks.");

    private final ColorSetting playerColor = new ColorSetting("Player Color", 0xFFFFFF).describe("Colour used for players.");
    private final ColorSetting mobColor = new ColorSetting("Mob Color", 0xFF3C3C).describe("Colour used for hostile mobs.");
    private final ColorSetting animalColor = new ColorSetting("Animal Color", 0x50DC64).describe("Colour used for passive animals.");

    protected final List<Target> targets = new ArrayList<Target>();

    protected EntityVisual(String name, String description) {
        super(name, Category.VISUAL, description);
    }

    /** Call at the end of the constructor so the filters sit below the module's own settings. */
    protected void addFilters() {
        add(new HeaderSetting("Filters"));
        add(players);
        add(mobs);
        add(animals);
        add(invisible);
        add(range);
    }

    protected void addColors() {
        add(new HeaderSetting("Colors"));
        add(playerColor);
        add(mobColor);
        add(animalColor);
    }

    protected void collectTargets(float partialTicks) {
        targets.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        double maxDistSq = range.get() * range.get();
        for (Entity entity : world.loadedEntityList) {
            double distanceSq = player.getDistanceSqToEntity(entity);
            // cheap gates first; getName allocates and the friend lookup locks
            if (!EntityTargets.isLivingTarget(player, entity, invisible.get()) || distanceSq > maxDistSq) {
                continue;
            }
            if (BotTracker.getInstance().isBot(entity)) {
                continue;
            }
            int friendColor = entity instanceof EntityPlayer
                    ? FriendManager.getInstance().getColor(entity.getName()) : 0;
            int color = colorFor(entity, friendColor);
            if (color == 0) {
                continue;
            }
            Vec3 pos = RenderUtil.interpolatedPosition(entity, partialTicks);
            double halfWidth = entity.width / 2.0;
            AxisAlignedBB box = new AxisAlignedBB(pos.xCoord - halfWidth, pos.yCoord, pos.zCoord - halfWidth,
                    pos.xCoord + halfWidth, pos.yCoord + entity.height, pos.zCoord + halfWidth);
            targets.add(new Target(entity, color, friendColor, box, pos.xCoord,
                    pos.yCoord + entity.height / 2.0, pos.zCoord, Math.sqrt(distanceSq)));
        }
    }

    private int colorFor(Entity entity, int friendColor) {
        if (entity instanceof EntityPlayer) { // covers player-shaped NPCs too
            if (!players.get()) {
                return 0;
            }
            return friendColor != 0 ? friendColor : playerColor.get();
        }
        if (EntityTargets.isMob(entity)) {
            return mobs.get() ? mobColor.get() : 0;
        }
        if (EntityTargets.isAnimal(entity)) {
            return animals.get() ? animalColor.get() : 0;
        }
        return 0; // villagers and the rest
    }

    /** One selected entity, interpolated to this frame. */
    static final class Target {
        final Entity entity;
        final int color;
        final int friendColor;
        final AxisAlignedBB box;
        final double centerX;
        final double centerY;
        final double centerZ;
        final double distance;

        Target(Entity entity, int color, int friendColor, AxisAlignedBB box,
               double centerX, double centerY, double centerZ, double distance) {
            this.entity = entity;
            this.color = color;
            this.friendColor = friendColor;
            this.box = box;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.distance = distance;
        }
    }
}
