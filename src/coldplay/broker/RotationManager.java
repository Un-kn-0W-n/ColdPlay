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
 * Rate-limits and mouse-GCD-snaps silent rotation for outgoing look packets, movement physics and
 * the third-person model. The first-person camera stays unchanged except for invisible whole-turn
 * shifts on release. Highest priority wins each frame, with the first requester retaining ties.
 * Without requests, rotation returns to the camera at the last requested rate, floored so a braked
 * owner cannot strand the wire look on a crawl, before releasing.
 */
public final class RotationManager {

    private static final RotationManager INSTANCE = new RotationManager();

    public static RotationManager getInstance() {
        return INSTANCE;
    }

    /**
     * Producer-side look history sampled from {@link EventMotion} PRE after rewriting. This does not
     * include correction acknowledgements or final buffer order; combat dispatch validation uses
     * {@link PlayerPacketState}. NaN prevents a cold start from tripping a delta gate.
     */
    public static final class SentLook {

        private float yaw = Float.NaN, pitch = Float.NaN;
        private float prevYaw = Float.NaN, prevPitch = Float.NaN;

        public void reset() {
            yaw = pitch = prevYaw = prevPitch = Float.NaN;
        }

        public void record(float sentYaw, float sentPitch) {
            prevYaw = yaw;
            prevPitch = pitch;
            yaw = sentYaw;
            pitch = sentPitch;
        }

        public boolean changedTo(float nextYaw, float nextPitch) {
            return !(nextYaw == yaw && nextPitch == pitch);
        }

        /** Wrapped yaw delta between the two sends: the rotation change a place gets judged against. */
        public float yawDelta() {
            return Math.abs(MathHelper.wrapAngleTo180_float(yaw - prevYaw));
        }

        public net.minecraft.util.Vec3 lookVec() {
            return RotationManager.lookVec(yaw, pitch);
        }
    }

    /**
     * The server just replaced the wire position/look with an S08 acknowledgement. Drop the old
     * spoof owner so no module can act on angles the server no longer has, then seed cleanly from
     * the corrected camera on the next requested frame.
     */
    public synchronized void resetAfterServerCorrection(EntityPlayerSP player) {
        if (player != null) {
            serverYaw = player.rotationYaw;
            serverPitch = player.rotationPitch;
            player.coldplayRenderPitch = Float.NaN;
        }
        active = false;
        lastFrameNanos = 0L;
        yawRemainder = pitchRemainder = 0.0;
        reqOwner = null;
        hasRequest = false;
        lastOwner = null;
        lastWinPriority = Integer.MIN_VALUE;
        MoveFix.resetHysteresis();
    }

    /** The spoofed look we send + render; read by the tick handlers and by consumers' raytrace gates. */
    private float serverYaw, serverPitch;
    /** Unsent fractions of a mouse count; retain these so slow rates still work at high FPS. */
    private double yawRemainder, pitchRemainder;
    /** False until a frame seeds the spoof from the live camera, the cold-start / release gate. */
    private boolean active;
    /** Wall-clock stamp of the previous frame, for framerate-independent turn speed. 0 = no prior frame. */
    private long lastFrameNanos;

    // The pending request for the current frame; highest priority wins, consumed when the frame steps.
    private Object reqOwner;
    private float reqYaw, reqPitch;
    private int reqPriority;
    private double reqYawRate, reqPitchRate;
    private boolean hasRequest;
    /** Winner of the last consumed frame, lets a low-priority requester see it lost the wire. */
    private Object lastOwner;
    private EntityPlayerSP trackedPlayer;
    private net.minecraft.world.World trackedWorld;
    /** Winning priority of the last consumed frame, feeds {@link #isBusyAbove} (the bottom-tier stand-down gate). */
    private int lastWinPriority = Integer.MIN_VALUE;
    private RotationManager() {
    }

