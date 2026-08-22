package app.numera.calculator.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.numera.calculator.R

/**
 * The role a key plays, which selects its colour pair.
 *
 * @property DIGIT a numeral, the decimal point, or a plain value key.
 * @property OPERATOR an arithmetic operator — the keys that structure the expression.
 * @property FUNCTION a named function, constant, or mode switch.
 * @property ACCENT the single most important key on a keypad, normally equals.
 * @property DESTRUCTIVE clear or delete.
 */
enum class KeyStyle { DIGIT, OPERATOR, FUNCTION, ACCENT, DESTRUCTIVE }

/**
 * How heavily a key's label is drawn.
 *
 * Digits carry more weight than the operators and functions around them. They are what the
 * eye lands on while typing, and the pad reads faster when the numerals are the strongest
 * thing on it — the operators do not need to compete, because their colour already separates
 * them.
 */
private fun KeyStyle.labelWeight(): FontWeight = when (this) {
    KeyStyle.DIGIT -> FontWeight.Bold
    else -> FontWeight.Normal
}

/**
 * How large a key's label is drawn, relative to the shared headline size.
 *
 * Applied here rather than by raising `calc_text_headline_large`, because that dimension is
 * shared with the operator and function keys and enlarging it would scale the whole pad,
 * losing the hierarchy this exists to create.
 *
 * Oversizing is safe: [AutoShrinkingLabel] steps any label back down until it fits its box,
 * so a wide glyph in a narrow key or a 2.0 font scale degrades to a smaller digit rather
 * than a clipped one.
 */
private fun KeyStyle.labelScale(): Float = when (this) {
    KeyStyle.DIGIT -> 1.25f
    else -> 1f
}

/** The container and content colours a [KeyStyle] resolves to in the current scheme. */
private data class KeyColors(val container: Color, val content: Color)

@Composable
private fun KeyStyle.colors(): KeyColors {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        KeyStyle.DIGIT -> KeyColors(scheme.surfaceContainerHigh, scheme.onSurface)
        KeyStyle.OPERATOR -> KeyColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        KeyStyle.FUNCTION -> KeyColors(scheme.surfaceContainer, scheme.onSurfaceVariant)
        KeyStyle.ACCENT -> KeyColors(scheme.primary, scheme.onPrimary)
        KeyStyle.DESTRUCTIVE -> KeyColors(scheme.errorContainer, scheme.onErrorContainer)
    }
}

/** Below this the label is unreadable, so the key clips rather than shrinking further. */
private val MinimumLabelSize = 11.sp

/**
 * One key on any of the app's keypads.
 *
 * Every keypad in the app is built out of this, so it has to survive the extremes: a 2.0
 * system font scale, a 320dp-wide screen, and TalkBack. The label therefore auto-shrinks
 * to fit instead of clipping.
 *
 * **The 48dp minimum is the caller's responsibility, not this composable's.** `defaultMinSize`
 * below only raises a minimum that arrives as zero, and every keypad in the app hands each key
 * `Modifier.weight(1f).fillMaxHeight()` inside a fixed-height row — fully determined constraints
 * in both axes, which `defaultMinSize` passes through untouched. A pad that divides a short
 * window (landscape, split screen) into more rows than fit therefore produces keys well under
 * 48dp, and nothing here can widen them. The floor has to be enforced where the constraints are
 * still loose: by the pad, which must bound its row height and scroll or shed a pad rather than
 * shrink past the minimum.
 *
 * Haptics fire on press rather than on release — a keypad that ticks when your finger
 * lifts feels lagged — and only when both the in-app preference ([LocalHapticsEnabled])
 * and the system touch-feedback setting allow it.
 *
 * @param label the glyph or text drawn on the key.
 * @param contentDescription what TalkBack announces; the glyph alone is often meaningless
 *   ("×" reads as "x"), so this is required rather than derived from [label].
 * @param onClick invoked on release.
 * @param modifier applied to the key's outer box; use it to size and weight the key.
 * @param style selects the colour pair.
 * @param onLongClick optional secondary action; null leaves long press unhandled.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalcButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KeyStyle = KeyStyle.DIGIT,
    onLongClick: (() -> Unit)? = null,
) {
    val keyColors: KeyColors = style.colors()
    val shape: Shape = RoundedCornerShape(dimensionResource(R.dimen.calc_key_corner))
    val view = LocalView.current
    val hapticsEnabled: Boolean = LocalHapticsEnabled.current
    val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }

    // Collecting the press interaction rather than hooking onClick is what makes the tick
    // land on finger-down; combinedClickable's onClick only fires once the press resolves,
    // which on a fast keypad is late enough to feel disconnected from the tap.
    LaunchedEffect(interactionSource, hapticsEnabled, view) {
        interactionSource.interactions.collect { interaction ->
            if (hapticsEnabled && interaction is PressInteraction.Press) {
                view.performKeyTap()
            }
        }
    }

    // Aliased because inside the semantics lambda the bare name would resolve to the
    // SemanticsPropertyReceiver's own property, not to this parameter.
    val description: String = contentDescription

    Box(
        modifier = modifier
            // Only bites when the caller leaves an axis unconstrained (a wrap-content key in
            // a dialog or a preview). Under the weight+fillMaxHeight the keypads pass, both
            // constraint minimums are already non-zero and this is a no-op — see the KDoc.
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            // Clipped before the ripple is attached so the ripple is bounded by the key's
            // own rounded shape instead of bleeding into the neighbouring keys.
            .clip(shape)
            .background(color = keyColors.container, shape = shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(color = keyColors.content),
                role = Role.Button,
                onLongClick = onLongClick?.let { action ->
                    {
                        if (hapticsEnabled) view.performLongPressTick()
                        action()
                    }
                },
                onClick = onClick,
            )
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AutoShrinkingLabel(
            text = label,
            color = keyColors.content,
            baseStyle = MaterialTheme.typography.headlineLarge.let { headline ->
                headline.copy(
                    fontWeight = style.labelWeight(),
                    fontSize = headline.fontSize * style.labelScale(),
                    // The line height has to grow with the text or a taller glyph is
                    // vertically clipped inside its own line box before the shrink-to-fit
                    // pass ever sees an overflow to react to.
                    lineHeight = headline.lineHeight * style.labelScale(),
                )
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * Single-line text that steps its own size down until it fits its box.
 *
 * Nothing is drawn until a measured pass fits, because the alternative is a visible flash
 * of oversized text on every key each time the keypad recomposes.
 */
@Composable
private fun AutoShrinkingLabel(
    text: String,
    color: Color,
    baseStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    var style: TextStyle by remember(text, baseStyle) { mutableStateOf(baseStyle) }
    var measured: Boolean by remember(text, baseStyle) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        modifier = modifier.drawWithContent { if (measured) drawContent() },
        onTextLayout = { result: TextLayoutResult ->
            val overflows: Boolean = result.didOverflowWidth || result.didOverflowHeight
            // The floor is what stops this from looping forever: without it a label wider
            // than its box shrinks by 8% per frame indefinitely and the key stays blank.
            if (overflows && style.fontSize.value > MinimumLabelSize.value) {
                // lineHeight is dropped to Unspecified rather than scaled alongside the
                // font: left at its original fixed value it keeps reporting a height
                // overflow no matter how small the glyphs get, and the loop runs to the
                // floor every time.
                style = style.copy(
                    fontSize = style.fontSize * 0.92f,
                    lineHeight = TextUnit.Unspecified,
                )
            } else {
                measured = true
            }
        },
    )
}
