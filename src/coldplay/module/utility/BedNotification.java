package coldplay.module.utility;

import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.friend.FriendManager;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.broker.BedTracker;
import coldplay.util.ChatUtil;
import coldplay.util.EntityTargets;
import coldplay.broker.GameStateTracker;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Warns in chat as enemies cross distance bands around your bed during a BedWars match. */
public final class BedNotification extends Module {
    private static final int MAX_DISTANCE = 40;
    private static final int BAND_SIZE = 10;
    private static final int REARM_DISTANCE = MAX_DISTANCE + BAND_SIZE;

    private Map<String, Integer> notifiedBands = new HashMap<String, Integer>();
    private WorldClient trackedWorld;
    private BlockPos trackedBedFoot;

    public BedNotification() {
        super("BedNotification", Category.UTILITY,
                "Warns when a non-friend player approaches your bed.");
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        BlockPos foot = BedTracker.getInstance().ownBedFoot();
        if (player == null || world == null || foot == null || !world.isBlockLoaded(foot, false)
                || !inBedWarsMatch(world.getScoreboard())) {
            reset();
            return;
        }

        IBlockState footState = world.getBlockState(foot);
        if (footState.getBlock() != Blocks.bed) {
            reset();
            return;
        }
        EnumFacing facing = (EnumFacing) footState.getValue(BlockBed.FACING);
        BlockPos head = foot.offset(facing);
        if (!world.isBlockLoaded(head, false) || world.getBlockState(head).getBlock() != Blocks.bed) {
            reset();
            return;
        }

        if (GameStateTracker.getInstance().transitionThisTick()
                || trackedWorld != null && world != trackedWorld
                || trackedBedFoot != null && !foot.equals(trackedBedFoot)) {
            reset();
            return;
        }
        trackedWorld = world;
        trackedBedFoot = foot;

        double bedX = (foot.getX() + head.getX() + 1.0D) * 0.5D;
        double bedY = foot.getY() + 0.28125D;
        double bedZ = (foot.getZ() + head.getZ() + 1.0D) * 0.5D;
        Map<String, Integer> next = new HashMap<String, Integer>();
        List<String> alerts = new ArrayList<String>();

        for (EntityPlayer target : world.playerEntities) {
            if (!isEligible(player, target)) {
                continue;
            }
            double dx = target.posX - bedX;
            double dy = target.posY - bedY;
            double dz = target.posZ - bedZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String name = target.getName();
            Integer previous = notifiedBands.get(name);
            Integer updated = nextBand(previous, distance);
            if (updated == null) {
                continue;
            }
            next.put(name, updated);
            if (distance <= MAX_DISTANCE && !updated.equals(previous)) {
                alerts.add(name + " is " + Math.round(distance) + " blocks away from the bed.");
            }
        }

        notifiedBands = next;
        for (String alert : alerts) {
            ChatUtil.error(alert);
        }
    }

    /** Hypixel's match sidebar lists each team with a ✓ or ✘; lobbies never draw those. */
    private static boolean inBedWarsMatch(Scoreboard scoreboard) {
        ScoreObjective sidebar = scoreboard.getObjectiveInDisplaySlot(1);
        if (sidebar == null) {
            return false;
        }
        for (Score score : scoreboard.getSortedScores(sidebar)) {
            String name = score.getPlayerName();
            if (isTeamStatusLine(ScorePlayerTeam.formatPlayerName(scoreboard.getPlayersTeam(name), name))) {
                return true;
            }
        }
        return false;
    }

    static boolean isTeamStatusLine(String line) {
        return line.indexOf('✓') >= 0 || line.indexOf('✘') >= 0;
    }

    private static boolean isEligible(EntityPlayerSP player, EntityPlayer target) {
        return EntityTargets.isLivingTarget(player, target, false)
                && EntityTargets.classify(target) == EntityTargets.Type.PLAYER
                && !target.isSpectator()
                && !player.isOnSameTeam(target)
                && !FriendManager.getInstance().isFriend(target.getName());
    }

    /** Latches the closest band and holds it through the outer margin against jitter. */
    static Integer nextBand(Integer previous, double distance) {
        if (distance > REARM_DISTANCE) {
            return null;
        }
        if (distance > MAX_DISTANCE) {
            return previous;
        }
        int current = Math.max(BAND_SIZE, (int) Math.ceil(distance / BAND_SIZE) * BAND_SIZE);
        return previous == null || current < previous ? current : previous;
    }

    private void reset() {
        notifiedBands.clear();
        trackedWorld = null;
        trackedBedFoot = null;
    }
}
