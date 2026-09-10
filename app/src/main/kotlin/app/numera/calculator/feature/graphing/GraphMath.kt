package app.numera.calculator.feature.graphing

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The visible window, in world coordinates.
 *
 * Pure data with pure transformations so the whole camera can be unit-tested without a
 * Canvas — panning and zooming are where an off-by-one turns into a graph that drifts.
 */
data class Viewport(
    val minX: Double = -10.0,
    val maxX: Double = 10.0,
    val minY: Double = -10.0,
    val maxY: Double = 10.0,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY

    fun worldToScreenX(x: Double, pixelWidth: Float): Float =
        (((x - minX) / width) * pixelWidth).toFloat()

    /** Y is flipped: screen coordinates grow downward and the world's grow upward. */
    fun worldToScreenY(y: Double, pixelHeight: Float): Float =
        ((1.0 - (y - minY) / height) * pixelHeight).toFloat()

    fun screenToWorldX(px: Float, pixelWidth: Float): Double =
        minX + (px / pixelWidth) * width

    fun screenToWorldY(py: Float, pixelHeight: Float): Double =
        minY + (1.0 - py / pixelHeight) * height

    /** Drags the window by a pixel delta, converted to world units. */
    fun pan(dxPixels: Float, dyPixels: Float, pixelWidth: Float, pixelHeight: Float): Viewport {
        val dx = (dxPixels / pixelWidth) * width
        val dy = (dyPixels / pixelHeight) * height
        return copy(minX = minX - dx, maxX = maxX - dx, minY = minY + dy, maxY = maxY + dy)
    }

    /**
     * Scales about a fixed world point, so a pinch keeps the pixel under the fingers still.
     *
     * Zooming about the centre instead is the common shortcut and it feels wrong: the thing
     * the user is pinching slides away from under them.
     */
    fun zoom(factorX: Double, factorY: Double, aboutX: Double, aboutY: Double): Viewport {
        val requestedWidth = width / factorX
        val requestedHeight = height / factorY
        // A factor of zero, of infinity or of NaN is not a gesture; scaling by it would put a
        // non-finite span into the window and every world-to-screen conversion after it.
        if (!requestedWidth.isFinite() || !requestedHeight.isFinite()) return this
        if (requestedWidth <= 0.0 || requestedHeight <= 0.0) return this
        // Both axes are clamped by one common ratio. Coercing each span on its own let the
        // axis that had not yet reached the limit carry on shrinking after the other had
        // stopped, so a pinch that runs into MIN_SPAN silently un-squares the window that
        // "Square the axes" had just made square — a plot that was round at the start of the
        // pinch is visibly squashed by the end, with nothing on screen to say why.
        val grow = max(MIN_SPAN / requestedWidth, MIN_SPAN / requestedHeight).coerceAtLeast(1.0)
        val shrink = min(MAX_SPAN / requestedWidth, MAX_SPAN / requestedHeight).coerceAtMost(1.0)
        // The two can only pull against each other when the window's own aspect ratio is
        // wider than the whole allowed range, which no gesture can reach. Keeping the window
        // out of the degenerate end matters more than its shape, so the lower bound wins and
        // the coercions below stay as the backstop.
        val scale = if (grow > 1.0) grow else shrink
        val newWidth = (requestedWidth * scale).coerceIn(MIN_SPAN, MAX_SPAN)
        val newHeight = (requestedHeight * scale).coerceIn(MIN_SPAN, MAX_SPAN)
        val ratioX = (aboutX - minX) / width
        val ratioY = (aboutY - minY) / height
        val newMinX = aboutX - ratioX * newWidth
        val newMinY = aboutY - ratioY * newHeight
        return Viewport(newMinX, newMinX + newWidth, newMinY, newMinY + newHeight)
    }

    /** Makes one world unit the same number of pixels on both axes. */
    fun squared(pixelWidth: Float, pixelHeight: Float): Viewport {
        if (pixelWidth <= 0f || pixelHeight <= 0f) return this
        // Clamped like a zoom is. Squaring a window that is already at a zoom limit asks for a
        // height outside the range the two constants exist to hold — a landscape canvas
        // scales the width *down* — and every later gesture then starts from a window the
        // clamps had already rejected.
        val targetHeight = (width * (pixelHeight / pixelWidth)).coerceIn(MIN_SPAN, MAX_SPAN)
        val centreY = (minY + maxY) / 2
        return copy(minY = centreY - targetHeight / 2, maxY = centreY + targetHeight / 2)
    }

    companion object {
        /** Bounds that stop a pinch from collapsing the window to zero or to infinity. */
        const val MIN_SPAN = 1e-9
        const val MAX_SPAN = 1e12
    }
}

