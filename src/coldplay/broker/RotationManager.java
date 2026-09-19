package coldplay.broker;

import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventRender;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;
public final class RotationManager {

    private static final RotationManager INSTANCE = new RotationManager();

    /** Degrees per tick used to ease back to the camera once nobody is aiming. */
    private static final double RETURN_RATE = 20.0D;

    public static RotationManager getInstance() {
        return INSTANCE;
    }

    private enum State {
        /** No spoof; the wire carries the camera. */
        OFF,
        /** An owner is aiming. */
        TRACKING,
        /** A screen is open, so the wire look is frozen where it stood. */
        HOLDING,
        /** Nobody is aiming; easing the spoof back onto the camera before handing over. */
        RETURNING
    }

    /** A spoofed look together with the sub-GCD carry that produced it. Immutable. */
    static final class Look {
        final float yaw;
        final float pitch;
        final double yawCarry;
        final double pitchCarry;

        Look(float yaw, float pitch, double yawCarry, double pitchCarry) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.yawCarry = yawCarry;
            this.pitchCarry = pitchCarry;
        }
    }

    /** One producer's bid for a tick. Immutable. */
    static final class Request {
        final Object owner;
        final float yaw;
        final float pitch;
        final int priority;
        final double yawRate;
        final double pitchRate;

        Request(Object owner, float yaw, float pitch, int priority, double yawRate, double pitchRate) {
            this.owner = owner;
            this.yaw = yaw;
            this.pitch = pitch;
            this.priority = priority;
            this.yawRate = yawRate;
            this.pitchRate = pitchRate;
        }
    }

    private State state = State.OFF;
    /** The spoofed look on the wire right now. */
    private Look current = new Look(0.0F, 0.0F, 0.0, 0.0);
    /** The look this tick started from: the render interpolation source and the reaim replay base. */
    private Look tickStart = current;
    /** This tick's winning bid, consumed exactly once by {@link #runTick}. */
    private Request pending;
    /** The bid {@link #current} was produced from, or null when nobody owns the spoof. */
    private Request committed;
    private float sentYaw, sentPitch; // look on the last movement packet
    private EntityPlayerSP trackedPlayer;
    private net.minecraft.world.World trackedWorld;

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

    /** Drops the spoof after the server replaced the look (S08); the next request reseeds from the camera. */
    public void resetAfterServerCorrection(EntityPlayerSP player) {
        if (player == null) {
            reset(current.yaw, current.pitch);
            return;
        }
        sentYaw = player.rotationYaw;
        sentPitch = player.rotationPitch;
        player.coldplayRenderPitch = Float.NaN;
        reset(player.rotationYaw, player.rotationPitch);
    }

    /**
     * Turns the server-side look toward (yaw, pitch) at up to turnRate degrees per tick. Call every tick
     * from EventUpdate PRE at {@link EventPriority#AIM}; the highest priority wins and the first request
     * holds ties.
     */
    public void request(Object who, float yaw, float pitch, int priority, double turnRate) {
        request(who, yaw, pitch, priority, turnRate, turnRate);
    }

    public void request(Object who, float yaw, float pitch, int priority,
                        double yawRate, double pitchRate) {
        if (pending != null && priority <= pending.priority) {
            return;
        }
        pending = new Request(who, yaw, pitch, priority, yawRate, pitchRate);
    }

    public float getServerYaw() {
        return current.yaw;
    }

    public float getServerPitch() {
        return current.pitch;
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
        return state != State.OFF;
    }

    public String packetLogContext() {
        Object owner = committed == null ? null : committed.owner;
        String name = owner instanceof coldplay.module.Module
                ? ((coldplay.module.Module) owner).getName()
                : owner == null ? "none" : owner.getClass().getSimpleName();
        String mode = state == State.OFF ? "camera"
                : state == State.HOLDING ? "gui-hold"
                : state == State.RETURNING ? "return-to-camera" : "tracking";
        String context = "rotationMode=" + mode + "; rotationOwner=" + name;
        if (state == State.OFF) {
            return context;
        }
        context += "; brokerYaw=" + current.yaw + "; brokerPitch=" + current.pitch;
        return committed == null ? context
                : context + "; requestedYaw=" + committed.yaw + "; requestedPitch=" + committed.pitch
                        + "; yawRate=" + committed.yawRate + "; pitchRate=" + committed.pitchRate;
    }

    /** True only while {@code who} is actually driving the spoof; false under a GUI hold or a return. */
    public boolean owns(Object who) {
        return committed != null && committed.owner == who;
    }

    public void cancel(Object who) {
        if (pending != null && pending.owner == who) {
            pending = null;
        }
        if (committed != null && committed.owner == who) {
            committed = null;
            // The spoof outlives the owner that placed it: it stands where it is until the next
            // tick eases it back onto the camera, so that is what the broker is now doing.
            if (state == State.TRACKING) {
                state = State.RETURNING;
            }
        }
    }

    public boolean isBusyAbove(int priority) {
        return committed != null && committed.priority > priority;
    }

    /** Ticks since {@code lastNanos}, capped at 10 like the vanilla timer; 0 when there is no prior frame. */
    public static double elapsedTicksSince(long lastNanos, long nowNanos) {
        return lastNanos == 0L ? 0.0 : Math.min((nowNanos - lastNanos) / 1.0E9 * 20.0, 10.0);
    }

    /** Snaps a rotation delta to a whole multiple of one mouse count at the live sensitivity. */
    public static float gcdSnap(float delta) {
        return snap(delta, gcdStep());
    }

    private static float snap(float delta, float gcd) {
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
        return lookVec(current.yaw, current.pitch);
    }

    /**
     * Used by EntityPlayerSP.updateDistance. A body target equal to the real look yaw is redirected
     * to the spoofed yaw.
     */
    public float remapBodyTarget(float bodyTarget, float realYaw) {
        return bodyTarget == realYaw ? current.yaw : bodyTarget;
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

    private void tick(EntityPlayerSP player, boolean gui) {
        if (player == null) {
            pending = null;
            reset(current.yaw, current.pitch);
            return;
        }
        float turns = runTick(player.rotationYaw, player.rotationPitch, gui, gcdStep());
        if (turns != 0.0F) {
            // The spoof and the camera both accumulate unbounded, so at handoff they can sit a whole
            // number of turns apart. Shifting the camera is invisible (rendering is mod 360) and keeps
            // the outgoing yaw continuous; re-branching the spoof would inject a 360 deg packet jump.
            player.rotationYaw += turns;
            player.prevRotationYaw += turns;
        }
        if (state == State.OFF) {
            player.coldplayRenderPitch = Float.NaN;
        } else if (state != State.HOLDING) {
            applyRenderLook(player);
        }
    }

    /**
     * One whole tick of the state machine, expressed without the player so it can be exercised
     * headlessly. Returns the whole-turn camera shift the caller must apply on handoff.
     */
    float runTick(float cameraYaw, float cameraPitch, boolean gui, float gcd) {
        Request request = pending; // consumed exactly once, here
        pending = null;
        tickStart = current;

        if (gui) { // vanilla cannot turn under a screen, so the wire look freezes where it stands
            if (state != State.OFF) {
                state = State.HOLDING;
                committed = null;
            }
            return 0.0F;
        }
        if (request == null) {
            if (state == State.OFF) {
                return 0.0F;
            }
            if (Math.abs(MathHelper.wrapAngleTo180_float(cameraYaw - current.yaw)) < gcd
                    && Math.abs(cameraPitch - current.pitch) < gcd) {
                float turns = Math.round((current.yaw - cameraYaw) / 360.0F) * 360.0F;
                reset(cameraYaw + turns, cameraPitch);
                return turns;
            }
            state = State.RETURNING;
            committed = null;
            current = step(tickStart, cameraYaw, cameraPitch, RETURN_RATE, RETURN_RATE, gcd);
            return 0.0F;
        }
        if (state == State.OFF) {
            // Seed from the live camera so the first step is rate-limited from the real look.
            tickStart = current = new Look(cameraYaw, cameraPitch, 0.0, 0.0);
        } else if (committed == null || committed.owner != request.owner) {
            // A carry belongs to the owner that produced it; a new owner starts on the grid.
            tickStart = current = new Look(current.yaw, current.pitch, 0.0, 0.0);
        }
        state = State.TRACKING;
        committed = request;
        current = step(tickStart, request.yaw, request.pitch, request.yawRate, request.pitchRate, gcd);
        return 0.0F;
    }

    /**
     * One tick of turning {@code from} toward (yaw, pitch), rate limited per axis and snapped to whole
     * mouse counts. Pure: the GCD is a parameter rather than a live settings read, so this is safe to
     * evaluate speculatively and can be exercised without a client.
     */
    static Look step(Look from, float yaw, float pitch, double yawRate, double pitchRate, float gcd) {
        double yawDiff = MathHelper.wrapAngleTo180_double(yaw - from.yaw - from.yawCarry);
        double pitchDiff = MathHelper.clamp_float(pitch, -90.0F, 90.0F) - from.pitch - from.pitchCarry;
        double yawStep = from.yawCarry + MathHelper.clamp_double(yawDiff, -yawRate, yawRate);
        double pitchStep = from.pitchCarry + MathHelper.clamp_double(pitchDiff, -pitchRate, pitchRate);
        // Seeded from the camera and stepped in GCD multiples, so the spoof stays on the camera's grid.
        float appliedYaw = snap((float) yawStep, gcd);
        float appliedPitch = snap((float) pitchStep, gcd);
        float newPitch = MathHelper.clamp_float(from.pitch + appliedPitch, -90.0F, 90.0F);
        // The pitch carry counts what was actually applied, so it cannot wind up against the clamp.
        return new Look(from.yaw + appliedYaw, newPitch,
                yawStep - appliedYaw, pitchStep - (newPitch - from.pitch));
    }

    /** Clears every scrap of spoof state at once, so no carry can outlive a reset. */
    private void reset(float yaw, float pitch) {
        state = State.OFF;
        committed = null;
        pending = null;
        current = tickStart = new Look(yaw, pitch, 0.0, 0.0);
    }

    /** Equal current and previous head yaw avoids interpolation jitter. */
    private void applyRenderLook(EntityPlayerSP player) {
        player.rotationYawHead = current.yaw;
        player.prevRotationYawHead = current.yaw;
        player.coldplayRenderPitch = current.pitch;
    }

    /** The head between the last two ticks' looks, like any other entity's rotation. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onRender(EventRender event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || state == State.OFF) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        float yaw = tickStart.yaw + (current.yaw - tickStart.yaw) * partialTicks;
        player.rotationYawHead = yaw;
        player.prevRotationYawHead = yaw;
        player.coldplayRenderPitch = tickStart.pitch + (current.pitch - tickStart.pitch) * partialTicks;
    }

    /**
     * Re-aims this tick's look packet from EventMotion PRE by re-deriving the tick's step from the look
     * it started at, so the packet still turns at most one tick's rate however often this is called.
     * Ignored unless {@code who} won this tick.
     */
    public void reaim(Object who, float yaw, float pitch, double turnRate) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        if (runReaim(who, yaw, pitch, turnRate, gcdStep())) {
            applyRenderLook(player);
        }
    }

    /** {@link #reaim} without the player, so the replay can be exercised headlessly. */
    boolean runReaim(Object who, float yaw, float pitch, double turnRate, float gcd) {
        if (!owns(who)) {
            return false;
        }
        current = step(tickStart, yaw, pitch, turnRate, turnRate, gcd);
        committed = new Request(who, yaw, pitch, committed.priority, turnRate, turnRate);
        return true;
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onStrafe(EventStrafe event) {
        if (isActive()) {
            event.setYaw(current.yaw);
        }
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        if (!event.isPre()) {
            return;
        }
        if (isActive()) {
            event.setYaw(current.yaw);
            event.setPitch(current.pitch);
        }
        sentYaw = event.getYaw();
        sentPitch = event.getPitch();
    }
}
