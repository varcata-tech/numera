package app.numera.calculator.feature.graphing

import kotlin.math.abs
import kotlin.math.sin
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
    fun `roots of x squared minus two are found at plus and minus root two`() {
        val f = { x: Double -> x * x - 2.0 }
        val roots = RootFinder.roots(f, GraphSampler.sample(f, viewport, 400)).sorted()
        assertEquals(2, roots.size)
        assertEquals(-1.41421356, roots[0], 1e-6)
        assertEquals(1.41421356, roots[1], 1e-6)
    }

    @Test
    fun `x squared meets x plus two at minus one and two`() {
        val meetings = RootFinder.intersections(
            { x -> x * x }, { x -> x + 2.0 }, viewport, 400,
        ).sorted()
        assertEquals(2, meetings.size)
        assertEquals(-1.0, meetings[0], 1e-6)
        assertEquals(2.0, meetings[1], 1e-6)
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
