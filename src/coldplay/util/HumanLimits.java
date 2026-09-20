package coldplay.util;

import java.util.Random;

/**
 * The envelope a hand actually stays inside, and the one place those numbers live.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Shaping a turn so it <em>looks</em> like a hand and keeping it inside what a hand can
 * <em>do</em> are different problems, and only the first one was solved. {@link AimShaper} derives
 * everything from the ceiling it is handed, so it faithfully reproduces the accelerate-and-brake
 * curve of a wrist at four thousand degrees per second. The trajectory passes; the magnitude does
 * not, and magnitude is what an aggregate check reads.
 *
 * <p>This matters more against a model than against a threshold. A hand-written check asks whether
 * one number crossed one line, so staying a hair under it works. A classifier trained on real
 * sessions - the worst players through the best - has a support, and every one of these quantities
 * has a maximum inside it that nobody has ever exceeded. Sitting outside that support is not a
 * borderline call it has to weigh; it is a region where none of its training data lives, and the
 * usual result is a confident positive no amount of smooth interpolation walks back.
 *
 * <p>So these are bounds rather than targets. Nothing here tries to hit a number, and a
 * configuration already inside the envelope is passed through untouched. They exist so that no
 * slider setting, and no bonus another module contributes, can put a value where a human's has
 * never been.
 */
public final class HumanLimits {

    private HumanLimits() {
    }

    /**
     * Fastest single tick a wrist produces, in degrees. Roughly 600 deg/s: a committed flick, well
     * above ordinary tracking, and still an order below what the slider alone permits. Tracking
     * headroom is not the binding concern - a target strafing at full speed one and a half blocks
     * away moves about eleven degrees per tick - so this constrains acquisition, which is exactly
     * where a turn betrays itself.
     */
    public static final double TURN_RATE = 30.0;

    /** Slowest sustained rate worth calling a click stream, in clicks per second. */
    public static final double CPS_MIN = 1.0;

    /**
     * Sustained click ceiling. Bursts above this exist in real logs; a whole fight above it does
     * not, and the aura sustains whatever it is given for as long as a target is in front of it.
     */
    public static final double CPS_MAX = 14.0;

    /**
     * A hand never holds one cadence, so the band it wanders inside is widened to at least this
     * before anything is sampled from it. A pinned band is the failure that survives every other
     * fix here: the interval distribution collapses onto a spike, and interval variance is both
     * the cheapest feature to compute and one of the first any classifier learns.
     */
    public static final double CPS_MIN_SPREAD = 2.0;

    /**
     * Vanilla survival attack reach in blocks. Past this the hit is arithmetic rather than aim, it
     * is recoverable from the packet stream alone without any behavioural modelling, and no amount
     * of rotation work conceals it.
     */
    public static final double REACH = 3.0;

    /** Widest cone a player engages without turning to look first, in degrees. */
    public static final double FOV = 150.0;

    /** Simple-reaction latency before the hand starts moving to something new, in ms. */
    public static final long REACTION_MIN_MS = 130L;
    public static final long REACTION_MAX_MS = 280L;

    /** Caps a per-tick turn ceiling. A slower setting is the user's choice and is left alone. */
    public static double turnRate(double requested) {
        return Math.min(requested, TURN_RATE);
    }

    /** Caps a total attack reach, whatever combination of settings and bonuses produced it. */
    public static double reach(double requested) {
        return Math.min(requested, REACH);
    }

    public static double fov(double requested) {
        return Math.min(requested, FOV);
    }

    /**
     * Puts a CPS band inside the human range and widens it if it is too tight to be a hand.
     * Returns {@code {lo, hi}}.
     *
     * <p>The widening goes downward: a hand that rarely reaches the top of its band is ordinary,
     * one that never varies at all is not, so the requested ceiling is what gets kept.
     */
    public static double[] cps(double lo, double hi) {
        double low = Math.min(lo, hi);
        double high = Math.max(lo, hi);
        high = Math.min(Math.max(high, CPS_MIN), CPS_MAX);
        low = Math.max(CPS_MIN, Math.min(low, high));
        if (high - low < CPS_MIN_SPREAD) {
            low = Math.max(CPS_MIN, high - CPS_MIN_SPREAD);
            high = Math.min(CPS_MAX, low + CPS_MIN_SPREAD);
        }
        return new double[]{low, high};
    }

    /** How long the hand takes to react to something it has not been tracking. */
    public static long reactionMs(Random random) {
        return REACTION_MIN_MS + (long) (random.nextDouble() * (REACTION_MAX_MS - REACTION_MIN_MS));
    }
}
