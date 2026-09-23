package coldplay.module.utility;

import coldplay.broker.PacketLog;
import coldplay.config.JsonStore;
import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ButtonSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ChatUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Streams every login/play packet and a per-tick player snapshot to CSV. Remembers, per server, every value each
 * packet field has carried, so the "new" column marks what the server has never sent before. Probes do one odd
 * thing on purpose so the server's reaction can be read back from the recording.
 */
public class ACLearner extends Module {

    private static final int MAX_VALUES = 32; // distinct values per field before it counts as varied
    private static final int MAX_VALUE_LENGTH = 64;
    private static final int SENT_HISTORY = 100; // position packets kept for matching setbacks
    private static final int PROBE_WAIT_TICKS = 40;
    private static volatile ACLearner active;

    private final Supplier<List<Module>> modules;
    private final ModeSetting probe;
    private final NumberSetting distance;
    private final Map<String, Long> lastNanos = new HashMap<String, Long>();
    private final ArrayDeque<double[]> sent = new ArrayDeque<double[]>(); // tick, x, y, z
    private Knowledge knowledge;
    private Writer out;
    private File file;
    private long rows;
    private volatile long tick;
    private String today;
    private String lastServer;
    private String lastModules;
    private int selfId = Integer.MIN_VALUE;
    private boolean velocityPending; // a self S12 arrived and the S32 after it has not
    private boolean velocityUnanswered;
    private short velocityTransaction;
    private long velocityReplyTick = -1;
    private long lastSetbackNanos;
    private String pendingProbe;
    private String confirmProbe; // Bad Confirm, No Confirm or Follow waiting for a setback
    private long confirmUntil;
    private double[] echo; // setback position whose C06 answer gets rewritten or dropped
    private Vec3 shift;
    private double[] home;
    private AxisAlignedBB homeBox;
    private Entity hitTarget;

    public ACLearner(Supplier<List<Module>> modules) {
        super("ACLearner", Category.UTILITY,
                "Records everything the server sends and learns what is normal for it, flagging anything new; probes test how it corrects you.");
        this.modules = modules;
        add(new ButtonSetting("Export CSV", this::export)
                .describe("Close the current recording, start a new one and write what has been learned so far to coldplay/recordings/."));
        add(new ButtonSetting("Mark", () -> note("Mark", "marked by hand"))
                .describe("Write a marker row so a moment can be found in the recording later."));
        probe = add(new ModeSetting("Probe", "Offset", "Offset", "Offset Hit", "Bad Confirm", "No Confirm", "Follow")
                .describe("Offset: one tick's position is sent Distance along your look, the next tick goes back. Offset Hit: the same toward the aimed or nearest player, attacking from there. Bad Confirm: Offset, then answer the setback Distance away. No Confirm: Offset, then never answer the setback. Follow: Bad Confirm, then Offset again while the setback is unanswered."));
        distance = add(new NumberSetting("Distance", 1.0, 0.1, 10.0, 0.1)
                .describe("How far a probe moves the sent position, in blocks."));
        add(new ButtonSetting("Run Probe", this::runProbe)
                .describe("Run the selected probe on the next tick. Turn off FakeLag, Velocity and combat modules first so they do not mix in."));
    }

    public static void receive(Packet<?> packet) {
        ACLearner learner = active;
        if (learner != null) {
            learner.log(packet, false);
        }
    }

    /** False drops the packet. */
    public static boolean send(Packet<?> packet) {
        ACLearner learner = active;
        return learner == null || learner.log(packet, true);
    }

    @Override
    public String getSuffix() {
        return null;
    }

    @Override
    protected void onEnable() {
        knowledge = store().load();
        today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        tick = 0;
        lastNanos.clear();
        sent.clear();
        lastServer = null;
        lastModules = null;
        selfId = Integer.MIN_VALUE;
        velocityPending = false;
        velocityUnanswered = false;
        velocityReplyTick = -1;
        pendingProbe = null;
        confirmProbe = null;
        echo = null;
        open();
        active = this;
    }

