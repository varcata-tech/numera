package app.numera.calculator.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import app.numera.calculator.R

/**
 * The height [rows] keypad rows need before any of them drops under [minRowHeight].
 *
 * Rows are separated by [spacing], and there is one fewer gap than there are rows — counting a
 * gap per row is the off-by-one that reports a pad as not fitting when it does, and puts a
 * scrollbar on a keypad that had 8dp to spare.
 */
fun keypadMinHeight(rows: Int, minRowHeight: Dp, spacing: Dp): Dp {
    require(rows > 0) { "A keypad has at least one row, got $rows" }
    return minRowHeight * rows + spacing * (rows - 1)
}

/**
 * Whether [rows] rows can share [available] height without any falling under [minRowHeight].
 *
 * An unbounded height is reported as *not* fitting. Weighted rows measure to zero when their
 * parent imposes no height, so a pad handed unbounded constraints has to fall back to
 * fixed-height rows just as a pad handed too little height does.
 */
fun keypadRowsFit(available: Dp, rows: Int, minRowHeight: Dp, spacing: Dp): Boolean =
    available.value.isFinite() && available >= keypadMinHeight(rows, minRowHeight, spacing)

/**
 * The column every keypad in the app lays its rows into.
 *
 * Exists because of what happens to keys in a short window. Each key is
 * `weight(1f).fillMaxHeight()` inside its row and each row is `weight(1f)` inside the pad, so
 * a pad given less height than its rows need divides what it has evenly and every key comes
 * out under the 48dp touch target — 31dp on a landscape phone, and on the programmer pad, whose
 * seven rows share a shorter band still, nearer 36dp. `CalcButton` cannot enforce the floor
 * itself: by the time the key is measured its constraints are already fixed, and forcing a
 * minimum there would make adjacent rows overlap so a tap on the top of one key lands on the
 * key below. The floor has to be enforced here, where the height is still negotiable.
 *
 * When the rows fit, they are weighted and fill the pad exactly as before. When they do not,
 * every row is pinned to `calc_key_min_size` and the column scrolls. A keypad that scrolls is
 * a compromise, but a keypad whose keys cannot be hit reliably is a defect, and the scroll is
 * only ever reached in a window too short for the keypad it holds.
 *
 * @param rows how many rows [content] emits. Counted rather than measured because the decision
 *   has to be made before the rows are composed.
 * @param modifier applied to the pad as a whole. Must bound the height, or the fallback is taken.
 * @param content the rows, each of which must apply the given modifier to its `Row` — that is
 *   what carries either the weight or the fixed height.
 */
@Composable
fun KeypadColumn(
    rows: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(row: Modifier) -> Unit,
) {
    val minRowHeight: Dp = dimensionResource(R.dimen.calc_key_min_size)
    val spacing: Dp = dimensionResource(R.dimen.calc_key_spacing)
    BoxWithConstraints(modifier = modifier) {
        if (keypadRowsFit(maxHeight, rows, minRowHeight, spacing)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                content(Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                // Fixed, not weighted: under a scroll's unbounded height a weighted row
                // measures to zero, which is the collapse this branch exists to avoid.
                content(Modifier.fillMaxWidth().height(minRowHeight))
            }
        }
    }
}
