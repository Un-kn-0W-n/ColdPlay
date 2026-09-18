package coldplay.util;

import coldplay.setting.RangeSetting;
import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * Turn shaping for one rotation producer: a correlated, bursty hand instead of a constant slew.
 * Hold one instance per owner, since the state is the hand's and not the broker's.
 */
public final class HandProfile {

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

    /** Aim-point offsets as a fraction of each hitbox dimension. */
    public static final class Wander {
        public final double x, y, z;

        Wander(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final Wander REST = new Wander(0.0, 0.0, 0.0);

    // Drift and gain decay toward rest each tick and take a gaussian kick, so the noise is
    // correlated across ticks like a hand rather than white dither.
    private static final double DRIFT_PULL = 0.18; // about five ticks of memory
    private static final double DRIFT_STEP = 0.035;
    private static final double DRIFT_CLAMP = 0.22;
    // A hand drags the aim point along a line, it does not jitter each axis on its own.
    private static final double DRIFT_SHARE = 0.75; // share^2 + solo^2 = 1 holds the old amplitude
    private static final double DRIFT_SOLO = 0.66;
    private static final double DRIFT_VERTICAL = 1.3; // pitch ends up carrying about half of yaw's travel
    // One multiplicative gain drives both axes, so a fast yaw tick is a fast pitch tick. In log
    // space, which makes it heavy tailed: a real turn is mostly small steps with the odd flick.
    private static final double GAIN_PULL = 0.35;
    private static final double GAIN_STEP = 0.90;
    private static final double GAIN_CLAMP = 1.9; // e^1.9, so about sevenfold either way
    private static final double SOLO_PULL = 0.50; // what is left after the shared part, per axis
    private static final double SOLO_STEP = 0.12;
    private static final double SOLO_CLAMP = 0.80;
    // The hand moves in bursts and rests between them. Run lengths are geometric with these means,
    // measured from the combat windows of a legit recording: about eight ticks moving, four resting.
    private static final double MOVE_CONTINUE = 0.873;
    private static final double HOLD_CONTINUE = 0.85;
    private static final int MOVE_MAX = 200;
    private static final int HOLD_MAX = 40;
    // The turn curve is fixed; the gain is what makes it differ from one turn to the next.
    private static final double ACCEL = 0.40; // fraction of the gap to top speed closed each tick
    private static final double DECEL = 0.25; // starts braking about two and a half ticks out

    private final RangeSetting yawSpeed;
    private final RangeSetting pitchSpeed;
    private final Random random;

    private double pace;
    private double yawBase, pitchBase;
    private double handGain, yawGain, pitchGain;
    private double driftHand, driftX, driftY, driftZ;
    private int holdTicks, moveTicks;

    public HandProfile(RangeSetting yawSpeed, RangeSetting pitchSpeed) {
        this(yawSpeed, pitchSpeed, new Random());
    }

    HandProfile(RangeSetting yawSpeed, RangeSetting pitchSpeed, Random random) {
        this.random = random;
        this.yawSpeed = yawSpeed;
        this.pitchSpeed = pitchSpeed;
    }

    public static double yawError(float want, float from) {
        return Math.abs(MathHelper.wrapAngleTo180_double(want - from));
    }

    public static double pitchError(float want, float from) {
        return Math.abs(want - from);
    }

    public void reset() {
        pace = 0.0;
        yawBase = pitchBase = 0.0;
        handGain = yawGain = pitchGain = 0.0;
        driftHand = driftX = driftY = driftZ = 0.0;
        holdTicks = moveTicks = 0;
    }

    /** A new target is turned to from rest, at its own pace. */
    public void retarget() {
        reset();
        moveTicks = burst(MOVE_CONTINUE, MOVE_MAX);
        pace = random.nextDouble();
    }

    /** Steps the wander walk. A resting hand does not wander either, so the offsets freeze with it. */
    public Wander wander() {
        if (holdTicks <= 0) {
            driftHand = walk(driftHand, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
            driftX = walk(driftX, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
            driftY = walk(driftY, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
            driftZ = walk(driftZ, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
        }
        return new Wander(share(driftX), share(driftY) * DRIFT_VERTICAL, share(driftZ));
    }

    /**
     * One tick of turn shaping. {@code onTarget} says whether freezing here would still be aimed at
     * the goal, since a hand only rests once it is already pointed at something.
     */
    public Step step(float fromYaw, float fromPitch, float wantYaw, float wantPitch, boolean onTarget) {
        if (holdTicks > 0 && !onTarget) {
            holdTicks = 0; // the target walked out from under the frozen ray
        }
        yawBase = ramp(yawBase, paced(yawSpeed), yawError(wantYaw, fromYaw));
        pitchBase = ramp(pitchBase, paced(pitchSpeed), pitchError(wantPitch, fromPitch));
        handGain = walk(handGain, GAIN_PULL, GAIN_STEP, GAIN_CLAMP);
        yawGain = walk(yawGain, SOLO_PULL, SOLO_STEP, SOLO_CLAMP);
        pitchGain = walk(pitchGain, SOLO_PULL, SOLO_STEP, SOLO_CLAMP);

        double yawRate = geared(yawBase, handGain + yawGain);
        double pitchRate = geared(pitchBase, handGain + pitchGain);

        if (holdTicks > 0) {
            // Re-requesting the look the broker already holds steps it by nothing, so the wire
            // look repeats exactly, the way it does while a hand is off the mouse.
            holdTicks--;
            if (holdTicks == 0) {
                moveTicks = burst(MOVE_CONTINUE, MOVE_MAX);
            }
            return new Step(fromYaw, fromPitch, yawRate, pitchRate);
        }
        if (moveTicks > 0) {
            moveTicks--;
        }
        // A rest that is due waits for the ray to be on the target, so it never starts off it.
        if (moveTicks <= 0 && onTarget) {
            holdTicks = burst(HOLD_CONTINUE, HOLD_MAX);
        }
        return new Step(wantYaw, wantPitch, yawRate, pitchRate);
    }

    private double share(double solo) {
        return solo * DRIFT_SOLO + driftHand * DRIFT_SHARE;
    }

    // Read live so a slider edit lands next tick; only the pace is rolled per target.
    private double paced(RangeSetting setting) {
        return setting.getLo() + pace * (setting.getHi() - setting.getLo());
    }

    /** Geometric run length: one tick, plus a coin that keeps landing heads. */
    private int burst(double keep, int cap) {
        int ticks = 1;
        while (ticks < cap && random.nextDouble() < keep) {
            ticks++;
        }
        return ticks;
    }

    // One step of a mean-reverting walk. The pull forgets the past, the gaussian adds the new wander.
    private double walk(double state, double pull, double step, double clamp) {
        return MathHelper.clamp_double(state - pull * state + step * random.nextGaussian(), -clamp, clamp);
    }

    // Eases the rate toward a ceiling that drops near the target, once per tick. There is no floor:
    // a rate under one mouse count snaps to nothing and carries in the broker's remainder, which is
    // how a settled aim produces the single-count corrections and idle ticks a real one does.
    private static double ramp(double rate, double cap, double error) {
        rate = Math.min(rate, cap); // a lowered slider has to bite on this tick
        double goal = Math.min(cap, error / (DECEL * 10.0)); // 100% brakes ten ticks out
        double step = goal > rate ? ACCEL : DECEL;
        return Math.max(rate + (goal - rate) * step, 0.0);
    }

    // The shared hand gain times what is left of this axis, both multiplicative. Overshoot is not a
    // risk: the broker clamps the step to the remaining error, so a spike only closes the gap sooner.
    private static double geared(double rate, double logGain) {
        return rate * Math.exp(logGain);
    }
}
