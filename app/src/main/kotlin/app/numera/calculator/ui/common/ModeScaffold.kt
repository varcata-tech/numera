package app.numera.calculator.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.numera.calculator.R
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/** How far the screen shrinks at a fully committed back gesture. */
private const val BACK_GESTURE_MIN_SCALE = 0.92f

/**
 * The frame every mode other than the main calculator is drawn in.
 *
 * @param title the mode's name, shown in the app bar.
 * @param onBack invoked when the user commits a back gesture or taps the back arrow;
 *   callers return to the calculator.
 * @param content the mode's body, given the app bar's inset padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    var backProgress: Float by remember { mutableFloatStateOf(0f) }

    // A plain BackHandler would consume the gesture and the system would fall back to the
    // non-predictive animation, so at targetSdk 36 the user gets the system's cross-activity
    // animation with no in-app preview and no way to abandon the swipe. Collecting the
    // progress flow is what keeps the gesture cancellable.
    PredictiveBackHandler(enabled = true) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event: BackEventCompat ->
                backProgress = event.progress
            }
            backProgress = 0f
            onBack()
        } catch (cancelled: CancellationException) {
            // The user swiped back and let go outside the commit threshold. Snapping the
            // scale back is the whole point of the preview; rethrowing would abort the
            // handler's coroutine before the screen returns to rest.
            backProgress = 0f
        }
    }

    val scale: Float = 1f - (1f - BACK_GESTURE_MIN_SCALE) * backProgress

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Edge to edge is enabled once in MainActivity, so without this the app bar
            // hides under the status bar and the last row of any keypad sits under the
            // gesture handle.
            .safeDrawingPadding()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // The root already consumed the system insets; leaving Scaffold's own default
            // in place would apply them a second time and leave a visible dead band.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            },
            content = content,
        )
    }
}