    @Override
    protected void onDisable() {
        active = null;
        synchronized (this) {
            close();
        }
        store().save(knowledge);
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        tick++;
        Minecraft mc = Minecraft.getMinecraft();
        StringBuilder enabled = new StringBuilder();
        for (Module module : modules.get()) {
            if (module.isEnabled()) {
                enabled.append(enabled.length() == 0 ? "" : ",").append(module.getName());
            }
        }
        synchronized (this) {
            if (out == null) {
                return;
            }
            try {
                String server = server();
                if (!server.equals(lastServer)) {
                    lastServer = server;
                    write("META", "Server", "", "server=" + server + "; knownEntries=" + knowledge.stats.size());
                }
                if (!enabled.toString().equals(lastModules)) {
                    lastModules = enabled.toString();
                    write("META", "Modules", "", lastModules);
                }
                if (confirmProbe != null && tick > confirmUntil) {
                    write("META", "Probe", "", confirmProbe + ": no setback within " + PROBE_WAIT_TICKS + " ticks");
                    confirmProbe = null;
                    echo = null;
                }
                if (mc.thePlayer != null) {
                    selfId = mc.thePlayer.getEntityId(); // a recording started mid-session never sees S01
                    write("TICK", "Tick", "", state(mc, mc.thePlayer));
                }
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    // DRAIN so other modules see the real position before the probe moves it for the packet.
    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.thePlayer;
        if (event.isPre()) {
            String run;
            synchronized (this) {
                run = pendingProbe;
                pendingProbe = null;
            }
            if (run != null && p != null) {
                startProbe(mc, p, run);
            }
        } else if (home != null) {
            if (hitTarget != null) {
                p.swingItem();
                mc.playerController.attackEntity(p, hitTarget);
            }
            p.setEntityBoundingBox(homeBox);
            p.posX = home[0];
            p.posY = home[1];
            p.posZ = home[2];
            home = null;
            hitTarget = null;
        }
    }

    private void runProbe() {
        if (!isEnabled()) {
            ChatUtil.info("Enable ACLearner first.");
            return;
        }
        synchronized (this) {
            pendingProbe = probe.get();
        }
    }

    /** Moves the player for the length of onUpdateWalkingPlayer, so exactly this tick's packet is displaced. */
    private void startProbe(Minecraft mc, EntityPlayerSP p, String run) {
        Entity target = null;
        Vec3 direction = p.getLookVec();
        if (run.equals("Offset Hit")) {
            target = mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null
                    ? mc.objectMouseOver.entityHit : nearestPlayer(mc, p);
            if (target == null) {
                note("Probe", "Offset Hit: no target");
                return;
            }
            direction = new Vec3(target.posX - p.posX, target.posY - p.posY, target.posZ - p.posZ).normalize();
        }
        double d = distance.get();
        Vec3 offset = new Vec3(direction.xCoord * d, direction.yCoord * d, direction.zCoord * d);
        String fields = "probe=" + run + "; distance=" + d + "; from=" + p.posX + "," + p.posY + "," + p.posZ;
        if (target != null) {
            fields += "; target=" + target.getEntityId() + "; reachFrom=" + reach(p, target);
        }
        home = new double[]{p.posX, p.posY, p.posZ};
        homeBox = p.getEntityBoundingBox();
        hitTarget = target;
        p.setEntityBoundingBox(homeBox.offset(offset.xCoord, offset.yCoord, offset.zCoord));
        p.posX += offset.xCoord;
        p.posY += offset.yCoord;
        p.posZ += offset.zCoord;
        fields += "; to=" + p.posX + "," + p.posY + "," + p.posZ;
        if (target != null) {
            fields += "; reachTo=" + reach(p, target);
        }
        synchronized (this) {
            if (!run.equals("Offset") && !run.equals("Offset Hit")) {
                confirmProbe = run;
                confirmUntil = tick + PROBE_WAIT_TICKS;
                shift = offset;
                echo = null;
            }
        }
        note("Probe", fields);
    }

    private synchronized boolean log(Packet<?> packet, boolean outbound) {
        EnumConnectionState state = EnumConnectionState.getFromPacket(packet);
        if (out == null || (state != EnumConnectionState.PLAY && state != EnumConnectionState.LOGIN)) {
            return true;
        }
        boolean keep = true;
        // Inbound runs on the netty thread; anything thrown here would disconnect.
        try {
            Map<String, Object> derived = new LinkedHashMap<String, Object>();
            keep = outbound ? outbound(packet, derived) : inbound(packet, derived);
            String name = packet.getClass().getSimpleName();
            String key = server() + "|" + name + "|";
            StringBuilder fresh = new StringBuilder(keep && learn(key, null, null) ? "packet" : "");
            StringBuilder fields = new StringBuilder();
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (Field field : PacketLog.fields(packet.getClass())) {
                values.put(field.getName(), field.get(packet));
            }
            values.putAll(derived);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                StringBuilder text = new StringBuilder();
                PacketLog.appendValue(text, entry.getValue());
                String shown = text.toString();
                fields.append(fields.length() == 0 ? "" : "; ").append(entry.getKey()).append('=').append(shown);
                String kept = shown.length() > MAX_VALUE_LENGTH ? shown.substring(0, MAX_VALUE_LENGTH) : shown;
                if (keep && learn(key + entry.getKey(), kept, entry.getValue())) {
                    fresh.append(fresh.length() == 0 ? "" : "; ").append(entry.getKey()).append('=').append(kept);
                }
            }
            Long last = lastNanos.get(name);
            if (keep && last != null) {
                learn(key + "(gapMs)", null, (System.nanoTime() - last) / 1e6);
            }
            write(outbound ? (keep ? "OUT" : "DROP") : "IN", name, fresh.toString(), fields.toString());
        } catch (Exception ignored) {
        }
        return keep;
    }

    private boolean inbound(Packet<?> packet, Map<String, Object> derived) throws IOException {
        if (packet instanceof S01PacketJoinGame) {
            selfId = ((S01PacketJoinGame) packet).getEntityId();
        } else if (packet instanceof S12PacketEntityVelocity && ((S12PacketEntityVelocity) packet).getEntityID() == selfId) {
            velocityPending = true;
            derived.put("(withSetback)", System.nanoTime() - lastSetbackNanos < 5_000_000L);
        } else if (packet instanceof S32PacketConfirmTransaction && velocityPending) {
            velocityPending = false;
            velocityUnanswered = true;
            velocityTransaction = ((S32PacketConfirmTransaction) packet).getActionNumber();
        } else if (packet instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook setback = (S08PacketPlayerPosLook) packet;
            lastSetbackNanos = System.nanoTime();
            derived.put("(sentTicksAgo)", sentTicksAgo(setback.getX(), setback.getY(), setback.getZ()));
            double[] last = sent.peekLast();
            if (last != null) {
                double dx = setback.getX() - last[1], dy = setback.getY() - last[2], dz = setback.getZ() - last[3];
                derived.put("(fromLastSent)", Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 10.0) / 10.0);
            }
            derived.put("(ticksSinceVelocityReply)", velocityReplyTick < 0 ? "none" : tick - velocityReplyTick);
            Set<S08PacketPlayerPosLook.EnumFlags> flags = setback.func_179834_f();
            if (confirmProbe != null && echo == null && !flags.contains(S08PacketPlayerPosLook.EnumFlags.X)
                    && !flags.contains(S08PacketPlayerPosLook.EnumFlags.Y) && !flags.contains(S08PacketPlayerPosLook.EnumFlags.Z)) {
                echo = new double[]{setback.getX(), setback.getY(), setback.getZ()};
            }
        }
        return true;
    }

