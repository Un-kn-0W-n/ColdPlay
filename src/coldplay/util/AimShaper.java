package coldplay.util;

import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * Shapes one producer's turn like a hand on a mouse. While the look sits inside a band around the
 * goal the hand only trembles or holds still, and once it leaves the band a correction throws it
 * back in. The constants were fit by replaying recorded legit PvP fights (Kaggle "Aim Dataset for
 * Minecraft", 2219 players) through this class and matching per-tick speed, acceleration, idle
 * share, pitch travel and yaw/pitch coupling.
 *
 * <p>Randomness comes from the caller's {@link Random} so that a seeded owner replays exactly.
 */
public final class AimShaper {

    /** The look to request this tick and the per-axis rates to request it at. Immutable. */
    public static final class Step {
        public final float yaw, pitch;
        public final double yawRate, pitchRate;

        Step(float yaw, float pitch, double yawRate, double pitchRate) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.yawRate = yawRate;
            this.pitchRate = pitchRate;
        }
    }

    // A hand leans into a correction harder than it brakes out of one.
    private static final double ACCEL = 0.34;
    private static final double DECEL = 0.24;
    private static final double BRAKE_TICKS = 6.0;

    // One shared log-space gain for both axes, so a fast yaw tick is also a fast pitch tick.
    private static final double GAIN_PULL = 0.30;
    private static final double GAIN_STEP = 0.26;
    private static final double GAIN_CLAMP = 0.33;
    private static final double SPEED_MID = 0.70;
    private static final double SPEED_MIN = 0.42;

    // Pitch runs slower than yaw by a ratio that wanders.
    private static final double PITCH_PULL = 0.22;
    private static final double PITCH_STEP = 0.18;
    private static final double PITCH_CLAMP = 0.34;
    private static final double PITCH_MID = 0.58;
    private static final double PITCH_MIN = 0.30;
    private static final double PITCH_MAX = 0.92;
    private static final double PITCH_HARD = 0.7; // pitch never moves more than this share of the ceiling

    // How deep into the band a correction aims: 0 is the edge, 1 the goal. Pitch settles shallow.
    private static final double YAW_DEPTH_MIN = 0.5;
    private static final double PITCH_DEPTH_MIN = 0.1;
    private static final double PITCH_DEPTH_MAX = 0.6;
    private static final double SETTLE = 0.5; // a correction ends inside this share of the band

    // Log-normal speed noise per correction tick. Matching the target's own motion gets less, or the
    // lag on a fast strafe random-walks off the hitbox.
    private static final double JITTER = 0.5;
    private static final double PURSUIT_JITTER = 0.15;
    private static final double TREMOR = 0.6; // degrees
    private static final double TREMOR_FULL = 10.0; // ceiling below which the tremor calms with it
    private static final double TREMOR_PULL = 0.6;
    private static final double TREMOR_PITCH = 0.7;
    private static final double COUPLING = 0.15; // pitch wobble per degree of yaw travel
    private static final double STILL = 0.35; // chance a resting tick starts a still run
    private static final double STILL_KEEP = 0.45;
    private static final int STILL_MAX = 20;

    // Overshoot on long throws, then corrected back.
    private static final double THROW_ERROR = 12.0;
    private static final double OVERSHOOT_GAIN = 0.14;
    private static final double OVERSHOOT_PITCH = 0.6;
    private static final double OVERSHOOT_DECAY = 0.12;
    private static final double OVERSHOOT_MAX = 6.0;
    private static final double OVERSHOOT_EPSILON = 0.01;

    private final Random random;

    private double yawRate, pitchRate;
    private double gain, pitchGain;
    private double tremorYaw, tremorPitch;
    private double biasYaw, biasPitch;
    private double depthYaw, depthPitch;
    private boolean fixYaw, fixPitch, throwing;
    private int still;
    private float lastYaw, lastPitch;
    private boolean hasLast;

    public AimShaper(Random random) {
        this.random = random;
    }

    /** Absolute yaw error across the seam, in degrees. */
    public static double yawError(float want, float from) {
        return Math.abs(MathHelper.wrapAngleTo180_double(want - from));
    }

    /** Absolute pitch error in degrees; pitch does not wrap. */
    public static double pitchError(float want, float from) {
        return Math.abs(want - from);
    }

    /** The largest a throw's bias can ever be, in degrees. */
    public static double overshootLimit() {
        return OVERSHOOT_MAX;
    }

    /** Clears every scrap of hand state. Draws no randomness. */
    public void reset() {
        yawRate = pitchRate = 0.0;
        gain = pitchGain = 0.0;
        tremorYaw = tremorPitch = 0.0;
        biasYaw = biasPitch = 0.0;
        depthYaw = depthPitch = 0.0;
        fixYaw = fixPitch = throwing = false;
        still = 0;
        lastYaw = lastPitch = 0.0F;
        hasLast = false;
    }

    /**
     * One tick of turn shaping. Call once per tick.
     *
     * @param ceiling   the owner's speed setting in degrees per tick; no axis moves faster
     * @param yawBand   half-width in degrees of the region around the goal the hand is content in
     * @param pitchBand the same for pitch
     */
    public Step step(float fromYaw, float fromPitch, float wantYaw, float wantPitch,
                     double ceiling, double yawBand, double pitchBand) {
        // The goal's own motion since last tick; a correction has to match it before it closes anything.
        double followYaw = hasLast ? yawError(wantYaw, lastYaw) : 0.0;
        double followPitch = hasLast ? pitchError(wantPitch, lastPitch) : 0.0;
        lastYaw = wantYaw;
        lastPitch = wantPitch;
        hasLast = true;

        double errorYaw = MathHelper.wrapAngleTo180_double(wantYaw - fromYaw);
        double errorPitch = wantPitch - fromPitch;
        gain = walk(gain, GAIN_PULL, GAIN_STEP, GAIN_CLAMP);
        pitchGain = walk(pitchGain, PITCH_PULL, PITCH_STEP, PITCH_CLAMP);
        if (!fixYaw && Math.abs(errorYaw) > yawBand) {
            fixYaw = true;
            depthYaw = YAW_DEPTH_MIN + (1.0 - YAW_DEPTH_MIN) * random.nextDouble();
        }
        if (!fixPitch && Math.abs(errorPitch) > pitchBand) {
            fixPitch = true;
            depthPitch = PITCH_DEPTH_MIN + (PITCH_DEPTH_MAX - PITCH_DEPTH_MIN) * random.nextDouble();
        }
        double yawCeiling = ceiling * clamp(SPEED_MID * Math.exp(gain), SPEED_MIN, 1.0);
        double pitchCeiling = yawCeiling * clamp(PITCH_MID * Math.exp(pitchGain), PITCH_MIN, PITCH_MAX);
        double gapYaw = fixYaw ? outside(errorYaw, yawBand * (1.0 - depthYaw)) : 0.0;
        double gapPitch = fixPitch ? outside(errorPitch, pitchBand * (1.0 - depthPitch)) : 0.0;
        yawRate = fixYaw ? ramp(yawRate, yawCeiling, ceiling, Math.abs(gapYaw), gapYaw != 0.0 ? followYaw : 0.0) : 0.0;
        pitchRate = fixPitch ? ramp(pitchRate, pitchCeiling, ceiling, Math.abs(gapPitch), gapPitch != 0.0 ? followPitch : 0.0) : 0.0;
        if (fixYaw && (gapYaw == 0.0 || Math.abs(errorYaw) < yawBand * SETTLE)) {
            fixYaw = false;
        }
        if (fixPitch && (gapPitch == 0.0 || Math.abs(errorPitch) < pitchBand * SETTLE)) {
            fixPitch = false;
        }
        overshoot(gapYaw, gapPitch);

        double moveYaw, movePitch;
        if (yawRate > 0.0 || pitchRate > 0.0) {
            double noise = random.nextGaussian();
            moveYaw = jitter(clamp(gapYaw + biasYaw, -yawRate, yawRate), followYaw, noise);
            movePitch = jitter(clamp(gapPitch + biasPitch, -pitchRate, pitchRate), followPitch, noise * 0.5);
            still = 0;
        } else {
            biasYaw = biasPitch = 0.0;
            throwing = false;
            if (still > 0 || random.nextDouble() < STILL) {
                still = still > 0 ? still - 1 : burst();
                return new Step(fromYaw, fromPitch, 0.0, 0.0);
            }
            moveYaw = movePitch = 0.0;
        }
        double shared = random.nextGaussian();
        double tremor = TREMOR * Math.min(1.0, ceiling / TREMOR_FULL);
        tremorYaw += -TREMOR_PULL * tremorYaw + tremor * (0.7 * shared + 0.7 * random.nextGaussian());
        tremorPitch += -TREMOR_PULL * tremorPitch + tremor * TREMOR_PITCH * random.nextGaussian();
        moveYaw = clamp(moveYaw + tremorYaw, -ceiling, ceiling);
        // A hand sweeping sideways does not hold its height to the pixel.
        movePitch += tremorPitch + COUPLING * Math.abs(moveYaw) * random.nextGaussian();
        movePitch = clamp(movePitch, -ceiling * PITCH_HARD, ceiling * PITCH_HARD);
        return new Step((float) (fromYaw + moveYaw), (float) (fromPitch + movePitch),
                Math.abs(moveYaw), Math.abs(movePitch));
    }

    private void overshoot(double gapYaw, double gapPitch) {
        double error = Math.abs(gapYaw) + Math.abs(gapPitch);
        if (!throwing && error > THROW_ERROR) {
            throwing = true;
            double g = OVERSHOOT_GAIN * random.nextDouble();
            biasYaw = clamp(gapYaw * g, -OVERSHOOT_MAX, OVERSHOOT_MAX);
            biasPitch = clamp(gapPitch * g * OVERSHOOT_PITCH, -OVERSHOOT_MAX, OVERSHOOT_MAX);
        } else if (throwing && error < THROW_ERROR * 0.5) {
            throwing = false;
        }
        biasYaw -= biasYaw * OVERSHOOT_DECAY;
        biasPitch -= biasPitch * OVERSHOOT_DECAY;
        if (Math.abs(biasYaw) < OVERSHOOT_EPSILON) {
            biasYaw = 0.0;
        }
        if (Math.abs(biasPitch) < OVERSHOOT_EPSILON) {
            biasPitch = 0.0;
        }
    }

    private static double jitter(double move, double follow, double noise) {
        double pursuit = Math.signum(move) * Math.min(Math.abs(move), follow);
        return pursuit * Math.exp(PURSUIT_JITTER * noise) + (move - pursuit) * Math.exp(JITTER * noise);
    }

    /** How far {@code error} reaches past {@code keep}, signed; zero when it is inside. */
    private static double outside(double error, double keep) {
        return Math.abs(error) > keep ? error - Math.signum(error) * keep : 0.0;
    }

    /** Geometric run length: one tick, plus a coin that keeps landing heads. */
    private int burst() {
        int ticks = 1;
        while (ticks < STILL_MAX && random.nextDouble() < STILL_KEEP) {
            ticks++;
        }
        return ticks;
    }

    private double walk(double state, double pull, double step, double clamp) {
        return clamp(state - pull * state + step * random.nextGaussian(), -clamp, clamp);
    }

    /**
     * Eases a rate toward the goal's own speed plus a share of the gap, so a correction keeps up
     * with a moving target and brakes into a still one. No floor: a rate under one mouse count
     * snaps to nothing in the broker and carries in its remainder.
     */
    private static double ramp(double rate, double shaped, double hard, double error, double follow) {
        rate = Math.min(rate, hard);
        double goal = Math.min(hard, follow + Math.min(shaped, error / BRAKE_TICKS));
        return Math.max(rate + (goal - rate) * (goal > rate ? ACCEL : DECEL), 0.0);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }
}
