package app.numera.calculator.feature.graphing

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plotting geometry and the solver, tested without a Canvas.
 *
 * The discontinuity cases are the point: a plotter that joins every adjacent sample draws a
 * vertical line through the asymptote of 1/x, and that artefact is what makes a graphing
 * calculator look amateur.
 */
class GraphMathTest {

    private val viewport = Viewport(-10.0, 10.0, -10.0, 10.0)

    @Test
    fun `world and screen coordinates round trip`() {
        // Tolerance is Float-sized, not Double-sized: the trip goes through a pixel
        // coordinate, and Float carries about seven significant digits. Demanding more
        // would be testing the hardware rather than the transformation.
        val x = viewport.screenToWorldX(viewport.worldToScreenX(3.0, 1000f), 1000f)
        assertEquals(3.0, x, 1e-5)
        val y = viewport.screenToWorldY(viewport.worldToScreenY(-4.0, 800f), 800f)
        assertEquals(-4.0, y, 1e-5)
    }

    @Test
    fun `the y axis is flipped because screens grow downward`() {
        // The top of the window is the largest world y, at pixel zero.
        assertEquals(0f, viewport.worldToScreenY(10.0, 800f), 1e-4f)
        assertEquals(800f, viewport.worldToScreenY(-10.0, 800f), 1e-4f)
    }

    @Test
    fun `panning moves the window opposite to the drag`() {
        val panned = viewport.pan(100f, 0f, 1000f, 800f)
        // Dragging content right shows smaller x values.
        assertEquals(-12.0, panned.minX, 1e-5)
        assertEquals(8.0, panned.maxX, 1e-5)
    }

    @Test
    fun `zooming keeps the focal point fixed`() {
        val focusX = 5.0
        val focusY = 2.0
        val zoomed = viewport.zoom(2.0, 2.0, focusX, focusY)
        assertEquals(10.0, zoomed.width, 1e-9)
        // The focal point sits at the same fraction across the window as before.
        val before = (focusX - viewport.minX) / viewport.width
        val after = (focusX - zoomed.minX) / zoomed.width
        assertEquals(before, after, 1e-9)
    }

    @Test
    fun `zoom cannot collapse the window to nothing`() {
        var v = viewport
        repeat(200) { v = v.zoom(10.0, 10.0, 0.0, 0.0) }
        assertTrue("width collapsed to ${v.width}", v.width >= Viewport.MIN_SPAN)
    }

    @Test
    fun `a pinch that runs into the zoom limit keeps the axes on one scale`() {
        // The window "Square the axes" leaves on a tall phone: wider in y than in x. Clamping
        // the two spans independently let the height carry on shrinking after the width had
        // stopped at MIN_SPAN, so a plot that was round at the start of the pinch came out
        // visibly squashed, with nothing on the screen to say the axes were no longer square.
        var v = Viewport(-10.0, 10.0, -26.0, 26.0)
        val ratio = v.width / v.height
        repeat(100) { v = v.zoom(4.0, 4.0, 0.0, 0.0) }
        assertTrue("width collapsed to ${v.width}", v.width >= Viewport.MIN_SPAN)
        assertTrue("height collapsed to ${v.height}", v.height >= Viewport.MIN_SPAN)
        assertEquals(ratio, v.width / v.height, 1e-9)
    }

    @Test
    fun `squaring the axes cannot put a span outside the zoom limits`() {
        // Pressing the button at a zoom limit wrote a height the clamps exist to forbid, and
        // every later gesture then started from a window they had already rejected.
        val tight = Viewport(-Viewport.MIN_SPAN / 2, Viewport.MIN_SPAN / 2, -1.0, 1.0)
        assertEquals(Viewport.MIN_SPAN, tight.squared(2000f, 500f).height, 0.0)
        val wide = Viewport(-Viewport.MAX_SPAN / 2, Viewport.MAX_SPAN / 2, -1.0, 1.0)
        assertEquals(Viewport.MAX_SPAN, wide.squared(500f, 2000f).height, 1e-3)
    }