    @EventTarget(priority = EventPriority.STATE_TRACKING)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        if (trackedPlayer != mc.thePlayer || trackedWorld != mc.theWorld) {
            resetAfterServerCorrection(mc.thePlayer);
            trackedPlayer = mc.thePlayer;
            trackedWorld = mc.theWorld;
        }
    }

    /**
     * Ask the manager to turn the server-side look toward {@code (yaw, pitch)} this frame at up to
     * {@code turnRate} degrees/tick on both axes. Call once per frame while you want the spoof; stop
     * calling to release it. The strictly-highest {@code priority} this frame wins (first request holds on ties).
     */
    public void request(Object who, float yaw, float pitch, int priority, double turnRate) {
        request(who, yaw, pitch, priority, turnRate, turnRate);
    }

    /** As {@link #request(Object, float, float, int, double)}, with pitch capped by its own rate. */
    public synchronized void request(Object who, float yaw, float pitch, int priority,
                                     double yawRate, double pitchRate) {
        if (hasRequest && priority <= reqPriority) {
            return; // a higher- or equal-priority request already owns this frame
        }
        reqOwner = who;
        reqYaw = yaw;
        reqPitch = pitch;
        reqPriority = priority;
        reqYawRate = yawRate;
        reqPitchRate = pitchRate;
        hasRequest = true;
    }

    /** The current spoofed server-side yaw (what the look packet / MoveFix use). Meaningful while {@link #isActive()}. */
    public float getServerYaw() {
        return serverYaw;
    }

    public float getServerPitch() {
        return serverPitch;
    }

    /** True while a spoof is live (seeded + a request this frame), the gate a consumer's raytrace uses. */
    public boolean isActive() {
        return active;
    }

    /** Observed broker state at packet preparation; camera mode does not imply human input. */
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

    /**
     * Whether {@code who} won the last frame. Consumers must suspend actions that depend on their
     * rotation when another requester owns it.
     */
    public boolean owns(Object who) {
        return isActive() && lastOwner == who;
    }

    /** Withdraw one producer while preserving another producer and the normal return ramp. */
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

    /**
     * Reads the last consumed frame's priority, so an Update PRE consumer can lag live requests
     * by one frame.
     */
    public boolean isBusyAbove(int priority) {
        return isActive() && lastWinPriority > priority;
    }

    /**
     * Elapsed ticks, capped at 10 like the vanilla timer so a stalled frame cannot fling the look.
     * Returns 0 without a prior frame.
     */
    public static double elapsedTicksSince(long lastNanos, long nowNanos) {
        return lastNanos == 0L ? 0.0 : Math.min((nowNanos - lastNanos) / 1.0E9 * 20.0, 10.0);
    }

    /**
     * Snap a rotation delta to a whole multiple of the mouse-movement quantum at the current live
     * sensitivity, {@code gcd = f^3 * 1.2}, {@code f = sensitivity*0.6+0.2}, 1.2 = 8.0 (EntityRenderer
     * mouse scale) * 0.15 (Entity.setAngles). Deltas built only from GCD multiples stay on the real
     * camera's GCD grid.
     */
    public static float gcdSnap(float delta) {
        float gcd = gcdStep();
        return gcd > 0.0F ? Math.round(delta / gcd) * gcd : delta;
    }

    /** One mouse count in degrees at the live sensitivity. */
    public static float gcdStep() {
        float f = Minecraft.getMinecraft().gameSettings.mouseSensitivity * 0.6F + 0.2F;
        return f * f * f * 1.2F;
    }

    /**
     * {@code {yaw, pitch}} in degrees from the eye position toward a world point.
     */
    public static float[] angleTo(double eyeX, double eyeY, double eyeZ,
                                  double targetX, double targetY, double targetZ) {
        return net.minecraft.util.RotationMath.anglesTo(
                eyeX, eyeY, eyeZ, targetX, targetY, targetZ);
    }

    /**
     * Accepts yaw before pitch, unlike {@code RotationMath.lookVector}.
     */
    public static net.minecraft.util.Vec3 lookVec(float yaw, float pitch) {
        return net.minecraft.util.RotationMath.lookVector(pitch, yaw);
    }

    /**
     * Look vector for the current spoofed {@code serverYaw/serverPitch}, what a raytrace along the
     * sent look must use. Meaningful while {@link #isActive()}.
     */
    public net.minecraft.util.Vec3 getServerLookVec() {
        return lookVec(serverYaw, serverPitch);
    }

    /**
     * Body-yaw target remap for the third-person model, used by the {@code EntityPlayerSP.updateDistance}
     * override while the spoof is active: vanilla's swing branch targets the body at the LOOK yaw, under
     * a silent rotation the rendered look is {@code serverYaw}, so retarget that branch (recognised by
     * {@code bodyTarget == realYaw}) onto it; movement-direction targets pass through untouched.
     */
    public float remapBodyTarget(float bodyTarget, float realYaw) {
        return bodyTarget == realYaw ? serverYaw : bodyTarget;
    }

    /** Per frame (after producers, low priority): step the spoof and drive the third-person model. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        boolean gui = mc.currentScreen != null || !mc.inGameHasFocus;
        if (player == null || !active && (!hasRequest || gui)) {
            release(player);
            return;
        }
        if (gui) { // vanilla cannot turn under a GUI: hold the wire where it is, ramp once it closes
            lastFrameNanos = 0L;
            yawRemainder = pitchRemainder = 0.0;
            hasRequest = false;
            return;
        }
        if (!hasRequest) {
            // Return at the last rate, floored: a decelerated owner leaves a one-mouse-count crawl
            // behind that would strand the wire look (and MoveFix) on it for seconds.
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

        // fps-independent step: deg/tick * elapsed ticks; a sparse frame turns proportionally further.
        long now = System.nanoTime();
        double ticks = elapsedTicksSince(lastFrameNanos, now);
        lastFrameNanos = now;
        step(player, ticks);
    }

    /**
     * Motion PRE, after physics and before the C03 goes out: the frame owner re-aims this tick's
     * packet from where the player actually ended up, stepped with a whole tick's budget. Ignored
     * unless {@code who} won the last frame, so it can never take the wire from a higher bidder.
     */
    public synchronized void reaim(Object who, float yaw, float pitch, double turnRate) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (!owns(who) || player == null) {
            return;
        }
        reqOwner = who;
        reqYaw = yaw;
        reqPitch = pitch;
        reqYawRate = turnRate;
        reqPitchRate = turnRate;
        reqPriority = lastWinPriority;
        hasRequest = true;
        step(player, 1.0D);
    }


    private void release(EntityPlayerSP player) {
        // Vanilla yaw is unbounded. Shift current and previous camera yaw by equal whole turns
        // to keep outgoing yaw continuous on release without changing rendering or physics.
        if (active && player != null) {
            float turns = Math.round((serverYaw - player.rotationYaw) / 360.0F) * 360.0F;
            if (turns != 0.0F) {
                player.rotationYaw += turns;
                player.prevRotationYaw += turns;
            }
        }
        active = false;
        lastFrameNanos = 0L;
        yawRemainder = pitchRemainder = 0.0;
        hasRequest = false;
        lastOwner = null;
        lastWinPriority = Integer.MIN_VALUE;
        if (player != null) {
            player.coldplayRenderPitch = Float.NaN;
        }
    }

    private void step(EntityPlayerSP player, double ticks) {
        if (!active || reqOwner != lastOwner) {
            yawRemainder = pitchRemainder = 0.0;
        }
        // Cold-start: seed the spoof from the live camera so the first step is rate-limited from the real look, not a jump.
        if (!active) {
            serverYaw = player.rotationYaw;
            serverPitch = player.rotationPitch;
            active = true;
        }
        double maxYaw = reqYawRate * ticks;
        double maxPitch = reqPitchRate * ticks;

        double yawDiff = MathHelper.wrapAngleTo180_double(reqYaw - serverYaw - yawRemainder);
        double pitchDiff = MathHelper.clamp_float(reqPitch, -90.0F, 90.0F) - serverPitch - pitchRemainder;
        double yawStep = yawRemainder + MathHelper.clamp_double(yawDiff, -maxYaw, maxYaw);
        double pitchStep = pitchRemainder + MathHelper.clamp_double(pitchDiff, -maxPitch, maxPitch);
        // GCD spoofing: seeded from rotationYaw + only GCD-multiple steps => we stay on the live camera's GCD grid.
        float appliedYaw = gcdSnap((float) yawStep);
        float appliedPitch = gcdSnap((float) pitchStep);
        yawRemainder = yawStep - appliedYaw;
        pitchRemainder = pitchStep - appliedPitch;
        serverYaw += appliedYaw;
        serverPitch = MathHelper.clamp_float(serverPitch + appliedPitch, -90.0F, 90.0F);

        // Equal current/previous head yaw prevents interpolation jitter. Vanilla keeps control of
        // body yaw, remapped through EntityPlayerSP.updateDistance so strafing still renders correctly.
        player.rotationYawHead = serverYaw;
        player.prevRotationYawHead = serverYaw;
        player.coldplayRenderPitch = serverPitch;

        if (reqOwner != lastOwner) {
            MoveFix.resetHysteresis(); // a new owner must not inherit the previous movement impulse
        }
        lastOwner = reqOwner;
        lastWinPriority = reqPriority;
        hasRequest = false;
    }

    /** Per tick (no phase): run the movement physics on serverYaw -> activates MoveFix. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onStrafe(EventStrafe event) {
        if (isActive()) {
            event.setYaw(serverYaw);
        }
    }

    /** Per tick PRE: make the outgoing C03 carry the spoofed look. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        if (event.isPre() && isActive()) {
            event.setYaw(serverYaw);
            event.setPitch(serverPitch);
        }
    }
}