    private boolean outbound(Packet<?> packet, Map<String, Object> derived) throws IOException {
        if (packet instanceof C0FPacketConfirmTransaction && velocityUnanswered
                && ((C0FPacketConfirmTransaction) packet).getUid() == velocityTransaction) {
            velocityUnanswered = false;
            velocityReplyTick = tick;
            derived.put("(velocityReply)", true);
        } else if (packet instanceof C03PacketPlayer) {
            C03PacketPlayer move = (C03PacketPlayer) packet;
            if (echo != null && packet instanceof C03PacketPlayer.C06PacketPlayerPosLook
                    && Math.abs(move.getPositionX() - echo[0]) < 1e-9 && Math.abs(move.getPositionY() - echo[1]) < 1e-9
                    && Math.abs(move.getPositionZ() - echo[2]) < 1e-9) {
                String run = confirmProbe;
                confirmProbe = null;
                echo = null;
                if (run.equals("No Confirm")) {
                    write("META", "Probe", "", "No Confirm: dropped the setback answer");
                    return false;
                }
                move.setPosition(move.getPositionX() + shift.xCoord, move.getPositionY() + shift.yCoord,
                        move.getPositionZ() + shift.zCoord);
                write("META", "Probe", "", run + ": answered the setback at " + move.getPositionX() + ","
                        + move.getPositionY() + "," + move.getPositionZ());
                if (run.equals("Follow")) {
                    pendingProbe = "Offset";
                }
            }
            if (move.isMoving() && move.getPositionY() != -999.0D) {
                sent.addLast(new double[]{tick, move.getPositionX(), move.getPositionY(), move.getPositionZ()});
                if (sent.size() > SENT_HISTORY) {
                    sent.removeFirst();
                }
            }
        }
        return true;
    }

