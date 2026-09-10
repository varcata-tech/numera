package app.numera.calculator.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How far the screen shrinks at a fully committed back gesture. */
private const val BACK_GESTURE_MIN_SCALE: Float = 0.92f

/** How long an abandoned back gesture takes to settle back to full size. */
private const val BACK_GESTURE_CANCEL_MILLIS: Int = 180

/**
 * Drives the shrink preview a mode screen shows while a predictive back gesture is in flight.
 *
 * Kept out of the composable because of how `PredictiveBackHandler` ends an abandoned gesture:
 * `onBackCancelled()` cancels the progress channel *and then the handler's own coroutine*, so
 * anything the handler suspends on after the cancellation — a frame wait included — throws
 * before the first frame is delivered. The first version animated the settle inside that
 * coroutine, and every abandoned swipe left the mode drawn at the last snapped scale until the
 * user left the route. The settle therefore runs on [settleScope], a scope that outlives the
 * gesture, and that is the behaviour the unit test pins.
 *
 * @param settleScope where the return-to-rest animation runs. Must carry a
 *   `MonotonicFrameClock`; `rememberCoroutineScope()` does.
 * @param settleMillis how long the abandoned gesture takes to ease back.
 */
class BackGesturePreview(
    private val settleScope: CoroutineScope,
    private val settleMillis: Int = BACK_GESTURE_CANCEL_MILLIS,
) {
    // An Animatable rather than a plain float, because the two ways a gesture ends need
    // different motion. While the finger is down the preview must track it exactly, so the
    // progress is snapped; when the gesture is abandoned the platform's own cancel is
    // animated, and assigning 0f jumped the screen from 0.92 scale back to 1.0 in a single
    // frame — which reads as a rendering glitch rather than as the swipe being refused.
    private val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** How far the gesture has travelled, 0 at rest and 1 at the commit threshold. */
    val fraction: Float get() = progress.value

    /** The scale to draw the screen at for the current [fraction]; 1 at rest. */
    val scale: Float get() = 1f - (1f - BACK_GESTURE_MIN_SCALE) * progress.value

    /**
     * Moves the preview to [fraction] of the way to the commit threshold, with no animation.
     *
     * Called from inside the handler's own collection of the progress flow, once per platform
     * event; see [follow].
     */
    suspend fun snapTo(fraction: Float) {
        progress.snapTo(fraction)
    }

    /**
     * Follows one gesture from its first event to its end.
     *
     * [track] is the handler's collection of the platform's progress flow, feeding [snapTo].
     * It is taken as a block rather than as the flow itself because `PredictiveBackHandler`'s
     * contract — enforced by its `NoCollectCallFound` lint check, which is fatal — is that the
     * handler lambda calls `collect` in its own body: the collect call is what splits the
     * callback into "gesture started" and "gesture finished", and a flow mapped and handed to
     * another function to collect reads to the check as a handler that runs everything at the
     * first event.
     *
     * Suspends until [track] returns or is cancelled. Return means the user committed: the
     * preview snaps to rest and [onCommit] runs. Cancellation — of the flow, of the calling
     * coroutine, or both at once as the platform does it — means the user let go short of the
     * threshold: the settle is launched on the outer scope and the cancellation is rethrown, so
     * a caller that is already cancelled completes the way cancellation expects rather than
     * pretending the gesture finished.
     *
     * @param onCommit invoked once when the gesture completes.
     * @param track collects the gesture's progress, 0 to 1, and hands each value to [snapTo].
     */
    suspend fun follow(onCommit: () -> Unit, track: suspend () -> Unit) {
        try {
            track()
        } catch (cancelled: CancellationException) {
            // Launched, not awaited: the coroutine running this catch block is normally already
            // cancelled, and suspending on a frame here throws before anything moves. A cancelled
            // settleScope (the mode has left composition) makes this a no-op, which is correct —
            // there is nothing left to draw.
            settleScope.launch {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = settleMillis),
                )
            }
            throw cancelled
        }
        progress.snapTo(0f)
        onCommit()
    }
}
