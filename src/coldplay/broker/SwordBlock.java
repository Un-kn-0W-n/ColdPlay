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
 * Keeps a sword block up on the server while the client never enters item use. Outgoing packets are
 * held for a few ticks and released in bursts shaped like a keyboard blockhit, so only one flying
 * packet per burst is processed as "using an item". Everything reaches the server up to one hold late.
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

    public void setArmed(boolean armed, int blinkTicks) {
        this.armed = armed;
        this.blinkTicks = blinkTicks;
    }

    /** Disarms. A hold that never burst is sent now, since only a burst releases it; otherwise the next burst does. */
    public void disarm() {
        armed = false;
        if (!serverBlocking) {
            drain(held);
            heldFlying = 0;
        }
    }

    /** Drops held packets; with sameConnection, protocol replies are still sent. */
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
        if (sameConnection && serverBlocking) {
            release();
        }
        serverBlocking = false;
        heldFlying = 0;
        slowedTick = -1;
        heldPlayer = null;
    }

    /** True on the one tick per burst the server processes as blocking. */
    public boolean slowedThisTick() {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        return player != null && player.ticksExisted == slowedTick;
    }

    /** Returns true when the packet was taken into the hold. */
    public boolean hold(Packet<?> packet) {
        if (!(armed || serverBlocking) || !mainThread()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        if (player != heldPlayer) {
            // a new player object means a new world; the server dropped our item use
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
        // Burst on the first transaction reply after enough flying packets, or on the next flying
        // packet when the server sends no transactions.
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
        drain(deferred); // fewer than two flying packets held; nothing is dropped
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