/** Chooses gridline spacing that produces round numbers rather than 0.30000000000000004. */
object AxisTicks {

    /**
     * The largest "nice" step (1, 2 or 5 times a power of ten) giving at most [maxTicks].
     *
     * Snapping to that sequence is what keeps labels readable at every zoom level; a plain
     * `span / n` produces values no one wants to read off an axis.
     */
    fun step(span: Double, maxTicks: Int = 10): Double {
        if (span <= 0.0 || !span.isFinite()) return 1.0
        val rough = span / maxTicks
        val magnitude = 10.0.pow(floor(log10(rough)))
        val normalised = rough / magnitude
        val nice = when {
            normalised <= 1.0 -> 1.0
            normalised <= 2.0 -> 2.0
            normalised <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    /**
     * Tick positions covering `[min, max]`, aligned to multiples of the step.
     *
     * Each tick is computed as `index * step` rather than by accumulating `t += step`.
     * Accumulation silently stops advancing once `min / step` exceeds 2^53 — a window far
     * from the origin and narrow enough that the step falls under the ulp of the coordinates
     * — and the old loop then ran to its guard and returned a thousand copies of the same
     * value, every one of which the grid drew as an overlapping line. Here the same
     * degeneracy shows up as a repeated coordinate and ends the list instead.
     */
    fun ticks(min: Double, max: Double, maxTicks: Int = 10): List<Double> {
        if (!min.isFinite() || !max.isFinite() || max <= min) return emptyList()
        val step = step(max - min, maxTicks)
        val firstIndex = floor(min / step)
        if (!firstIndex.isFinite()) return emptyList()
        val result = ArrayList<Double>()
        // A "nice" step is never smaller than span / maxTicks, so the run cannot be longer
        // than this; the bound exists so a pathological step cannot spin the draw thread.
        val limit = maxTicks + 2
        var previous = Double.NaN
        for (index in 0..limit) {
            val t = (firstIndex + index) * step
            if (!t.isFinite() || t > max) break
            // The step has been absorbed by the magnitude of the coordinate: the window is
            // narrower than one ulp of where it sits, and no further tick is representable.
            if (t == previous) break
            if (t >= min) result += t
            previous = t
        }
        return result
    }
}

/**
 * One sampled column of the plot. `y` is NaN where the function has no value there.
 *
 * `continuous[i]` records that the segment arriving at column `i` from column `i - 1` was
 * found, at sampling time, to be a steep run of the function rather than a pole between the
 * two samples — see [GraphSampler.sample]. Only segments whose jump would break under
 * [GraphSampler.breaksBetween] are ever examined, so `false` means "not examined, or not
 * shown to be continuous", never "a pole for certain".
 *
 * Annotated rather than converted to a `data class`: the arrays are filled once in
 * [GraphSampler.sample] and never written to again, but Compose cannot see that on its own
 * and infers the type — and with it [Plot], the plot list and every composable holding one —
 * unstable, which makes the whole graphing screen non-skippable during a pan.
 */
@Immutable
class Samples(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val continuous: BooleanArray = BooleanArray(xs.size),
)

object GraphSampler {

    /**
     * Evaluates [f] once per horizontal pixel, plus [margin] whole windows to either side.
     *
     * Deliberately the double-precision compiled closure rather than the exact engine: a
     * thousand exact evaluations per frame is orders of magnitude too slow, and a pixel is
     * only ever going to be at an integer position anyway.
     *
     * The margin is what a pan reveals. Samples are left alone while a finger is down and
     * the old path is redrawn under the new window, which is right for the part of the
     * curve already sampled — but with the samples covering the window exactly, every pixel
     * a drag uncovered on its leading edge had nothing to draw, and a slow one-screen drag
     * ended with the plot filling only the half of the canvas it started in. The grid inside
     * the window is not changed by the margin: column `margin * columns` is `minX` exactly
     * and the last visible column is `maxX` exactly, which [RootFinder] relies on.
     *
     * Wherever two adjacent samples jump by more than [breaksBetween] allows, [f] is
     * evaluated once more, halfway between them, to tell a pole from a steep line. The two
     * are indistinguishable from the endpoints alone — the per-column jump of `1000x` is
     * twice the default window's height — and breaking on the jump alone drew that line as
     * two isolated dots at the top and bottom edges. Either side of a pole the function
     * diverges, so the midpoint value lies *beyond* one of the two samples; along a steep
     * run it lies between them. That is one extra evaluation per candidate segment, and no
     * candidate at all along an ordinary curve.
     */
    fun sample(f: (Double) -> Double, viewport: Viewport, columns: Int, margin: Int = 0): Samples {
        val count = columns.coerceAtLeast(2)
        val windows = 1 + 2 * margin.coerceAtLeast(0)
        val offset = margin.coerceAtLeast(0) * count
        val total = count * windows
        val xs = DoubleArray(total)
        val ys = DoubleArray(total)
        val step = viewport.width / (count - 1)
        for (i in 0 until total) {
            val x = viewport.minX + (i - offset) * step
            xs[i] = x
            ys[i] = evaluate(f, x)
        }
        val continuous = BooleanArray(total)
        for (i in 1 until total) {
            val previous = ys[i - 1]
            val current = ys[i]
            if (previous.isNaN() || current.isNaN()) continue
            if (!breaksBetween(previous, current, viewport)) continue
            val between = evaluate(f, (xs[i - 1] + xs[i]) / 2)
            continuous[i] = !between.isNaN() &&
                between >= min(previous, current) && between <= max(previous, current)
        }
        return Samples(xs, ys, continuous)
    }

    /**
     * The columns of [samples] inside [viewport], for the readouts that say "in view".
     *
     * The margin exists for the canvas alone. Handed the whole array, the root finder counts
     * roots the user cannot see against its cap — `sin(100x)` fills it before the scan ever
     * reaches the window — and a line captioned "in view" listed roots two screens away.
     */
    fun visible(samples: Samples, viewport: Viewport): Samples {
        val xs = samples.xs
        var first = 0
        while (first < xs.size && xs[first] < viewport.minX) first++
        var end = xs.size
        while (end > first && xs[end - 1] > viewport.maxX) end--
        if (first == 0 && end == xs.size) return samples
        return Samples(
            xs.copyOfRange(first, end),
            samples.ys.copyOfRange(first, end),
            samples.continuous.copyOfRange(first, end),
        )
    }

    /** One sample: NaN wherever the function has no finite value. */
    private fun evaluate(f: (Double) -> Double, x: Double): Double = try {
        val y = f(x)
        if (y.isFinite()) y else Double.NaN
    } catch (e: ArithmeticException) {
        Double.NaN
    }

    /**
     * Whether the line should be broken between two adjacent samples.
     *
     * Without this, `1/x` gets a vertical line straight through the asymptote at zero and
     * `tan(x)` gets one at every multiple of π/2 — the classic graphing-calculator artefact,
     * caused by joining a large positive sample to a large negative one.
     */
    fun breaksBetween(previous: Double, current: Double, viewport: Viewport): Boolean {
        if (previous.isNaN() || current.isNaN()) return true
        // The threshold has to be a fraction of the height, not the whole height: at exactly
        // one height the test still admits a segment that spans the screen top to bottom,
        // which is precisely the artefact it exists to remove. One pinch out from the default
        // view the jump either side of a pole lands just under a full height, so the old
        // test drew a solid line through the asymptote of 1/x at a wide band of zoom levels.
        return abs(current - previous) > viewport.height * BREAK_FRACTION
    }

    /**
     * How much of the visible height one joined segment may cover.
     *
     * Half is the usual choice. On its own it is a trade — a real function that climbs more
     * than half a screen inside one pixel column is broken too, and a line steep enough to
     * do that in *every* column is not a line but a scatter of dots — which is why a jump
     * over this fraction is only a candidate, settled by the midpoint check in [sample].
     */
    private const val BREAK_FRACTION = 0.5
}

/** Finds where a sampled curve crosses zero. */
object RootFinder {

    /**
     * Roots of [f] within the sampled range, at most [limit] of them.
     *
     * Sign changes in the samples locate the brackets; bisection then refines each one.
     * Bisection rather than Newton because it cannot diverge, and a graph is exactly where a
     * user will hand the solver a function whose derivative misbehaves.
     *
     * A sign change on its own is not evidence of a root: `1/x` and `tan(x)` change sign at
     * every pole without ever reaching zero, and bisection converges happily onto the pole.
     * Every bracket is therefore checked *after* it has been refined, by asking whether |f|
     * actually fell away at the crossing. Judging the endpoints against a fixed magnitude
     * instead cannot work at any zoom, because the two samples either side of a simple pole
     * are only about 2(columns-1)/width in size — around a hundred in the default window —
     * while a perfectly ordinary steep line such as 1e9x has enormous endpoints and a real
     * root at the origin.
     *
     * A sign change is also not the only evidence of one. A double root — `x^2`, the first
     * thing anyone plots — never changes sign, so it was found only when a column landed on
     * it exactly, which the default window does at almost no device width; the readout said
     * "No roots in view" under a parabola visibly resting on the axis. Those are located as a
     * column where |f| is smaller than at both neighbours with all three on the same side of
     * zero, and refined by [tangentRoot]. A root on the edge of the function's domain —
     * `√x` at zero — has no bracket either, because the sample on the far side is NaN; a
     * finite column next to a NaN one is refined toward the boundary by [edgeRoot].
     *
     * Only the first plot on the screen is ever passed here; there is no reporting of where
     * two plotted curves meet.
     */
    fun roots(f: (Double) -> Double, samples: Samples, limit: Int = MAX_ROOTS): List<Double> {
        val found = ArrayList<Double>()
        val xs = samples.xs
        val ys = samples.ys
        val count = xs.size
        // Deduplicated against the previous root only, which is enough because every
        // candidate below is visited in x order: once the window is narrower than one ulp
        // of where it sits, every column samples the same double, and reporting the same
        // root a hundred times over reads as a hundred different roots.
        fun report(root: Double) {
            if (found.size < limit && found.lastOrNull() != root) found += root
        }
        // Every column is visited, the last one included. Stopping one short tested each
        // sample for zero except the final one, and the sign-change test below cannot stand
        // in for it — `y0 > 0 != y1 > 0` needs y0 positive when y1 is zero — so a curve
        // arriving at zero from below at the right edge produced no bracket at all. In the
        // reset window the last sample is exactly 10.0 on every device width, so x^2-100
        // deterministically reported its left root and not its right one, while the plot
        // visibly crossed the axis at both.
        for (i in 0 until count) {
            if (found.size >= limit) break
            val y0 = ys[i]
            if (y0.isNaN()) continue
            if (y0 == 0.0) {
                report(xs[i])
                continue
            }
            if (i > 0) {
                val yPrevious = ys[i - 1]
                if (yPrevious.isNaN()) {
                    edgeRoot(f, undefinedX = xs[i - 1], definedX = xs[i], sample = y0)
                        ?.let { report(it) }
                } else if (i < count - 1) {
                    val yNext = ys[i + 1]
                    val sameSide = !yNext.isNaN() &&
                        (yPrevious > 0) == (y0 > 0) && (yNext > 0) == (y0 > 0)
                    // Ties go to the left column, so a minimum that two columns share is
                    // refined once, not once for each of them.
                    if (sameSide && abs(y0) < abs(yPrevious) && abs(y0) <= abs(yNext)) {
                        val neighbours = min(abs(yPrevious), abs(yNext))
                        tangentRoot(f, xs[i - 1], xs[i + 1], neighbours)?.let { report(it) }
                    }
                }
            }
            if (i == count - 1) break
            val y1 = ys[i + 1]
            if (y1.isNaN()) {
                edgeRoot(f, undefinedX = xs[i + 1], definedX = xs[i], sample = y0)
                    ?.let { report(it) }
                continue
            }
            // A zero sitting at the far end of the bracket is reported at its own column, on
            // the next iteration or by the zero branch above. Refining a bracket that already
            // has an endpoint *at* zero would report the same crossing twice: once bisected
            // to within an ulp of the column and once exactly on it, which prints as the same
            // number listed twice.
            if (y1 == 0.0) continue
            if (y0 > 0 != y1 > 0) {
                val refined = bisect(f, xs[i], xs[i + 1]) ?: continue
                if (isCrossing(f, refined, y0, y1)) report(refined.root)
            }
        }
        return found
    }

    /**
     * Where |f| bottoms out between [low] and [high], if it bottoms out at zero.
     *
     * A ternary search on |f|, which is unimodal across a single touch of the axis. The
     * search is judged the way [isCrossing] judges a bracket, against the samples rather
     * than against a constant: a curve that merely passes close to the axis has a minimum a
     * fixed fraction of its [neighbours] — `x²+1` bottoms out at 1 between samples of about
     * 1.0001 — while a genuine tangency falls by as many orders of magnitude as the search
     * has resolution, to a ~1e-30 residual for `(x−1)²`. The acceptance fraction is what a
     * near miss must be under to be reported as a touch: at the default zoom a parabola
     * whose vertex sits within about 1e-8 of the axis is, which is far below anything the
     * canvas can show and where double precision has stopped being able to tell the two
     * curves apart in any case.
     *
     * A near miss is abandoned early, so that a function with hundreds of local minima in
     * view — `sin(100x)+2` — costs a few dozen evaluations per minimum rather than a few
     * hundred: once the interval has shrunk by [GIVE_UP_FRACTION] without the residual
     * clearing the threshold, a touch would have been recognised long before, so it is not
     * one. A touch, once recognised, is still refined to the resolution of `Double`: stopping
     * at the threshold left `(x−1)²` reported at 1.00009, which prints as 1.0001 under a
     * curve resting on the axis at exactly 1.
     */
    private fun tangentRoot(
        f: (Double) -> Double,
        low: Double,
        high: Double,
        neighbours: Double,
    ): Double? {
        val threshold = neighbours * TANGENT_RESIDUAL_FRACTION
        if (!threshold.isFinite() || threshold <= 0.0) return null
        val span = high - low
        var a = low
        var b = high
        var best = Double.NaN
        var fBest = Double.POSITIVE_INFINITY
        var iterations = 0
        while (iterations < MAX_ITERATIONS) {
            val m1 = a + (b - a) / 3
            val m2 = b - (b - a) / 3
            // The thirds have rounded onto the ends: nothing narrower is representable.
            if (m1 <= a || m2 >= b || m1 >= m2) break
            val f1 = abs(f(m1))
            val f2 = abs(f(m2))
            if (!f1.isFinite() || !f2.isFinite()) return null
            if (f1 < fBest) {
                fBest = f1
                best = m1
            }
            if (f2 < fBest) {
                fBest = f2
                best = m2
            }
            if (fBest == 0.0) return best
            if (fBest >= threshold && b - a < span * GIVE_UP_FRACTION) return null
            if (f1 < f2) b = m2 else a = m1
            iterations++
        }
        return if (fBest < threshold) best else null
    }

    /**
     * The boundary of the function's domain between a NaN column and a finite one, if the
     * function reaches zero there.
     *
     * Bisection on whether `f` has a value rather than on its sign. The refined boundary is
     * a root only when |f| has fallen away toward it relative to the finite [sample] beside
     * it: `√x` goes to zero at its boundary and is reported, `ln(x)` goes to −∞ at the same
     * boundary and is not, and `√x + 1` arrives at 1 and is not either.
     */
    private fun edgeRoot(
        f: (Double) -> Double,
        undefinedX: Double,
        definedX: Double,
        sample: Double,
    ): Double? {
        var undefined = undefinedX
        var defined = definedX
        var iterations = 0
        while (iterations < MAX_ITERATIONS) {
            val mid = (undefined + defined) / 2
            if (mid == undefined || mid == defined) break
            val fMid = f(mid)
            if (fMid.isFinite()) defined = mid else undefined = mid
            iterations++
        }
        val residual = abs(f(defined))
        if (!residual.isFinite()) return null
        return if (residual < abs(sample) * TANGENT_RESIDUAL_FRACTION) defined else null
    }

    /**
     * Whether the refined root is a crossing rather than a pole bisection walked into.
     *
     * Around a root |f| falls away as the bracket narrows; around a pole it grows without
     * bound, so the converged point of a pole carries a value orders of magnitude *larger*
     * than the samples that bracketed it. Measuring against those samples rather than against
     * a constant is what makes the test hold at every scale of function.
     *
     * The test needs bisection to have actually narrowed something. When the two bracketing
     * columns are already adjacent doubles there is nothing to narrow, the residual is simply
     * the value at one of those two samples, and demanding that it have fallen by half throws
     * a real crossing away: at the tightest zoom the app allows, around x = 1e6, the sample
     * step is far under one ulp and a genuine root of x²-K rejects with a residual of exactly
     * half its own bracket. A sign change between two adjacent doubles is as much of a
     * crossing as this arithmetic can describe, so it is taken as one. That admits a pole
     * whose two neighbouring doubles happen to be sampled — but a pole there already passes
     * the residual test whenever the smaller of its two endpoints is under half the larger,
     * so nothing new is let through, and no other information is left to tell them apart.
     */
    private fun isCrossing(
        f: (Double) -> Double,
        refined: Refined,
        y0: Double,
        y1: Double,
    ): Boolean {
        val residual = abs(f(refined.root))
        if (!residual.isFinite()) return false
        if (residual == 0.0) return true
        if (refined.atResolution) return true
        val bracketScale = max(abs(y0), abs(y1))
        return residual < bracketScale * MAX_RESIDUAL_FRACTION
    }

    /**
     * Where bisection stopped, and whether it was handed a bracket it could not narrow.
     *
     * [atResolution] is the case [isCrossing] cannot judge on the residual, and it is
     * deliberately *not* set when bisection narrowed the bracket first and only then ran out
     * of doubles — which is the ordinary way a run of bisection ends.
     */
    private class Refined(val root: Double, val atResolution: Boolean)

    /**
     * Halves the bracket until it cannot be halved again.
     *
     * Convergence stops at the resolution of `Double` rather than at an absolute tolerance.
     * An absolute one is wrong at both ends of the zoom range: at maximum zoom the bracket is
     * already narrower than it, so bisection would return the untouched midpoint and
     * [isCrossing] would then throw a real root away, and at maximum zoom-out it can never be
     * reached and every iteration of the cap is spent.
     */
    private fun bisect(f: (Double) -> Double, lowStart: Double, highStart: Double): Refined? {
        var low = lowStart
        var high = highStart
        var fLow = f(low)
        if (!fLow.isFinite()) return null
        var narrowed = false
        repeat(MAX_ITERATIONS) {
            val mid = (low + high) / 2
            // The ends are adjacent doubles: halving again would return this same midpoint
            // for ever. Whether anything was narrowed on the way here is what tells a
            // converged root from a bracket that arrived already collapsed.
            if (mid <= low || mid >= high) return Refined(mid, atResolution = !narrowed)
            val fMid = f(mid)
            if (!fMid.isFinite()) return null
            if (fMid == 0.0) return Refined(mid, atResolution = false)
            narrowed = true
            if ((fMid > 0) == (fLow > 0)) {
                low = mid
                fLow = fMid
            } else {
                high = mid
            }
        }
        return Refined((low + high) / 2, atResolution = false)
    }

    private const val MAX_ITERATIONS = 200

    /**
     * How far |f| must have fallen at the refined root, relative to the bracketing samples.
     *
     * Deliberately loose. A genuine crossing lands many orders of magnitude below this and a
     * pole many orders above, so the only things sitting near the line are jump
     * discontinuities, which have no root and belong on the rejected side.
     */
    private const val MAX_RESIDUAL_FRACTION = 0.5

    /**
     * How far |f| must have fallen at a touch or a domain edge, relative to the sample beside
     * it, for the point to count as a root rather than as a near miss — see [tangentRoot].
     */
    private const val TANGENT_RESIDUAL_FRACTION = 1e-4

    /**
     * The fraction of its starting width at which [tangentRoot] gives up on a candidate.
     *
     * A touch shrinks the residual quadratically with the interval, so it is recognised
     * once the interval is under about a hundredth of its start; a near miss keeps its
     * residual however far the search narrows.
     */
    private const val GIVE_UP_FRACTION = 1e-7

    /**
     * A cap on how many roots one pass reports.
     *
     * Both the bisection work and the readout are bounded by it: `sin(100x)` has hundreds of
     * roots in the default window and an identically zero function nominally has one per
     * sampled column.
     */
    const val MAX_ROOTS = 100
}
