package coldplay.util;

import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * Turn shaping for one rotation producer: an accelerating, varying, resting hand instead of a
 * constant slew. Hold one instance per owner, since the state is the hand's and not the broker's.
 *
 * <h3>Why this exists</h3>
 *
 * <p>A producer that hands the broker a fixed degrees-per-tick draws a square wave: the look
 * crosses the gap at exactly the cap, lands dead on the aim point, and then reports a turn rate of
 * zero until the target moves. Yaw and pitch also arrive at the same rate, so their ratio is
 * exactly one. Each of those is trivially separable from a recording of a hand, and none of them
 * needs a clever detector to spot.
 *
 * <p>This shapes the same request into something with the statistics of a hand: the rate ramps up
 * and brakes early, wanders tick to tick, carries pitch slower than yaw by a ratio that itself
 * moves, lets the aim point drift around the hitbox instead of pinning the centroid, throws past a
 * distant goal and corrects back onto it, and stops moving entirely for short rests.
 *
 * <h3>The ceiling is honoured, and it is the caller's job to make it human</h3>
 *
 * <p>Everything is derived from the one speed ceiling passed into {@link #step}, and shaped rates
 * only ever sit at or below it. The broker's per-tick rate limit is therefore still exactly the
 * setting the user chose: shaping spends part of the budget, it never borrows against it.
 *
 * <p>That cuts both ways, and it is the limit of what this class can do. Shape is not magnitude:
 * handed a ceiling no wrist could reach, this reproduces the curve of a hand at a speed that is not
 * one, and the curve is the part nobody measures first. A caller that wants the result to survive
 * an aggregate check must clamp the ceiling before passing it - see {@link HumanLimits#turnRate} -
 * because nothing below this line will do it for them.
 *
 * <h3>Per-tick call contract</h3>
 *
 * <p>Call {@link #drift()} once per tick and {@link #step} once per tick, in that order. They are
 * not independent: the rest counter couples them, so while a rest runs the drift freezes and draws
 * no randomness, and {@code step} re-requests the look the broker already holds. Calling them out
 * of order, twice, or only one of them pulls the rest apart from the drift and the result stops
 * reading as a hand.
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

    /** Aim-point offsets as a fraction of each hitbox dimension. Immutable. */
    public static final class Drift {
        public final double x, y, z;

        Drift(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** The centred, motionless drift a reset hand starts from. */
    public static final Drift REST = new Drift(0.0, 0.0, 0.0);

    // The turn curve. A hand leans into a turn harder than it brakes out of one, and it starts
    // braking well before it arrives rather than stopping dead on the mark.
    private static final double ACCEL = 0.34;
    private static final double DECEL = 0.24;
    private static final double BRAKE_TICKS = 4.0;

    // One shared gain drives both axes, so a fast yaw tick is also a fast pitch tick. The walk is
    // mean reverting, so the speed is correlated across ticks like a hand rather than white dither,
    // and it lives in log space so the tail is heavy: mostly small steps with the odd flick.
    private static final double GAIN_PULL = 0.30;
    private static final double GAIN_STEP = 0.26;
    private static final double GAIN_CLAMP = 0.33;
    // The hand cruises below the ceiling. SPEED_MID * e^GAIN_CLAMP stays under 1, so the shaped
    // rate never pins itself to the cap and never draws the flat top that gave the old code away.
    // The band still has to sit high enough that an ordinary strafe is inside it, or the shaping
    // turns into a speed limit and the aim is left behind everything that moves.
    private static final double SPEED_MID = 0.70;
    private static final double SPEED_MIN = 0.42;

    // Pitch is a wrist movement against a physically shorter axis, so it lags yaw. The ratio
    // wanders on its own walk, because a fixed ratio is just as separable as a fixed rate.
    private static final double PITCH_PULL = 0.22;
    private static final double PITCH_STEP = 0.18;
    private static final double PITCH_CLAMP = 0.34;
    private static final double PITCH_MID = 0.58;
    private static final double PITCH_MIN = 0.30;
    private static final double PITCH_MAX = 0.92;

    // Where on the hitbox the aim settles. Without this the look parks on the exact centroid and
    // holds it to the last float, which no hand does.
    private static final double DRIFT_PULL = 0.18; // about five ticks of memory
    private static final double DRIFT_STEP = 0.035;
    // Worst case offset is CLAMP * (SOLO + SHARE) = 0.226 of a hitbox dimension, so even a pinned
    // walk stays in the torso rather than sliding onto the shins, where the reach ray would miss.
    private static final double DRIFT_CLAMP = 0.16;
    // A hand drags the aim point along a line; it does not jitter each axis independently.
    private static final double DRIFT_SHARE = 0.75; // share^2 + solo^2 = 1 keeps the amplitude
    private static final double DRIFT_SOLO = 0.66;
    private static final double DRIFT_VERTICAL = 0.9; // pitch carries a little less than yaw

    // The hand moves in bursts and rests between them: roughly nine ticks moving, five resting.
    // The rest is deliberately short. A pause is a hand detail, but a long one is a lost fight.
    private static final double MOVE_KEEP = 0.89;
    private static final double REST_KEEP = 0.80;
    private static final int MOVE_MAX = 200;
    private static final int REST_MAX = 10;
    // A rest is only for a target that is holding still. Past this share of the ceiling the goal
    // is moving fast enough that pausing would drop the aim behind it, so the hand stays on it.
    private static final double REST_BUSY = 0.12;

    // Overshoot. Braking toward a goal and never passing it makes every approach monotone, and a
    // monotone approach is not what a limb does: an aimed movement is one ballistic throw that
    // lands off the mark plus one or two corrections back onto it. The sign of the final approach
    // is therefore a usable feature all by itself, and without this it is constant.
    //
    // The bias is rolled once per throw from the error at the time, then decays, so it is still
    // present when the look arrives - that is what puts the look past the mark - and gone shortly
    // after. It deliberately survives the arrival by a few ticks rather than tracking the error
    // down to nothing, which would just be a slower brake.
    private static final double OVERSHOOT_GAIN = 0.14;
    private static final double OVERSHOOT_PITCH = 0.6; // the wrist commits less on the short axis
    // Slow enough that a long throw still carries a couple of degrees of bias when the look
    // arrives. Much faster and the overshoot decays to less than one mouse count before it lands,
    // which the broker's quantization then rounds away entirely - a correction nobody can see is
    // not one. Much slower and it stops being a correction and starts being a standing offset.
    private static final double OVERSHOOT_DECAY = 0.12;
    private static final double OVERSHOOT_MAX = 6.0; // degrees, so a long throw cannot fling the aim
    // Small corrections are not thrown, they are placed. Only a gap this wide starts a new throw.
    private static final double THROW_ERROR = 12.0;
    // A decaying bias never reaches zero, and one that lingers stops being a correction and becomes
    // a permanent offset between the aim point and what is asked for. Well under one mouse count,
    // so dropping it here is invisible on the wire and leaves a settled hand asking for the aim
    // point exactly, which is the property the rest of the aim path is entitled to rely on.
    private static final double OVERSHOOT_EPSILON = 0.01;

    private final Random random;

    private double yawRate, pitchRate;
    private double gain, pitchGain;
    private double driftHand, driftX, driftY, driftZ;
    private double biasYaw, biasPitch;
    private boolean throwing;
    private int moveTicks, restTicks;
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

    /** Clears every scrap of hand state. Draws no randomness. */
    public void reset() {
        yawRate = pitchRate = 0.0;
        gain = pitchGain = 0.0;
        driftHand = driftX = driftY = driftZ = 0.0;
        biasYaw = biasPitch = 0.0;
        throwing = false;
        moveTicks = restTicks = 0;
        lastYaw = lastPitch = 0.0F;
        hasLast = false;
    }

    /** A new target is turned to from rest, and gets a fresh burst before its first pause. */
    public void retarget() {
        reset();
        moveTicks = burst(MOVE_KEEP, MOVE_MAX);
    }

    /**
     * Steps the aim-point walk. Call once per tick, before {@link #step}. A resting hand does not
     * wander either, so while a rest runs the offsets freeze and no randomness is drawn.
     */
    public Drift drift() {
        if (restTicks <= 0) {
            driftHand = walk(driftHand, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
            driftX = walk(driftX, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
            driftY = walk(driftY, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
            driftZ = walk(driftZ, DRIFT_PULL, DRIFT_STEP, DRIFT_CLAMP);
        }
        return new Drift(share(driftX), share(driftY) * DRIFT_VERTICAL, share(driftZ));
    }

    /**
     * One tick of turn shaping. Call once per tick, after {@link #drift()}.
     *
     * @param ceiling the owner's speed setting in degrees per tick; the shaped rates stay under it
     * @param onTarget whether freezing here would still be aimed at the goal, since a hand only
     *                 rests once it is already pointed at something
     */
    public Step step(float fromYaw, float fromPitch, float wantYaw, float wantPitch,
                     double ceiling, boolean onTarget) {
        // How far the goal itself travelled since the last tick, which is the target's angular
        // speed. Matching it is what the turn has to do before any of the gap is closed at all.
        double followYaw = hasLast ? yawError(wantYaw, lastYaw) : 0.0;
        double followPitch = hasLast ? pitchError(wantPitch, lastPitch) : 0.0;
        lastYaw = wantYaw;
        lastPitch = wantPitch;
        hasLast = true;

        // A hand does not take its thumb off something that is still moving.
        boolean busy = followYaw + followPitch > ceiling * REST_BUSY;
        if (restTicks > 0 && (!onTarget || busy)) {
            restTicks = 0; // the target walked out from under the frozen ray
            moveTicks = burst(MOVE_KEEP, MOVE_MAX);
        }
        gain = walk(gain, GAIN_PULL, GAIN_STEP, GAIN_CLAMP);
        pitchGain = walk(pitchGain, PITCH_PULL, PITCH_STEP, PITCH_CLAMP);

        double yawCeiling = ceiling * clamp(SPEED_MID * Math.exp(gain), SPEED_MIN, 1.0);
        double pitchCeiling = yawCeiling * clamp(PITCH_MID * Math.exp(pitchGain), PITCH_MIN, PITCH_MAX);

        yawRate = ramp(yawRate, yawCeiling, ceiling, yawError(wantYaw, fromYaw), followYaw);
        pitchRate = ramp(pitchRate, pitchCeiling, ceiling, pitchError(wantPitch, fromPitch), followPitch);

        if (restTicks > 0) {
            restTicks--;
            if (restTicks == 0) {
                moveTicks = burst(MOVE_KEEP, MOVE_MAX);
            }
            // A still hand carries no correction, and letting one survive the pause would pop the
            // look the tick the rest ends.
            biasYaw = biasPitch = 0.0;
            throwing = false;
            // Re-requesting the look the broker already holds steps it by nothing, so the wire look
            // repeats exactly the way it does while a real hand is off the mouse.
            return new Step(fromYaw, fromPitch, yawRate, pitchRate);
        }
        if (moveTicks > 0) {
            moveTicks--;
        }
        // A rest that is due waits for the ray to be on a target that is holding still, so it
        // never starts off one and never starts on one that is about to run out from under it.
        if (moveTicks <= 0 && onTarget && !busy) {
            restTicks = burst(REST_KEEP, REST_MAX);
        }
        overshoot(fromYaw, fromPitch, wantYaw, wantPitch);
        return new Step((float) (wantYaw + biasYaw), (float) (wantPitch + biasPitch),
                yawRate, pitchRate);
    }

    /**
     * Throws the requested look past a distant goal and takes the bias back out over the ticks that
     * follow. Only the requested angle moves: the rates, the rest logic and the caller's own
     * on-target test all still measure against the true aim point, so a throw changes the path the
     * look takes without changing when the hand decides it has arrived.
     */
    private void overshoot(float fromYaw, float fromPitch, float wantYaw, float wantPitch) {
        double signedYaw = MathHelper.wrapAngleTo180_double(wantYaw - fromYaw);
        double signedPitch = wantPitch - fromPitch;
        double error = Math.abs(signedYaw) + Math.abs(signedPitch);
        if (!throwing && error > THROW_ERROR) {
            throwing = true;
            // Rolled per throw rather than fixed: a constant overshoot ratio is its own giveaway.
            double gain = OVERSHOOT_GAIN * random.nextDouble();
            biasYaw = clamp(signedYaw * gain, -OVERSHOOT_MAX, OVERSHOOT_MAX);
            biasPitch = clamp(signedPitch * gain * OVERSHOOT_PITCH, -OVERSHOOT_MAX, OVERSHOOT_MAX);
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

    /** The largest a throw's bias can ever be, in degrees. Exposed so a check can bound it. */
    public static double overshootLimit() {
        return OVERSHOOT_MAX;
    }

    private double share(double solo) {
        return solo * DRIFT_SOLO + driftHand * DRIFT_SHARE;
    }

    /** Geometric run length: one tick, plus a coin that keeps landing heads. */
    private int burst(double keep, int cap) {
        int ticks = 1;
        while (ticks < cap && random.nextDouble() < keep) {
            ticks++;
        }
        return ticks;
    }

    /** One step of a mean-reverting walk: the pull forgets the past, the gaussian adds the wander. */
    private double walk(double state, double pull, double step, double clamp) {
        return clamp(state - pull * state + step * random.nextGaussian(), -clamp, clamp);
    }

    /**
     * Eases a rate toward a ceiling that drops as the error closes, so the turn brakes into its
     * target instead of stopping on it. There is deliberately no floor: a rate under one mouse
     * count snaps to nothing and carries in the broker's remainder, which is how a settled aim
     * produces the single-count corrections and idle ticks a real one does.
     *
     * <p>{@code follow} is the goal's own speed and is added on top of the braking term rather
     * than being braked against. Without it this is a plain proportional controller, which parks
     * at whatever error makes {@code error / BRAKE_TICKS} equal the target's angular speed: a
     * permanent lag of {@link #BRAKE_TICKS} ticks of target motion that no slider setting can
     * close, because the brake and not the ceiling is what binds. Carrying the goal's speed
     * separately drops the settled lag to the one tick that sampling costs, and a target holding
     * still contributes nothing here, so it still gets braked into exactly as before.
     *
     * <p>Note which ceiling bounds which term. {@code shaped} is the hand's cruising band and it
     * bounds the part that closes the gap; only {@code hard}, the user's actual setting, bounds
     * the total. Matching a target's motion is not a stylistic choice a hand gets to make, so it
     * is not shaped: were {@code follow} held under the cruising band too, the fastest target the
     * aim could hold would be the band's average rather than the setting, and anything quicker
     * would walk away permanently however high the slider went.
     */
    private static double ramp(double rate, double shaped, double hard, double error, double follow) {
        rate = Math.min(rate, hard); // a lowered slider has to bite on this tick
        double goal = Math.min(hard, follow + Math.min(shaped, error / BRAKE_TICKS));
        return Math.max(rate + (goal - rate) * (goal > rate ? ACCEL : DECEL), 0.0);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }
}
