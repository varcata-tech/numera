package app.numera.calculator.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

/**
 * Shows a number that may be far wider than the screen.
 *
 * A 1000-digit result must stay readable, so the text never wraps and never ellipsises;
 * it scrolls horizontally instead. The view is pinned to the **right** edge whenever the
 * text changes, because the digits a user is watching while typing are the least
 * significant ones, and a left-pinned display would sit on the leading digits and appear
 * frozen as the value grows.
 *
 * A number is written left to right in every locale the app ships, Arabic included, so the
 * layout direction is pinned rather than inherited. Left to the ambient direction, "right
 * edge" becomes the *left* edge under RTL and the view opens on the leading digits of a long
 * result — the exact failure this composable exists to avoid, arrived at from the other side.
 *
 * @param text the value to display.
 * @param modifier applied to the scrolling container.
 * @param style the type style; defaults to the result size from the calculator scale.
 * @param onLongPress invoked on long press — normally "copy" or "open the full value".
 *   Null leaves long press to the parent.
 */
@Composable
fun ScrollableValueText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    onLongPress: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()

    // Keyed on maxValue as well as the text: on the frame the text changes, maxValue is
    // still the previous content's width, and scrolling to it lands short of the new end.
    // The second pass, once measurement has caught up, is what actually pins the right edge.
    LaunchedEffect(text, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier.horizontalScroll(scrollState),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier
                    // Short values must still sit against the right edge; without this the
                    // Text fills the scroll viewport and its own alignment takes over.
                    .wrapContentWidth(align = Alignment.End, unbounded = true)
                    .then(
                        if (onLongPress == null) {
                            Modifier
                        } else {
                            // detectTapGestures sits on the text rather than the scrolling
                            // box so a drag past touch slop is still claimed by the scroll.
                            Modifier.pointerInput(onLongPress) {
                                detectTapGestures(onLongPress = { onLongPress() })
                            }
                        }
                    ),
            )
        }
    }
}