    private Object sentTicksAgo(double x, double y, double z) {
        for (Iterator<double[]> it = sent.descendingIterator(); it.hasNext(); ) {
            double[] at = it.next();
            if (Math.abs(at[1] - x) < 1e-9 && Math.abs(at[2] - y) < 1e-9 && Math.abs(at[3] - z) < 1e-9) {
                return tick - (long) at[0];
            }
        }
        return "never";
    }

    /** Returns true the first time a value is seen in this field, or the first time the key is seen when value is null. */
    private boolean learn(String key, String value, Object raw) {
        Stat stat = knowledge.stats.get(key);
        if (stat == null) {
            stat = new Stat();
            stat.firstSeen = today;
            knowledge.stats.put(key, stat);
        }
        stat.count++;
        stat.lastSeen = today;
        if (raw instanceof Number) {
            double number = ((Number) raw).doubleValue();
            stat.min = stat.min == null ? number : Math.min(stat.min, number);
            stat.max = stat.max == null ? number : Math.max(stat.max, number);
        }
        if (value == null) {
            return stat.count == 1;
        }
        if (stat.varied) {
            return false;
        }
        Long times = stat.seen.get(value);
        if (times != null) {
            stat.seen.put(value, times + 1);
            return false;
        }
        if (stat.seen.size() >= MAX_VALUES) {
            stat.varied = true;
            stat.seen.clear();
            return false;
        }
        stat.seen.put(value, 1L);
        return true;
    }

    private synchronized void note(String name, String fields) {
        if (out == null) {
            return;
        }
        try {
            write("META", name, "", fields);
        } catch (IOException ignored) {
        }
    }

    private void write(String direction, String name, String fresh, String fields) throws IOException {
        long nanos = System.nanoTime();
        Long last = lastNanos.put(name, nanos);
        out.write(System.currentTimeMillis() + "," + nanos + "," + tick + "," + direction + "," + name + ","
                + (last == null ? "" : String.valueOf((nanos - last) / 1e6)) + ","
                + PacketLog.csv(fresh) + "," + PacketLog.csv(fields) + "\n");
        rows++;
    }