    @Test
    fun `tick steps are always one two or five times a power of ten`() {
        for (span in listOf(1.0, 3.7, 20.0, 0.004, 12345.0)) {
            val step = AxisTicks.step(span)
            val mantissa = step / Math.pow(10.0, Math.floor(Math.log10(step)))
            assertTrue(
                "step $step for span $span has mantissa $mantissa",
                abs(mantissa - 1.0) < 1e-9 || abs(mantissa - 2.0) < 1e-9 ||
                    abs(mantissa - 5.0) < 1e-9 || abs(mantissa - 10.0) < 1e-9,
            )
        }
    }

    @Test
    fun `ticks align to multiples of the step and stay inside the range`() {
        val ticks = AxisTicks.ticks(-10.0, 10.0)
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it >= -10.0 && it <= 10.0 })
        assertTrue("expected a tick at zero, got $ticks", ticks.any { abs(it) < 1e-9 })
    }

    @Test
    fun `ticks never degenerate into a thousand copies of one coordinate`() {
        // Far from the origin and narrower than the ulp of its own coordinates, `t += step`
        // stops advancing altogether. The accumulating loop then ran to its guard and handed
        // the grid a thousand identical lines to overdraw, every single frame.
        val degenerate = AxisTicks.ticks(1e10 - 5e-7, 1e10 + 5e-7)
        assertEquals(degenerate.distinct().size, degenerate.size)
        assertTrue("expected a bounded list, got ${degenerate.size}", degenerate.size <= 12)
        // A window that is merely far from the origin still gets a usable grid.
        val faraway = AxisTicks.ticks(1e6, 1e6 + 1e-9)
        assertEquals(faraway.distinct().size, faraway.size)
        assertTrue("expected some gridlines, got $faraway", faraway.isNotEmpty())
    }

    @Test
    fun `sampling produces one point per column`() {
        val samples = GraphSampler.sample({ x -> x * x }, viewport, 100)
        assertEquals(100, samples.xs.size)
        assertEquals(-10.0, samples.xs.first(), 1e-9)
        assertEquals(10.0, samples.xs.last(), 1e-9)
        assertEquals(100.0, samples.ys.first(), 1e-9)
    }

    @Test
    fun `non finite values become NaN rather than propagating`() {
        val samples = GraphSampler.sample({ x -> 1.0 / x }, Viewport(-1.0, 1.0, -10.0, 10.0), 3)
        // The middle column lands exactly on zero.
        assertTrue(samples.ys[1].isNaN())
    }

    @Test
    fun `the line breaks across an asymptote instead of drawing through it`() {
        // A jump from a large negative to a large positive is a pole, not a slope.
        assertTrue(GraphSampler.breaksBetween(-1e6, 1e6, viewport))
        assertTrue(GraphSampler.breaksBetween(Double.NaN, 1.0, viewport))
        // An ordinary steep-but-real slope must NOT be broken.
        assertFalse(GraphSampler.breaksBetween(1.0, 2.0, viewport))
    }

    @Test
    fun `no joined segment may span the whole visible height`() {
        // Testing the jump against the full height admits exactly the artefact the test
        // exists to remove: one pinch out from the default view the jump either side of the
        // pole of 1/x lands a hair under a full height, and the plot drew a solid line from
        // the bottom of the screen to the top straight through the asymptote.
        val wide = Viewport(-33.0, 33.0, -33.0, 33.0)
        assertTrue(GraphSampler.breaksBetween(-32.7, 32.7, wide))
        // A steep-but-real slope that stays well inside the window is still joined.
        assertFalse(GraphSampler.breaksBetween(1.0, 12.0, wide))
    }

    @Test
    fun `a steep line is joined through every column rather than scattered as dots`() {
        // 1000x climbs twice the window's height in one column, so the jump test alone broke
        // every segment and the whole line came out as two round-capped dots at the top and
        // bottom edges with nothing between. Sampling settles each candidate with one more
        // evaluation halfway between the two columns: a line passes through the midpoint
        // value, a pole does not.
        val line = GraphSampler.sample({ x -> 1000.0 * x }, viewport, 1016)
        for (i in 1 until line.xs.size) {
            assertTrue(
                "column $i of 1000x should be joined",
                GraphSampler.breaksBetween(line.ys[i - 1], line.ys[i], viewport),
            )
            assertTrue("column $i of 1000x was broken", line.continuous[i])
        }

        // The pole of 1/x is still broken: the midpoint lands on the pole itself. And the
        // steep-but-real segments either side of it, which the old rule also broke, are now
        // joined — the run from -13 to -40 one column short of the pole is the function.
        val pole = GraphSampler.sample({ x -> 1.0 / x }, viewport, 400)
        val across = (1 until pole.xs.size).first { pole.xs[it - 1] < 0.0 && pole.xs[it] >= 0.0 }
        assertFalse("the segment across the pole of 1/x was joined", pole.continuous[across])
        assertTrue(
            "the steep run up to the pole of 1/x was broken",
            GraphSampler.breaksBetween(pole.ys[across - 2], pole.ys[across - 1], viewport) &&
                pole.continuous[across - 1],
        )
    }

    @Test
    fun `a margin samples beyond the window without moving a single visible column`() {
        // What a pan reveals is drawn from samples taken before the finger went down, so the
        // margin is where the newly uncovered curve comes from. It must not shift the grid
        // inside the window: the root finder's exact-zero and last-column behaviour both
        // depend on `minX` and `maxX` being sampled exactly.
        val f = { x: Double -> x * x }
        val plain = GraphSampler.sample(f, viewport, 100)
        val padded = GraphSampler.sample(f, viewport, 100, margin = 1)
        assertEquals(300, padded.xs.size)
        // A margin is `columns` columns, which is one step more than a window's width.
        assertTrue("margin too short: ${padded.xs.first()}", padded.xs.first() <= -30.0)
        assertTrue("margin too short: ${padded.xs.last()}", padded.xs.last() >= 30.0)
        assertEquals(-10.0, padded.xs[100], 0.0)
        assertEquals(10.0, padded.xs[199], 0.0)
        for (i in 0 until 100) {
            assertEquals("column $i moved", plain.xs[i], padded.xs[i + 100], 0.0)
            assertEquals("column $i changed value", plain.ys[i], padded.ys[i + 100], 0.0)
        }
        // And the visible slice is the plain sampling again, so the roots see the same grid.
        val visible = GraphSampler.visible(padded, viewport)
        assertEquals(100, visible.xs.size)
        assertEquals(-10.0, visible.xs.first(), 0.0)
        assertEquals(10.0, visible.xs.last(), 0.0)
        // Without a margin the samples are already the visible ones.
        assertTrue(GraphSampler.visible(plain, viewport) === plain)
    }

    @Test
    fun `the roots readout counts only what is in view against its cap`() {
        // The margin exists for the canvas alone. Fed the whole array, the root finder spent
        // its hundred on the left margin — sin(100x) has six hundred roots per window — and
        // the line captioned "in view" listed roots two screens to the left.
        val f = { x: Double -> sin(100 * x) }
        val padded = GraphSampler.sample(f, viewport, 1080, margin = 1)
        val roots = RootFinder.roots(f, GraphSampler.visible(padded, viewport))
        assertEquals(RootFinder.MAX_ROOTS, roots.size)
        assertTrue("a root outside the window was listed: ${roots.first()}", roots.all { it >= -10.0 })
    }

    @Test
    fun `a root the curve touches without crossing is found`() {
        // x^2 is the first thing anyone plots, and it never changes sign. It was found only
        // when a column landed on zero exactly, which at the default window happens at almost
        // no device width, so the readout printed "No roots in view" under a parabola
        // visibly resting on the axis — and (x-1)^2 was never found on any device at all.
        for (columns in listOf(672, 900, 1016, 1080, 1440)) {
            val parabola = { x: Double -> x * x }
            val origin = RootFinder.roots(parabola, GraphSampler.sample(parabola, viewport, columns))
            assertEquals("at $columns columns", 1, origin.size)
            assertEquals("at $columns columns", 0.0, origin[0], 1e-9)

            // Refined to full resolution, not just to the point where the touch is
            // recognised: stopping there reported 1.00009, which prints as 1.0001.
            val shifted = { x: Double -> (x - 1.0) * (x - 1.0) }
            val one = RootFinder.roots(shifted, GraphSampler.sample(shifted, viewport, columns))
            assertEquals("at $columns columns", 1, one.size)
            assertEquals("at $columns columns", 1.0, one[0], 1e-6)
        }
        // A tangency far from the origin, where the ulp is coarser, at the tightest zoom.
        val deep = { x: Double -> (x - 10.0) * (x - 10.0) }
        val tight = Viewport(10.0 - 5e-10, 10.0 + 5e-10, -1e-9, 1e-9)
        val roots = RootFinder.roots(deep, GraphSampler.sample(deep, tight, 1080))
        assertEquals(1, roots.size)
        assertEquals(10.0, roots[0], 1e-12)
    }

    @Test
    fun `a curve that only comes close to the axis has no root`() {
        // The touch test is relative to the neighbouring samples, the way the crossing test
        // is: a minimum that stays a fixed fraction of its neighbours is a curve passing
        // near the axis, not one resting on it. x^2+0.001 sits under a pixel of the axis at
        // the default zoom and still must not be reported.
        for (offset in listOf(1.0, 0.01, 0.001, 1e-6)) {
            val near = { x: Double -> x * x + offset }
            val roots = RootFinder.roots(near, GraphSampler.sample(near, viewport, 1016))
            assertTrue("x^2 + $offset has no root, found $roots", roots.isEmpty())
        }
        // A function with hundreds of local minima, none of them roots, stays rootless.
        val wave = { x: Double -> sin(100 * x) + 2.0 }
        assertTrue(RootFinder.roots(wave, GraphSampler.sample(wave, viewport, 1080)).isEmpty())
    }

    @Test
    fun `a root on the edge of the domain is found, and a pole there is not`() {
        // The root of √x sits where the function starts existing: the column to its left is
        // NaN, so no bracket ever formed and the readout denied the root the plot clearly
        // shows. ln(x) has the same boundary and diverges there instead; its only root in
        // the window is at 1. √x + 1 reaches the boundary at 1 and has no root at all.
        val root = { x: Double -> sqrt(x) }
        val atZero = RootFinder.roots(root, GraphSampler.sample(root, viewport, 1016))
        assertEquals(1, atZero.size)
        assertEquals(0.0, atZero[0], 1e-9)

        val log = { x: Double -> ln(x) }
        val logRoots = RootFinder.roots(log, GraphSampler.sample(log, viewport, 1016))
        assertEquals(listOf(1.0), logRoots.map { Math.round(it * 1e9) / 1e9 })

        val lifted = { x: Double -> sqrt(x) + 1.0 }
        assertTrue(RootFinder.roots(lifted, GraphSampler.sample(lifted, viewport, 1016)).isEmpty())

        // Both edges of a bounded domain.
        val arc = { x: Double -> sqrt(1.0 - x * x) }
        val ends = RootFinder.roots(arc, GraphSampler.sample(arc, viewport, 1016)).sorted()
        assertEquals(2, ends.size)
        assertEquals(-1.0, ends[0], 1e-9)
        assertEquals(1.0, ends[1], 1e-9)
    }

    @Test
    fun `roots of x squared minus two are found at plus and minus root two`() {
        val f = { x: Double -> x * x - 2.0 }
        val roots = RootFinder.roots(f, GraphSampler.sample(f, viewport, 400)).sorted()
        assertEquals(2, roots.size)
        assertEquals(-1.41421356, roots[0], 1e-6)
        assertEquals(1.41421356, roots[1], 1e-6)
    }

    @Test
    fun `a root sitting on the last sampled column is reported`() {
        // The scan used to stop one column short, and the sign-change test cannot stand in
        // for the missing zero test: with y1 exactly zero, `y0 > 0 != y1 > 0` is only true
        // when y0 is *positive*, so a curve arriving at zero from below at the right edge
        // produced no bracket at all. In the reset window the last sample is exactly 10.0 at
        // every column count the app can be laid out at, so this was deterministic: the
        // parabola visibly crossed the axis at both edges and the readout named one root.
        for (columns in listOf(900, 1016, 1080, 1440)) {
            val parabola = { x: Double -> x * x - 100.0 }
            val both = RootFinder.roots(
                parabola, GraphSampler.sample(parabola, viewport, columns),
            ).sorted()
            assertEquals("at $columns columns", 2, both.size)
            assertEquals(-10.0, both[0], 1e-9)
            assertEquals(10.0, both[1], 1e-9)

            // The mirrored function was already right, which is what made the asymmetry easy
            // to miss, and a straight line crossing at the edge was silently rootless.
            val line = { x: Double -> x - 10.0 }
            val edge = RootFinder.roots(line, GraphSampler.sample(line, viewport, columns))
            assertEquals("at $columns columns", listOf(10.0), edge)
        }
    }

    @Test
    fun `a zero at both ends of one bracket is reported once, not twice`() {
        // The zero at the right end of a bracket is left to the column that owns it. Refining
        // the bracket as well would report the same crossing twice — once bisected to within
        // an ulp of the column and once exactly on it — which prints as one number listed
        // twice, the readout equivalent of an off-by-one.
        val f = { x: Double -> 100.0 - x * x }
        val roots = RootFinder.roots(f, GraphSampler.sample(f, viewport, 1080)).sorted()
        assertEquals(listOf(-10.0, 10.0), roots)
    }

    @Test
    fun `a crossing between two adjacent doubles is a root, not a rejected bracket`() {
        // Bisection cannot narrow a bracket that is already two adjacent doubles wide, so the
        // residual it reports is simply the value at one of the two bracketing samples.
        // Demanding that the residual have *fallen* by half then threw away a perfectly real
        // root: here the two endpoints are equal and opposite, so the ratio is exactly one
        // half and the test failed on the boundary.
        val k = Math.nextUp(1e12)
        val f = { x: Double -> x * x - k }
        val low = 1e6
        val high = Math.nextUp(low)
        val adjacent = Samples(doubleArrayOf(low, high), doubleArrayOf(f(low), f(high)))
        val direct = RootFinder.roots(f, adjacent)
        assertEquals(1, direct.size)
        assertEquals(low, direct[0], Math.ulp(low))

        // And by the route the app actually takes there: MIN_SPAN is 1e-9, which is far under
        // one ulp of 1e6, so every bracket in that window arrives already collapsed.
        val deep = Viewport(low - 5e-10, low + 5e-10, -1.0, 1.0)
        val zoomed = RootFinder.roots(f, GraphSampler.sample(f, deep, 900))
        assertEquals(1, zoomed.size)
        assertEquals(low, zoomed[0], Math.ulp(low))
    }

    @Test
    fun `the pole of one over x is not reported as a root`() {
        // 1/x changes sign at zero but never crosses it; calling that a root is wrong.
        //
        // An even column count and an off-centre window on purpose. With an odd count over a
        // symmetric window a sample lands exactly on the pole, is discarded as NaN, and both
        // brackets around it disappear before the pole test is ever consulted — so that
        // arrangement passes no matter what the pole test does.
        val f = { x: Double -> 1.0 / x }
        val centred = RootFinder.roots(f, GraphSampler.sample(f, viewport, 400))
        assertTrue("1/x should have no roots, found $centred", centred.isEmpty())
        val panned = Viewport(-9.3, 10.7, -10.0, 10.0)
        val offCentre = RootFinder.roots(f, GraphSampler.sample(f, panned, 400))
        assertTrue("1/x should have no roots, found $offCentre", offCentre.isEmpty())
    }

    @Test
    fun `the poles of tan are rejected while its zeros are kept`() {
        // tan changes sign at every multiple of pi over two: at the multiples of pi because
        // it crosses zero, and at the odd multiples because it blows up. Reporting the poles
        // as roots contradicts the app's own exact engine, which calls tan of ninety degrees
        // a divide by zero rather than a number.
        val f = { x: Double -> tan(x) }
        val roots = RootFinder.roots(f, GraphSampler.sample(f, viewport, 1080)).sorted()
        // Zeros at 0 and at plus or minus pi, two pi and three pi.
        assertEquals(7, roots.size)
        for (root in roots) {
            assertTrue("tan($root) = ${tan(root)} is not zero", abs(tan(root)) < 1e-6)
        }
    }

    @Test
    fun `a line too steep to fit the window still reports its root`() {
        // Rejecting a bracket on the absolute size of its endpoints threw this root away:
        // both samples either side of the crossing are about 1e7, so the graph drew a line
        // through the origin while the readout claimed there was no root in view.
        val f = { x: Double -> 1e9 * x }
        val roots = RootFinder.roots(f, GraphSampler.sample(f, viewport, 1080))
        assertEquals(1, roots.size)
        assertEquals(0.0, roots[0], 1e-9)
    }

    @Test
    fun `a jump discontinuity is not a root`() {
        // A step changes sign without ever taking the value zero, so there is nothing to
        // report; bisection on its own would happily converge on the step.
        val f = { x: Double -> if (x < 0.3) -1.0 else 1.0 }
        val roots = RootFinder.roots(f, GraphSampler.sample(f, viewport, 400))
        assertTrue("a step has no root, found $roots", roots.isEmpty())
    }

    @Test
    fun `the root list is capped so one function cannot flood the readout`() {
        // sin(100x) has hundreds of roots in the default window. Unbounded, the readout that
        // spells them out squeezed the canvas and the delete buttons out of the layout
        // entirely, leaving no way to remove the function that caused it.
        val f = { x: Double -> sin(100 * x) }
        val roots = RootFinder.roots(f, GraphSampler.sample(f, viewport, 1080))
        assertEquals(RootFinder.MAX_ROOTS, roots.size)
    }

    @Test
    fun `a window narrower than one ulp does not repeat the same root`() {
        // Every column there samples the same double, so the same coordinate is found over
        // and over; a hundred copies of one number reads as a hundred separate roots.
        val f = { x: Double -> x - 1e6 }
        val narrow = Viewport(1e6 - 5e-10, 1e6 + 5e-10, -1.0, 1.0)
        val roots = RootFinder.roots(f, GraphSampler.sample(f, narrow, 400))
        assertEquals(1, roots.size)
        assertEquals(1e6, roots[0], 1e-6)
    }

    @Test
    fun `a root survives at the tightest zoom the viewport allows`() {
        // The bracket is then narrower than any absolute convergence tolerance. Stopping
        // bisection at one would return the untouched midpoint, whose value has not fallen
        // at all, and the crossing check would throw a perfectly real root away.
        //
        // Note what this case does *not* cover: a sample lands on the root exactly, so it
        // leaves through the exact-zero path without ever reaching the guard that fires when
        // a bracket cannot be halved. `a crossing between two adjacent doubles` above is the
        // case that exercises that guard, and a real root was being discarded there for as
        // long as this test was the only deep-zoom one.
        val f = { x: Double -> x - 1.0 }
        val tight = Viewport(1.0 - 5e-10, 1.0 + 5e-10, -1e-9, 1e-9)
        val roots = RootFinder.roots(f, GraphSampler.sample(f, tight, 1080))
        assertEquals(1, roots.size)
        assertEquals(1.0, roots[0], 1e-12)
    }

    @Test
    fun `squaring the axes equalises the scale per pixel`() {
        val squared = Viewport(-10.0, 10.0, -100.0, 100.0).squared(1000f, 500f)
        val perPixelX = squared.width / 1000f
        val perPixelY = squared.height / 500f
        assertEquals(perPixelX, perPixelY, 1e-9)
    }

    @Test
    fun `squaring against a one by one canvas cannot change anything`() {
        // Why the view model has to know the canvas height rather than pass literal ones.
        // Every window the app can reach is already square in world units — the default is,
        // and pan and zoom both preserve it — so squaring against a 1:1 canvas hands back the
        // very same window while the plot stays visibly stretched on a tall screen. The
        // button was incapable of doing anything at all.
        val square = Viewport(-10.0, 10.0, -10.0, 10.0)
        assertEquals(square, square.squared(1f, 1f))
        // With the real canvas it does the job: one world unit becomes one count of pixels.
        // The tolerance is Float-sized because the aspect ratio is computed in Float.
        val fitted = square.squared(1080f, 1400f)
        assertEquals(square.width / 1080f, fitted.height / 1400f, 1e-6)
    }
}
