package app.numera.calculator.ui.common

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a mode's shrink preview when a back gesture ends.
 *
 * The committed case is the obvious one. The abandoned case is the one that regressed: when
 * the user lets go short of the threshold, `PredictiveBackHandler` cancels the progress
 * channel *and the coroutine collecting it* in the same call, so a settle animation awaited
 * inside that coroutine throws on its first frame wait and the screen stays drawn at the last
 * snapped scale — a permanent 0.97-scale mode with a band of window background around it,
 * cleared only by leaving the route. The cancel path below reproduces the platform's exact
 * sequence and asserts the preview returns to rest anyway.
 */
class BackGesturePreviewTest {

    /**
     * A frame clock that steps 16 ms per request and, like the real one, refuses to deliver a
     * frame to a coroutine that has already been cancelled — the property the regression
     * depended on. Without that refusal a settle awaited in a dead coroutine would "work" here
     * and never on a device.
     */
    private class SteppingFrameClock : MonotonicFrameClock {
        private var nowNanos: Long = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            nowNanos += 16_000_000L
            return onFrame(nowNanos)
        }
    }

    @Test
    fun `an abandoned gesture eases the preview back to rest even though its coroutine is cancelled`() =
        runBlocking(SteppingFrameClock()) {
            val settleScope = CoroutineScope(coroutineContext + Job())
            val preview = BackGesturePreview(settleScope = settleScope, settleMillis = 180)
            val events: Channel<Float> = Channel(Channel.BUFFERED)
            var committed = 0

            val handler: Job = launch {
                preview.follow(onCommit = { committed++ }) {
                    events.receiveAsFlow().collect { value: Float -> preview.snapTo(value) }
                }
            }
            events.trySend(0.3f)
            repeat(4) { yield() }
            assertEquals("the preview must track the finger first", 0.3f, preview.fraction, 1e-6f)

            // Exactly what ComposePredictiveBackHandler.onBackCancelled() does, in this order.
            events.cancel(CancellationException("onBack cancelled"))
            handler.cancel()
            handler.join()

            settleScope.coroutineContext.job.children.forEach { settle -> settle.join() }

            assertEquals(0f, preview.fraction, 1e-6f)
            assertEquals(1f, preview.scale, 1e-6f)
            assertEquals("an abandoned gesture is not a back press", 0, committed)
        }

    @Test
    fun `a committed gesture snaps the preview to rest and fires the callback once`() =
        runBlocking(SteppingFrameClock()) {
            val settleScope = CoroutineScope(coroutineContext + Job())
            val preview = BackGesturePreview(settleScope = settleScope, settleMillis = 180)
            val events: Channel<Float> = Channel(Channel.BUFFERED)
            var committed = 0

            val handler: Job = launch {
                preview.follow(onCommit = { committed++ }) {
                    events.receiveAsFlow().collect { value: Float -> preview.snapTo(value) }
                }
            }
            events.trySend(0.5f)
            events.trySend(1f)
            events.close()
            handler.join()

            assertEquals(1, committed)
            assertEquals(0f, preview.fraction, 1e-6f)
            assertTrue(
                "nothing should be left animating after a commit",
                settleScope.coroutineContext.job.children.none(),
            )
        }

    @Test
    fun `the scale shrinks with the gesture and never below the committed size`() =
        runBlocking(SteppingFrameClock()) {
            val preview = BackGesturePreview(settleScope = this, settleMillis = 180)
            val events: Channel<Float> = Channel(Channel.BUFFERED)

            val handler: Job = launch {
                preview.follow(onCommit = {}) {
                    events.receiveAsFlow().collect { value: Float -> preview.snapTo(value) }
                }
            }
            events.trySend(1f)
            repeat(4) { yield() }

            assertEquals(0.92f, preview.scale, 1e-6f)
            events.cancel(CancellationException("onBack cancelled"))
            handler.cancel()
            handler.join()
            coroutineContext.job.children.forEach { settle -> settle.join() }
            assertEquals(1f, preview.scale, 1e-6f)
        }
}
