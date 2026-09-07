package app.numera.calculator.feature.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.numera.calculator.R
import app.numera.calculator.data.HistoryEntry
import app.numera.calculator.math.AngleMode

/**
 * The list of past calculations, pulled down over the keypad.
 *
 * Each row shows the formula small and its answer large, which is the shape a user scans:
 * they are looking for *the sum they did*, not the number it produced. Tapping a row inserts
 * the stored expression rather than its decimal, so continuing from a history entry keeps
 * every digit — see [app.numera.calculator.data.HistoryStore] for why that matters.
 */
@Composable
fun HistoryDrawer(
    entries: List<HistoryEntry>,
    onSelect: (HistoryEntry) -> Unit,
    onCopy: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    IconButton(onClick = { confirmingClear = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.history_clear),
                        )
                    }
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Newest at the bottom, nearest the display it came from, so the most
                    // recent calculation is the one already under the user's thumb.
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(
                            formula = entry.formula,
                            result = entry.result,
                            angleMode = entry.angleMode,
                            onClick = { onSelect(entry) },
                            onLongClick = { onCopy(entry) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmingClear = false
                }) { Text(stringResource(R.string.history_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    formula: String,
    result: String,
    angleMode: AngleMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Radians only, matching the display's own badge: degrees is the default and the unit
    // nearly every row is in, so marking those too would be noise on every line. Unmarked
    // therefore reads as degrees, which is what makes the marked row legible.
    val radians = angleMode == AngleMode.RADIANS
    // The answer is named as well as the formula, and the unit with them. An explicit
    // contentDescription on a merged node replaces the text of everything inside it, so
    // describing the row by its formula alone means a screen-reader user is read the sum and
    // never told the answer — and without the unit, `sin(30` is read out with an answer that
    // does not belong to the unit the app is in now.
    val description = if (radians) {
        stringResource(R.string.desc_history_entry_radians, formula, result)
    } else {
        stringResource(R.string.desc_history_entry, formula, result)
    }
    val copyLabel = stringResource(R.string.action_copy)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // combinedClickable, not clickable: Modifier.clickable has no long-press
            // handling at all, so the copy callback was accepted here and never called,
            // and the gesture instead fell through to the ordinary tap on finger-up.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            // mergeDescendants, because the contentDescription below is written on the
            // assumption of a merged node. Without it the two Texts stay separately
            // focusable, so a screen-reader user lands on the raw formula and then on the
            // raw answer, and the description that names both — and carries the copy action
            // — is a third stop rather than the row itself.
            .semantics(mergeDescendants = true) {
                contentDescription = description
                // A long press is unavailable to a switch user and to TalkBack, so copy has
                // to be reachable as an action as well as a gesture.
                customActions = listOf(
                    CustomAccessibilityAction(copyLabel) {
                        onLongClick()
                        true
                    },
                )
            },
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (radians) {
                // The one thing on the row that a switch to the other unit would change.
                // Without it two rows reading `sin(30` sit above two different answers with
                // nothing to say which is which.
                Text(
                    text = stringResource(R.string.mode_rad),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = formula,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = result,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
