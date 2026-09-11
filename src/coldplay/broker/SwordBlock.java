package coldplay.broker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Holds a sword block on the server while the client never enters item use. While armed, outgoing
 * packets are held for a few ticks and released in bursts shaped like a keyboard blockhit: the
 * first flying packet closes the interval the block was up in, the release follows in the next
 * tick, the held swings and attacks in the tick after that, and a fresh block ends the burst.
 * The server sees the block up for the whole hold in real time, yet only one flying packet per
 * burst is processed as "using an item". That is the single tick the client moves slowed; every
 * other tick runs at full speed without contradicting the server's prediction.
 *
 * <p>A tick's actions start after the transaction replies the client queued for it, so every
 * inserted packet is placed past the replies that follow its flying packet, and a burst waits for
 * the first reply after its last flying packet so the block has one to follow.
 *
 * <p>The cost is latency: everything, hits included, reaches the server up to one hold late.
 *
 * <p>This is the client's one intentional outbound packet-semantic exception; modules themselves
 * never see packets.
 */
public final class SwordBlock {
    private static final SwordBlock INSTANCE = new SwordBlock();
    private static final int MAX_HELD = 64;

    private boolean armed;
    private int blinkTicks;
    private boolean serverBlocking;
    private int heldFlying;
    private int slowedTick = -1;
    private EntityPlayerSP heldPlayer;
    private final ArrayDeque<Packet<?>> held = new ArrayDeque<Packet<?>>();
    private Consumer<Packet<?>> wire = packet -> {
        NetHandlerPlayClient handler = Minecraft.getMinecraft().getNetHandler();
        if (handler != null) {
            handler.getNetworkManager().sendPacket(packet);
        }
    };

    private SwordBlock() {
    }

    public static SwordBlock getInstance() {
        return INSTANCE;
    }

    /** Whether a module wants the block held, and how many ticks of packets each burst spans. */
    public void setArmed(boolean armed, int blinkTicks) {
        this.armed = armed;
        this.blinkTicks = blinkTicks;
    }

    /** Discard stale gameplay after a correction; preserve connection-control replies on the same connection. */
    public void reset(boolean sameConnection) {
        armed = false;
        while (!held.isEmpty()) {
            Packet<?> packet = held.pollFirst();
            if (sameConnection && (packet instanceof C0FPacketConfirmTransaction
                    || packet instanceof net.minecraft.network.play.client.C00PacketKeepAlive
                    || packet instanceof net.minecraft.network.play.client.C01PacketChatMessage
                    || packet instanceof net.minecraft.network.play.client.C17PacketCustomPayload)) {
                wire.accept(packet);
            }
        }
        if (sameConnection && serverBlocking) release();
        serverBlocking = false;
        heldFlying = 0;
        slowedTick = -1;
        heldPlayer = null;
    }

    /** True on the one tick per burst whose flying packet the server processes while blocking. */
    public boolean slowedThisTick() {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        return player != null && player.ticksExisted == slowedTick;
    }

    /** Outbound funnel. Returns true when the packet was taken into the hold. */
    public boolean hold(Packet<?> packet) {
        if (!(armed || serverBlocking) || !mainThread()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        if (player != heldPlayer) {
            // a new player object means a new world: the server dropped our item use with it
            held.clear();
            heldFlying = 0;
            serverBlocking = false;
            heldPlayer = player;
        }
        if (held.size() >= MAX_HELD) {
            burst(player);
        }
        held.addLast(packet);
        if (packet instanceof C03PacketPlayer) {
            heldFlying++;
        }
        int need = armed ? blinkTicks : 1;
        // burst on the first transaction reply after enough flying packets; a further flying
        // packet without any reply (no transactions from this server) bursts instead
        boolean due = packet instanceof C0FPacketConfirmTransaction ? heldFlying >= need
                : packet instanceof C03PacketPlayer && heldFlying > need;
        if (due) {
            burst(player);
        }
        return true;
    }

    private void burst(EntityPlayerSP player) {
        boolean releasing = serverBlocking;
        ArrayDeque<Packet<?>> deferred = new ArrayDeque<Packet<?>>();
        int flying = 0;
        boolean releasePending = false;
        boolean attacksPending = false;
        while (!held.isEmpty()) {
            Packet<?> packet = held.pollFirst();
            if (!(packet instanceof C0FPacketConfirmTransaction)) {
                // a tick's own packets begin here; anything inserted for this tick goes first
                if (releasePending) {
                    release();
                    releasePending = false;
                }
                if (attacksPending) {
                    drain(deferred);
                    attacksPending = false;
                }
            }
            // an attack may not share an interval with the block or with the release
            if (releasing && flying < 2 && (packet instanceof C02PacketUseEntity || packet instanceof C0APacketAnimation)) {
                deferred.addLast(packet);
                continue;
            }
            wire.accept(packet);
            if (releasing && packet instanceof C03PacketPlayer && ++flying <= 2) {
                if (flying == 1) {
                    releasePending = true;
                } else {
                    attacksPending = true;
                }
            }
        }
        if (releasePending) {
            release();
        }
        drain(deferred); // fewer than two flying packets held: shape is lost, nothing is dropped
        heldFlying = 0;
        if (armed) {
            PacketLog.getInstance().tagged("SwordBlock", () ->
                    wire.accept(new C08PacketPlayerBlockPlacement(player == null ? null : player.inventory.getCurrentItem())));
            serverBlocking = true;
            slowedTick = player == null ? -1 : player.ticksExisted + 1;
        }
    }

    private void release() {
        PacketLog.getInstance().tagged("SwordBlock", () -> wire.accept(new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN)));
        serverBlocking = false;
    }

    private void drain(ArrayDeque<Packet<?>> packets) {
        while (!packets.isEmpty()) {
            wire.accept(packets.pollFirst());
        }
    }

    private static boolean mainThread() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.isCallingFromMinecraftThread();
    }
}