    private static String state(Minecraft mc, EntityPlayerSP p) {
        StringBuilder potions = new StringBuilder();
        for (PotionEffect effect : p.getActivePotionEffects()) {
            potions.append(potions.length() == 0 ? "" : ",").append(effect.getEffectName())
                    .append(':').append(effect.getAmplifier()).append(':').append(effect.getDuration());
        }
        ItemStack held = p.getHeldItem();
        NetworkPlayerInfo info = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(p.getUniqueID());
        EntityPlayer near = nearestPlayer(mc, p);
        return "x=" + p.posX + "; y=" + p.posY + "; z=" + p.posZ
                + "; motionX=" + p.motionX + "; motionY=" + p.motionY + "; motionZ=" + p.motionZ
                + "; yaw=" + p.rotationYaw + "; pitch=" + p.rotationPitch
                + "; onGround=" + p.onGround + "; collidedH=" + p.isCollidedHorizontally
                + "; collidedV=" + p.isCollidedVertically + "; fallDistance=" + p.fallDistance
                + "; sprinting=" + p.isSprinting() + "; sneaking=" + p.isSneaking()
                + "; usingItem=" + p.isUsingItem() + "; blocking=" + p.isBlocking()
                + "; hurtTime=" + p.hurtTime + "; health=" + p.getHealth() + "; absorption=" + p.getAbsorptionAmount()
                + "; food=" + p.getFoodStats().getFoodLevel() + "; saturation=" + p.getFoodStats().getSaturationLevel()
                + "; slot=" + p.inventory.currentItem + "; held=" + (held == null ? "none"
                        : Item.itemRegistry.getNameForObject(held.getItem()) + ":" + held.getMetadata() + "x" + held.stackSize)
                + "; inWater=" + p.isInWater() + "; inLava=" + p.isInLava() + "; onLadder=" + p.isOnLadder()
                + "; riding=" + (p.ridingEntity == null ? "none" : p.ridingEntity.getClass().getSimpleName())
                + "; flying=" + p.capabilities.isFlying + "; allowFlying=" + p.capabilities.allowFlying
                + "; speed=" + p.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue()
                + "; potions=[" + potions + "]"
                + "; gameType=" + mc.playerController.getCurrentGameType()
                + "; ping=" + (info == null ? -1 : info.getResponseTime())
                + "; screen=" + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName())
                + "; forward=" + p.movementInput.moveForward + "; strafe=" + p.movementInput.moveStrafe
                + "; jump=" + p.movementInput.jump + "; sneak=" + p.movementInput.sneak
                + "; ticksExisted=" + p.ticksExisted + "; fps=" + Minecraft.getDebugFPS()
                + "; nearest=" + (near == null ? "none" : near.getEntityId() + ":" + near.getName()
                        + "@" + near.posX + "," + near.posY + "," + near.posZ + "; nearestReach=" + reach(p, near)
                        + "; nearestHurt=" + near.hurtTime);
    }

    private static EntityPlayer nearestPlayer(Minecraft mc, EntityPlayerSP p) {
        EntityPlayer best = null;
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other != p && (best == null || p.getDistanceSqToEntity(other) < p.getDistanceSqToEntity(best))) {
                best = other;
            }
        }
        return best;
    }

    // eye to the nearest point of the unexpanded hitbox
    private static double reach(EntityPlayerSP p, Entity target) {
        Vec3 eye = p.getPositionEyes(1.0F);
        AxisAlignedBB box = target.getEntityBoundingBox();
        double dx = Math.max(0.0, Math.max(box.minX - eye.xCoord, eye.xCoord - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - eye.yCoord, eye.yCoord - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - eye.zCoord, eye.zCoord - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private synchronized void export() {
        if (knowledge == null) {
            knowledge = store().load();
        }
        String recording = "";
        if (out != null) {
            recording = file.getName() + " (" + rows + " rows) and ";
            close();
            open();
        }
        store().save(knowledge);
        File csv = new File(directory(), "aclearner-knowledge-" + stamp() + ".csv");
        try (Writer writer = Files.newBufferedWriter(csv.toPath(), StandardCharsets.UTF_8)) {
            writer.write("server,packet,field,count,varied,values,min,max,firstSeen,lastSeen\n");
            for (Map.Entry<String, Stat> entry : new TreeMap<String, Stat>(knowledge.stats).entrySet()) {
                String[] key = entry.getKey().split("\\|", 3);
                Stat stat = entry.getValue();
                StringBuilder values = new StringBuilder();
                for (Map.Entry<String, Long> seen : stat.seen.entrySet()) {
                    values.append(values.length() == 0 ? "" : " | ").append(seen.getKey()).append(" (").append(seen.getValue()).append(')');
                }
                writer.write(PacketLog.csv(key[0]) + "," + key[1] + "," + PacketLog.csv(key[2].isEmpty() ? "(packet)" : key[2])
                        + "," + stat.count + "," + stat.varied + "," + PacketLog.csv(values.toString())
                        + "," + (stat.min == null ? "" : stat.min) + "," + (stat.max == null ? "" : stat.max)
                        + "," + stat.firstSeen + "," + stat.lastSeen + "\n");
            }
        } catch (IOException exception) {
            ChatUtil.error("Export failed: " + exception.getMessage());
            return;
        }
        ChatUtil.success("Exported " + recording + csv.getName());
    }

    private void open() {
        File dir = directory();
        dir.mkdirs();
        file = new File(dir, "aclearner-" + stamp() + ".csv");
        try {
            out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
            out.write("timeMs,nanoTime,tick,dir,packet,gapMs,new,fields\n");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        rows = 0;
    }

    private void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    private static String server() {
        ServerData data = Minecraft.getMinecraft().getCurrentServerData();
        return data == null ? "singleplayer" : data.serverIP.toLowerCase(Locale.ROOT);
    }

    private static File directory() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "coldplay"), "recordings");
    }

    private static JsonStore<Knowledge> store() {
        return new JsonStore<Knowledge>(new File(new File(Minecraft.getMinecraft().mcDataDir, "coldplay"),
                "aclearner.json"), Knowledge.class, Knowledge::new, "ACLearner knowledge");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS").format(new Date());
    }

    // key is "server|packet|field"; field is empty for the packet itself
    private static final class Knowledge {
        HashMap<String, Stat> stats = new HashMap<String, Stat>();
    }

    private static final class Stat {
        long count;
        boolean varied;
        Map<String, Long> seen = new LinkedHashMap<String, Long>(); // value -> times, until varied
        Double min, max;
        String firstSeen, lastSeen;
    }
}
