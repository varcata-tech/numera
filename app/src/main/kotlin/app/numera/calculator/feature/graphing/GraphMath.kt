package app.numera.calculator.feature.graphing

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
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
        val newWidth = (width / factorX).coerceIn(MIN_SPAN, MAX_SPAN)
        val newHeight = (height / factorY).coerceIn(MIN_SPAN, MAX_SPAN)
        val ratioX = (aboutX - minX) / width
        val ratioY = (aboutY - minY) / height
        val newMinX = aboutX - ratioX * newWidth
        val newMinY = aboutY - ratioY * newHeight
        return Viewport(newMinX, newMinX + newWidth, newMinY, newMinY + newHeight)
    }

    /** Makes one world unit the same number of pixels on both axes. */
    fun squared(pixelWidth: Float, pixelHeight: Float): Viewport {
        if (pixelWidth <= 0f || pixelHeight <= 0f) return this
        val targetHeight = width * (pixelHeight / pixelWidth)
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

/** One sampled column of the plot. `y` is NaN where the function has no value there. */
class Samples(val xs: DoubleArray, val ys: DoubleArray)

object GraphSampler {

    /**
     * Evaluates [f] once per horizontal pixel.
     *
     * Deliberately the double-precision compiled closure rather than the exact engine: a
     * thousand exact evaluations per frame is orders of magnitude too slow, and a pixel is
     * only ever going to be at an integer position anyway.
     */
    fun sample(f: (Double) -> Double, viewport: Viewport, columns: Int): Samples {
        val count = columns.coerceAtLeast(2)
        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        val step = viewport.width / (count - 1)
        for (i in 0 until count) {
            val x = viewport.minX + i * step
            xs[i] = x
            ys[i] = try {
                val y = f(x)
                if (y.isFinite()) y else Double.NaN
            } catch (e: ArithmeticException) {
                Double.NaN
            }
        }
        return Samples(xs, ys)
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
     * Half is the usual choice, and it is a genuine trade: a real function that steps by more
     * than half a screen inside one pixel column is broken too. A gap of one column in a
     * near-vertical line is far less misleading than a false asymptote line.
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
     */
    fun roots(f: (Double) -> Double, samples: Samples, limit: Int = MAX_ROOTS): List<Double> {
        val found = ArrayList<Double>()
        for (i in 0 until samples.xs.size - 1) {
            if (found.size >= limit) break
            val y0 = samples.ys[i]
            val y1 = samples.ys[i + 1]
            if (y0.isNaN() || y1.isNaN()) continue
            if (y0 == 0.0) {
                // Once the window is narrower than one ulp of where it sits, every column
                // samples the same double, and reporting the same root a hundred times over
                // reads as a hundred different roots.
                if (found.lastOrNull() != samples.xs[i]) found += samples.xs[i]
                continue
            }
            if (y0 > 0 != y1 > 0) {
                val root = bisect(f, samples.xs[i], samples.xs[i + 1]) ?: continue
                if (isCrossing(f, root, y0, y1) && found.lastOrNull() != root) found += root
            }
        }
        return found
    }

    /**
     * Whether the refined [root] is a crossing rather than a pole bisection walked into.
     *
     * Around a root |f| falls away as the bracket narrows; around a pole it grows without
     * bound, so the converged point of a pole carries a value orders of magnitude *larger*
     * than the samples that bracketed it. Measuring against those samples rather than against
     * a constant is what makes the test hold at every zoom and for every scale of function.
     */
    private fun isCrossing(
        f: (Double) -> Double,
        root: Double,
        y0: Double,
        y1: Double,
    ): Boolean {
        val residual = abs(f(root))
        if (!residual.isFinite()) return false
        if (residual == 0.0) return true
        val bracketScale = max(abs(y0), abs(y1))
        return residual < bracketScale * MAX_RESIDUAL_FRACTION
    }

    /** Where two functions meet, found as the roots of their difference. */
    fun intersections(
        f: (Double) -> Double,
        g: (Double) -> Double,
        viewport: Viewport,
        columns: Int,
    ): List<Double> {
        val difference = { x: Double -> f(x) - g(x) }
        return roots(difference, GraphSampler.sample(difference, viewport, columns))
    }

    /**
     * Halves the bracket until it cannot be halved again.
     *
     * Convergence stops at the resolution of `Double` rather than at an absolute tolerance.
     * An absolute one is wrong at both ends of the zoom range: at maximum zoom the bracket is
     * already narrower than it, so bisection would return the untouched midpoint and
     * [isCrossing] would then throw a real root away, and at maximum zoom-out it can never be
     * reached and every iteration of the cap is spent.
     */
    private fun bisect(f: (Double) -> Double, lowStart: Double, highStart: Double): Double? {
        var low = lowStart
        var high = highStart
        var fLow = f(low)
        if (!fLow.isFinite()) return null
        repeat(MAX_ITERATIONS) {
            val mid = (low + high) / 2
            // The ends are adjacent doubles: halving again would return this same midpoint
            // for ever.
            if (mid <= low || mid >= high) return mid
            val fMid = f(mid)
            if (!fMid.isFinite()) return null
            if (fMid == 0.0) return mid
            if ((fMid > 0) == (fLow > 0)) {
                low = mid
                fLow = fMid
            } else {
                high = mid
            }
        }
        return (low + high) / 2
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
     * A cap on how many roots one pass reports.
     *
     * Both the bisection work and the readout are bounded by it: `sin(100x)` has hundreds of
     * roots in the default window and an identically zero function nominally has one per
     * sampled column.
     */
    const val MAX_ROOTS = 100
}
