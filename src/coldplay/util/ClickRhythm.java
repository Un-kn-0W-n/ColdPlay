package coldplay.util;

import java.util.Random;

/**
 * Inter-click intervals with the shape a hand produces. Hold one instance per clicking owner: the
 * state is the hand's tempo, and sharing it across owners would correlate streams that are not.
 *
 * <h3>Why the uniform roll is not enough</h3>
 *
 * <p>{@link CpsDelay} draws each gap independently and uniformly between the two ends of the CPS
 * band. That produces two artefacts, and both are visible in a histogram anyone can build from a
 * packet capture without training anything.
 *
 * <p>The first is the shape. A uniform roll gives a flat histogram with two hard edges and nothing
 * outside them. Real inter-click intervals are right-skewed - a mode, a short floor the hand cannot
 * beat, and a long tail of gaps where attention went elsewhere - which is what you get from a
 * process that is roughly log-normal. A rectangle is not a bad fit to that; it is a different
 * distribution, and the edges alone give it away.
 *
 * <p>The second is the independence. Drawing each gap fresh means neighbouring gaps are
 * uncorrelated, so the stream has no tempo: it jumps from one end of the band to the other and back
 * with equal ease. A hand speeds up and slows down over a fight and carries that speed for many
 * clicks, so consecutive intervals are strongly correlated. The autocorrelation of the gap series
 * is about as cheap to compute as the variance, and white noise where a slow drift belongs is as
 * separable as the flat histogram is.
 *
 * <h3>What this does instead</h3>
 *
 * <p>Sampling happens in log-interval space, which is what makes the result skewed in milliseconds
 * without any special-casing. Two mean-reverting walks are summed there: a slow one for tempo,
 * carrying across many clicks, and a quick one for the click-to-click jitter a hand cannot suppress.
 * Their shares sum to one, so the interval stays inside the band the user asked for. A rare stumble
 * adds a one-sided excursion past the slow end, because the tail of a real gap series is not
 * symmetric and a distribution with no tail at all is its own signature.
 *
 * <p>Randomness comes from the caller's {@link Random} so a seeded owner replays exactly.
 */
public final class ClickRhythm {

    // Tempo: slow and heavily autocorrelated. Stationary sd is about 0.6 of the clamp, so the walk
    // spends most of its time mid-band and reaches an end only occasionally, which is the point -
    // the band's extremes should be visited, not sampled from as often as its middle.
    private static final double TEMPO_PULL = 0.10;
    private static final double TEMPO_STEP = 0.26;
    // Jitter: short memory. Neighbouring clicks correlate a little; clicks far apart do not.
    private static final double JITTER_PULL = 0.60;
    private static final double JITTER_STEP = 0.30;
    // Shares sum to 1.0, so a saturated walk still lands on the band edge rather than past it.
    private static final double TEMPO_SHARE = 0.62;
    private static final double JITTER_SHARE = 0.38;
    // The tail: about one click in fifty runs long, by up to e^0.55 ~ 1.7x the gap it would have had.
    private static final double STUMBLE_CHANCE = 0.02;
    private static final double STUMBLE_MAX = 0.55;

    private final Random random;
    private double tempo, jitter;

    public ClickRhythm(Random random) {
        this.random = random;
    }

    /** Returns the hand to mid-tempo. Draws no randomness. */
    public void reset() {
        tempo = jitter = 0.0;
    }

    /**
     * The next inter-click gap in milliseconds, for a band given in clicks per second. The band is
     * put through {@link HumanLimits#cps} first, so a caller cannot ask for a rate or a tightness
     * that no hand produces.
     */
    public long sample(double minCps, double maxCps) {
        double[] band = HumanLimits.cps(minCps, maxCps);
        double slowest = Math.log(1000.0 / band[0]);
        double fastest = Math.log(1000.0 / band[1]);
        double mid = (slowest + fastest) * 0.5;
        double half = (slowest - fastest) * 0.5;

        tempo = walk(tempo, TEMPO_PULL, TEMPO_STEP);
        jitter = walk(jitter, JITTER_PULL, JITTER_STEP);

        double ln = mid + half * (tempo * TEMPO_SHARE + jitter * JITTER_SHARE);
        if (random.nextDouble() < STUMBLE_CHANCE) {
            ln += STUMBLE_MAX * random.nextDouble();
        }
        return Math.max(1L, Math.round(Math.exp(ln)));
    }

    /** One step of a mean-reverting walk, clamped to the unit interval the shares are scaled to. */
    private double walk(double state, double pull, double step) {
        double next = state - pull * state + step * random.nextGaussian();
        return next < -1.0 ? -1.0 : next > 1.0 ? 1.0 : next;
    }
}
