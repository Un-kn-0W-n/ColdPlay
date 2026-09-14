package coldplay.broker;

import coldplay.friend.FriendManager;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldSettings;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Bot verdicts for AntiBot; target pickers ask {@link #isBot} before using a player entity. */
public final class BotTracker {

    private static final BotTracker INSTANCE = new BotTracker();

    public static BotTracker getInstance() {
        return INSTANCE;
    }

    private BotTracker() {
    }

    public static final class Checks {
        public final boolean notInTab, invalidName, duplicateName, zeroPing, neverOnGround, invisibleSpawn;
        public final double spawnRadius;

        public Checks(boolean notInTab, boolean invalidName, boolean duplicateName, boolean zeroPing,
                      boolean neverOnGround, boolean invisibleSpawn, double spawnRadius) {
            this.notInTab = notInTab;
            this.invalidName = invalidName;
            this.duplicateName = duplicateName;
            this.zeroPing = zeroPing;
            this.neverOnGround = neverOnGround;
            this.invisibleSpawn = invisibleSpawn;
            this.spawnRadius = spawnRadius;
        }
    }

    private static final class History {
        final EntityOtherPlayerMP entity;
        final boolean invisibleSpawn;
        boolean seenGround;
        int seenTick;
        boolean bot;

        History(EntityOtherPlayerMP entity, boolean invisibleSpawn) {
            this.entity = entity;
            this.invisibleSpawn = invisibleSpawn;
        }
    }

    private boolean enabled;
    private Checks checks;
    private WorldClient world;
    private EntityPlayerSP player;
    private NetHandlerPlayClient net;
    private int tickSerial;
    private final Map<Integer, History> history = new HashMap<Integer, History>();
    private final Map<String, Integer> nameCounts = new HashMap<String, Integer>();

    public void configure(Checks checks) {
        this.checks = checks;
        enabled = true;
    }

    public void disable() {
        enabled = false;
        checks = null;
        clear();
    }

    private void clear() {
        history.clear();
        nameCounts.clear();
        world = null;
        player = null;
        net = null;
    }

    public void tick(EntityPlayerSP player, WorldClient world, NetHandlerPlayClient net) {
        if (!enabled) {
            return;
        }
        if (player == null || world == null || net == null) {
            clear();
            return;
        }
        if (world != this.world) {
            history.clear();
            this.world = world;
        }
        this.player = player;
        this.net = net;
        tickSerial++;
        nameCounts.clear();
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityOtherPlayerMP) {
                observe((EntityOtherPlayerMP) entity);
            }
        }
        for (Iterator<History> it = history.values().iterator(); it.hasNext(); ) {
            History h = it.next();
            if (h.seenTick != tickSerial) {
                it.remove();
            } else {
                judge(h);
            }
        }
    }

    /** Cheap enough for render loops: a few field checks and one map lookup per player entity. */
    public boolean isBot(Entity entity) {
        if (!enabled || !(entity instanceof EntityOtherPlayerMP) || world == null) {
            return false;
        }
        if (FriendManager.getInstance().isFriend(entity.getName())) {
            return false;
        }
        History h = history.get(entity.getEntityId());
        if (h == null || h.entity != entity) {
            // spawned since the last tick; judged now and kept until the next tick refreshes it
            h = observe((EntityOtherPlayerMP) entity);
            judge(h);
        }
        return h.bot;
    }

    private History observe(EntityOtherPlayerMP entity) {
        Integer id = entity.getEntityId();
        History h = history.get(id);
        if (h == null || h.entity != entity) {
            h = new History(entity, spawnedInvisibleNearby(entity.ticksExisted, entity.isInvisible(),
                    player.getDistanceToEntity(entity), checks.spawnRadius));
            history.put(id, h);
        }
        h.seenTick = tickSerial;
        if (!h.seenGround && (entity.onGround || standingOnBlock(entity))) {
            h.seenGround = true;
        }
        String name = entity.getName();
        if (name != null) {
            nameCounts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return h;
    }

    // A standing player sends no movement packets, so its onGround never arrives; ask the blocks instead.
    // The slab straddles the feet: a block top exactly at minY counts, a bot hovering 1/32 above does not.
    private boolean standingOnBlock(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        AxisAlignedBB feet = new AxisAlignedBB(box.minX, box.minY - 0.01, box.minZ,
                box.maxX, box.minY + 0.01, box.maxZ);
        return !world.getCollisionBoxes(feet).isEmpty();
    }

    private void judge(History h) {
        EntityOtherPlayerMP entity = h.entity;
        String name = entity.getName();
        NetworkPlayerInfo info = net.getPlayerInfo(entity.getUniqueID());
        h.bot = checks.notInTab && info == null
                || checks.invalidName && invalidName(name)
                || checks.duplicateName && duplicate(entity, name)
                || checks.zeroPing && zeroPing(info != null, info == null ? 0 : info.getResponseTime(),
                        info == null ? null : info.getGameType())
                || checks.neverOnGround && !h.seenGround
                || checks.invisibleSpawn && h.invisibleSpawn && entity.isInvisible();
    }

    private boolean duplicate(EntityOtherPlayerMP entity, String name) {
        if (name == null) {
            return false;
        }
        Integer copies = nameCounts.get(name.toLowerCase(Locale.ROOT));
        if (copies == null || copies < 2) {
            return false;
        }
        NetworkPlayerInfo byName = net.getPlayerInfo(name);
        return duplicateName(entity.getUniqueID(), copies, byName == null ? null : byName.getGameProfile().getId());
    }

    /** Empty, over 16 characters, or anything outside A-Z, a-z, 0-9 and underscore, which also rejects colour codes. */
    public static boolean invalidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return true;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) {
                return true;
            }
        }
        return false;
    }

    /** {@code loadedWithName} counts every loaded copy including this one; the copy the tab entry names is real. */
    public static boolean duplicateName(UUID uuid, int loadedWithName, UUID tabUuid) {
        return loadedWithName > 1 && (tabUuid == null || !tabUuid.equals(uuid));
    }

    public static boolean zeroPing(boolean inTab, int responseTime, WorldSettings.GameType gameType) {
        return inTab && (responseTime == 0 || gameType == null || gameType == WorldSettings.GameType.NOT_SET);
    }

    /** Recorded once, when tracking first sees the entity, so only brand-new entities qualify. */
    public static boolean spawnedInvisibleNearby(int ticksExisted, boolean invisible, double distance, double radius) {
        return ticksExisted <= 2 && invisible && distance <= radius;
    }
}
