package coldplay.broker;

import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventRender;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.util.MoveFix;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Silent rotation. Once per tick, before the player moves, turns a spoofed server-side look toward the
 * highest-priority request, rate-limited and snapped to the mouse GCD. That look drives movement physics
 * and the look packet; the third-person head is interpolated between ticks while the camera stays put.
 */
public final class RotationManager {

    private static final RotationManager INSTANCE = new RotationManager();

    public static RotationManager getInstance() {
        return INSTANCE;
    }

    /** Drops the spoof after the server replaced the look (S08); the next request reseeds from the camera. */
    public synchronized void resetAfterServerCorrection(EntityPlayerSP player) {
        if (player != null) {
            serverYaw = prevYaw = sentYaw = player.rotationYaw;
            serverPitch = prevPitch = sentPitch = player.rotationPitch;
            player.coldplayRenderPitch = Float.NaN;
        }
        active = false;
        yawRemainder = pitchRemainder = 0.0;
        reqOwner = null;
        hasRequest = false;
        lastOwner = null;
        lastWinPriority = Integer.MIN_VALUE;
        MoveFix.resetHysteresis();
    }

    private float serverYaw, serverPitch;
    private float prevYaw, prevPitch; // before this tick's step
    private double yawRemainder, pitchRemainder; // unapplied fraction of a GCD step
    private double prevYawRemainder, prevPitchRemainder;
    private float sentYaw, sentPitch; // look on the last movement packet
    private boolean active;

    private Object reqOwner;
    private float reqYaw, reqPitch;
    private int reqPriority;
    private double reqYawRate, reqPitchRate;
    private boolean hasRequest;
    private Object lastOwner;
    private EntityPlayerSP trackedPlayer;
    private net.minecraft.world.World trackedWorld;
    private int lastWinPriority = Integer.MIN_VALUE;
    private RotationManager() {
    }

