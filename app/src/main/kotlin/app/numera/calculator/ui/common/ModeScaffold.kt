package app.numera.calculator.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.numera.calculator.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

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
    // The settle animation of an abandoned gesture cannot run inside the back handler's own
    // coroutine — the platform cancels that coroutine as part of cancelling the gesture — so
    // the preview is given a scope that lives as long as this composition instead.
    val settleScope: CoroutineScope = rememberCoroutineScope()
    val preview: BackGesturePreview = remember(settleScope) { BackGesturePreview(settleScope) }

    // A plain BackHandler would consume the gesture and the system would fall back to the
    // non-predictive animation, so the user gets the system's cross-activity animation with
    // no in-app preview and no way to abandon the swipe. Collecting the progress flow is what
    // keeps the gesture cancellable. Progress events only arrive at all because the manifest
    // opts into OnBackInvokedCallback: without that flag every device below Android 16 takes
    // the legacy onBackPressed path, where the flow completes at once and nothing is shown.
    PredictiveBackHandler(enabled = true) { progress: Flow<BackEventCompat> ->
        // The collect call stays in this lambda, and is not folded into the preview class:
        // the handler's own lint check (NoCollectCallFound, fatal) requires it here, because
        // the collect is what splits the callback into "started" and "finished".
        preview.follow(onCommit = onBack) {
            progress.collect { event: BackEventCompat -> preview.snapTo(event.progress) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Painted here, outside the scaled layer and before the insets are consumed. The
            // window background behind the transparent system bars is set once at launch from
            // the stored ThemeMode; switching the theme in Settings recomposes this scheme but
            // never repaints the window, so without this the status-bar and gesture-bar bands
            // stayed the old theme's colour — a white frame around a dark screen, with the
            // now-light clock and battery icons drawn invisibly on it. Being outside the layer
            // also means the border a back gesture reveals is the scheme's colour, not the
            // window's.
            .background(MaterialTheme.colorScheme.background)
            .graphicsLayer {
                // Read here rather than in composition: the progress changes on every frame
                // of the gesture, and reading it inside the layer block keeps that to the
                // draw phase instead of recomposing the whole mode sixty times a second.
                val scale: Float = preview.scale
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
