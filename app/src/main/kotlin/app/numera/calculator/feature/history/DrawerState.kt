package app.numera.calculator.feature.history

/**
 * How far the history drawer is pulled down, and where it should land when released.
 *
 * Extracted from the composable deliberately. The interesting behaviour here — where a
 * half-open drawer settles, and whether a flick beats the halfway rule — is exactly the part
 * that cannot be checked by looking at it, and exactly the part that goes subtly wrong. As a
 * plain value type it is testable without a device, a Canvas or a frame clock.
 *
 * Offsets are in pixels, `0f` is closed, [maxOffset] is fully open.
 */
data class DrawerState(
    val offset: Float = 0f,
    val maxOffset: Float = 0f,
) {

    /** `0f` closed to `1f` open. Safe before the drawer has been measured. */
    val progress: Float
        get() = if (maxOffset <= 0f) 0f else (offset / maxOffset).coerceIn(0f, 1f)

    val isOpen: Boolean get() = maxOffset > 0f && offset >= maxOffset
    val isClosed: Boolean get() = offset <= 0f

    /** Applies a drag delta, clamped so the drawer cannot be dragged past either end. */
    fun drag(delta: Float): DrawerState =
        copy(offset = (offset + delta).coerceIn(0f, maxOffset))

    /**
     * Where the drawer lands when the finger lifts.
     *
     * A deliberate flick wins over position: someone who throws the drawer downward from
     * barely-open clearly means to open it, and snapping back to closed because they did not
     * cross the halfway mark feels broken. Only a slow release falls back to "nearest end".
     *
     * @param velocity pixels per second, positive downward.
     */
    fun settle(velocity: Float): DrawerState = when {
        maxOffset <= 0f -> copy(offset = 0f)
        velocity > FLING_THRESHOLD -> copy(offset = maxOffset)
        velocity < -FLING_THRESHOLD -> copy(offset = 0f)
        progress >= 0.5f -> copy(offset = maxOffset)
        else -> copy(offset = 0f)
    }

    fun opened(): DrawerState = copy(offset = maxOffset)

    fun closed(): DrawerState = copy(offset = 0f)

    /**
     * Records a new measured height.
     *
     * Keeps the drawer's open/closed *state* across a rotation or a window resize rather
     * than its raw pixel offset, which would otherwise leave it stranded part-open at the
     * old height — and make the next drag jump by the difference between the two heights.
     *
     * @param newMax the newly measured fully-open offset.
     * @param open where the drawer should land. Defaults to where it is now, which is the
     *   right answer for a resize; a rotation builds a brand-new state whose offset starts
     *   at zero, so that caller passes the open flag that actually survived instead.
     */
    fun resized(newMax: Float, open: Boolean = isOpen): DrawerState =
        DrawerState(offset = if (open) newMax else 0f, maxOffset = newMax)

    companion object {
        /** Pixels per second past which a release counts as a flick rather than a drop. */
        const val FLING_THRESHOLD = 400f
    }
}