    @EventTarget(priority = EventPriority.STATE_TRACKING)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (trackedPlayer != mc.thePlayer || trackedWorld != mc.theWorld) {
            resetAfterServerCorrection(mc.thePlayer);
            trackedPlayer = mc.thePlayer;
            trackedWorld = mc.theWorld;
        }
    }

    /**
     * Turns the server-side look toward (yaw, pitch) at up to turnRate degrees per tick. Call every tick
     * from EventUpdate PRE at {@link EventPriority#AIM}; the highest priority wins and the first request
     * holds ties.
     */
    public void request(Object who, float yaw, float pitch, int priority, double turnRate) {
        request(who, yaw, pitch, priority, turnRate, turnRate);
    }

    public synchronized void request(Object who, float yaw, float pitch, int priority,
                                     double yawRate, double pitchRate) {
        if (hasRequest && priority <= reqPriority) {
            return;
        }
        reqOwner = who;
        reqYaw = yaw;
        reqPitch = pitch;
        reqPriority = priority;
        reqYawRate = yawRate;
        reqPitchRate = pitchRate;
        hasRequest = true;
    }

    public float getServerYaw() {
        return serverYaw;
    }

    public float getServerPitch() {
        return serverPitch;
    }

    /** The look the server holds until this tick's movement packet; actions sent before it are judged against it. */
    public float getSentYaw() {
        return sentYaw;
    }

    public float getSentPitch() {
        return sentPitch;
    }

    public net.minecraft.util.Vec3 getSentLookVec() {
        return lookVec(sentYaw, sentPitch);
    }

    public boolean isActive() {
        return active;
    }

    public String packetLogContext() {
        String owner = lastOwner instanceof coldplay.module.Module
                ? ((coldplay.module.Module) lastOwner).getName()
                : lastOwner == null ? "none" : lastOwner.getClass().getSimpleName();
        Minecraft mc = Minecraft.getMinecraft();
        String mode = !active ? "camera" : mc.currentScreen != null || !mc.inGameHasFocus
                ? "gui-hold" : lastOwner == null ? "return-to-camera" : "tracking";
        String state = "rotationMode=" + mode + "; rotationOwner=" + owner;
        return !active ? state : state + "; brokerYaw=" + serverYaw + "; brokerPitch=" + serverPitch
                + "; requestedYaw=" + reqYaw + "; requestedPitch=" + reqPitch
                + "; yawRate=" + reqYawRate + "; pitchRate=" + reqPitchRate;
    }

    public boolean owns(Object who) {
        return isActive() && lastOwner == who;
    }

    public synchronized void cancel(Object who) {
        if (reqOwner == who) {
            reqOwner = null;
            hasRequest = false;
        }
        if (lastOwner == who) {
            lastOwner = null;
            yawRemainder = pitchRemainder = 0.0;
            lastWinPriority = Integer.MIN_VALUE;
            MoveFix.resetHysteresis();
        }
    }

    public boolean isBusyAbove(int priority) {
        return isActive() && lastWinPriority > priority;
    }

    /** Ticks since {@code lastNanos}, capped at 10 like the vanilla timer; 0 when there is no prior frame. */
    public static double elapsedTicksSince(long lastNanos, long nowNanos) {
        return lastNanos == 0L ? 0.0 : Math.min((nowNanos - lastNanos) / 1.0E9 * 20.0, 10.0);
    }

    /** Snaps a rotation delta to a whole multiple of one mouse count at the live sensitivity. */
    public static float gcdSnap(float delta) {
        float gcd = gcdStep();
        return gcd > 0.0F ? Math.round(delta / gcd) * gcd : delta;
    }

    /** One mouse count in degrees at the live sensitivity. */
    public static float gcdStep() {
        float f = Minecraft.getMinecraft().gameSettings.mouseSensitivity * 0.6F + 0.2F;
        return f * f * f * 1.2F; // 1.2 = 8.0 mouse scale * 0.15 in Entity.setAngles
    }

    /** {@code {yaw, pitch}} in degrees from the eye toward a point. */
    public static float[] angleTo(double eyeX, double eyeY, double eyeZ,
                                  double targetX, double targetY, double targetZ) {
        return net.minecraft.util.RotationMath.anglesTo(
                eyeX, eyeY, eyeZ, targetX, targetY, targetZ);
    }

    public static net.minecraft.util.Vec3 lookVec(float yaw, float pitch) {
        return net.minecraft.util.RotationMath.lookVector(pitch, yaw);
    }

    public net.minecraft.util.Vec3 getServerLookVec() {
        return lookVec(serverYaw, serverPitch);
    }

    /**
     * Used by EntityPlayerSP.updateDistance. A body target equal to the real look yaw is redirected
     * to the spoofed yaw.
     */
    public float remapBodyTarget(float bodyTarget, float realYaw) {
        return bodyTarget == realYaw ? serverYaw : bodyTarget;
    }

    /** After every producer has requested for this tick, before the player moves. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onTick(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        tick(mc.thePlayer, mc.currentScreen != null || !mc.inGameHasFocus);
    }

    private synchronized void tick(EntityPlayerSP player, boolean gui) {
        if (player == null || !active && (!hasRequest || gui)) {
            release(player);
            return;
        }
        if (gui) { // vanilla cannot turn under a GUI; hold the wire look until it closes
            prevYaw = serverYaw;
            prevPitch = serverPitch;
            yawRemainder = pitchRemainder = prevYawRemainder = prevPitchRemainder = 0.0;
            hasRequest = false;
            return;
        }
        if (!hasRequest) {
            // Return to the camera at the last rate, floored so a braked owner cannot leave a crawl behind.
            float gcd = gcdStep();
            if (Math.abs(MathHelper.wrapAngleTo180_float(player.rotationYaw - serverYaw)) < gcd
                    && Math.abs(player.rotationPitch - serverPitch) < gcd) {
                release(player);
                return;
            }
            reqOwner = null;
            reqYaw = player.rotationYaw;
            reqPitch = player.rotationPitch;
            reqPriority = Integer.MIN_VALUE;
            reqYawRate = Math.max(reqYawRate, 20.0D);
            reqPitchRate = Math.max(reqPitchRate, 20.0D);
            hasRequest = true;
        }
        step(player);
    }

    /** The head between the last two ticks' looks, like any other entity's rotation. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onRender(EventRender event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || !active) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        float yaw = prevYaw + (serverYaw - prevYaw) * partialTicks;
        player.rotationYawHead = yaw;
        player.prevRotationYawHead = yaw;
        player.coldplayRenderPitch = prevPitch + (serverPitch - prevPitch) * partialTicks;
    }

    /**
     * Re-aims this tick's look packet from EventMotion PRE by redoing the tick's step, so the packet still
     * turns at most one tick's rate. Ignored unless {@code who} won this tick.
     */
    public synchronized void reaim(Object who, float yaw, float pitch, double turnRate) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (!owns(who) || player == null) {
            return;
        }
        serverYaw = prevYaw;
        serverPitch = prevPitch;
        yawRemainder = prevYawRemainder;
        pitchRemainder = prevPitchRemainder;
        reqOwner = who;
        reqYaw = yaw;
        reqPitch = pitch;
        reqYawRate = turnRate;
        reqPitchRate = turnRate;
        reqPriority = lastWinPriority;
        advance(player);
    }


    private void release(EntityPlayerSP player) {
        // Shift the camera by whole turns so the outgoing yaw stays continuous; nothing visible changes.
        if (active && player != null) {
            float turns = Math.round((serverYaw - player.rotationYaw) / 360.0F) * 360.0F;
            if (turns != 0.0F) {
                player.rotationYaw += turns;
                player.prevRotationYaw += turns;
            }
        }
        active = false;
        yawRemainder = pitchRemainder = 0.0;
        hasRequest = false;
        lastOwner = null;
        lastWinPriority = Integer.MIN_VALUE;
        if (player != null) {
            player.coldplayRenderPitch = Float.NaN;
        }
    }

    private void step(EntityPlayerSP player) {
        if (!active || reqOwner != lastOwner) {
            yawRemainder = pitchRemainder = 0.0;
        }
        // Seed from the live camera so the first step is rate-limited from the real look.
        if (!active) {
            serverYaw = player.rotationYaw;
            serverPitch = player.rotationPitch;
            active = true;
        }
        prevYaw = serverYaw;
        prevPitch = serverPitch;
        prevYawRemainder = yawRemainder;
        prevPitchRemainder = pitchRemainder;
        advance(player);
    }

    private void advance(EntityPlayerSP player) {
        double yawDiff = MathHelper.wrapAngleTo180_double(reqYaw - serverYaw - yawRemainder);
        double pitchDiff = MathHelper.clamp_float(reqPitch, -90.0F, 90.0F) - serverPitch - pitchRemainder;
        double yawStep = yawRemainder + MathHelper.clamp_double(yawDiff, -reqYawRate, reqYawRate);
        double pitchStep = pitchRemainder + MathHelper.clamp_double(pitchDiff, -reqPitchRate, reqPitchRate);
        // Seeded from the camera and stepped in GCD multiples, so the spoof stays on the camera's GCD grid.
        float appliedYaw = gcdSnap((float) yawStep);
        float appliedPitch = gcdSnap((float) pitchStep);
        yawRemainder = yawStep - appliedYaw;
        pitchRemainder = pitchStep - appliedPitch;
        serverYaw += appliedYaw;
        serverPitch = MathHelper.clamp_float(serverPitch + appliedPitch, -90.0F, 90.0F);

        // Equal current and previous head yaw avoids interpolation jitter.
        player.rotationYawHead = serverYaw;
        player.prevRotationYawHead = serverYaw;
        player.coldplayRenderPitch = serverPitch;

        if (reqOwner != lastOwner) {
            MoveFix.resetHysteresis(); // new owner, drop the old movement impulse
        }
        lastOwner = reqOwner;
        lastWinPriority = reqPriority;
        hasRequest = false;
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onStrafe(EventStrafe event) {
        if (isActive()) {
            event.setYaw(serverYaw);
        }
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        if (!event.isPre()) {
            return;
        }
        if (isActive()) {
            event.setYaw(serverYaw);
            event.setPitch(serverPitch);
        }
        sentYaw = event.getYaw();
        sentPitch = event.getPitch();
    }
}
