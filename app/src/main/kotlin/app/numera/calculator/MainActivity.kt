package app.numera.calculator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.numera.calculator.data.HistoryStore
import app.numera.calculator.data.LocalHistoryStore
import app.numera.calculator.nav.CalculatorApp
import app.numera.calculator.nav.Route
import app.numera.calculator.settings.LocalSettingsStore
import app.numera.calculator.settings.SettingsStore
import app.numera.calculator.ui.common.LocalHapticsEnabled
import app.numera.calculator.ui.theme.CalculatorTheme
import app.numera.calculator.ui.theme.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The name of the intent extra a launcher shortcut uses to name its destination.
 *
 * Duplicated as a literal in `res/xml/shortcuts.xml`, which cannot reference a Kotlin
 * constant; the two must be changed together.
 */
const val EXTRA_ROUTE: String = "app.numera.calculator.extra.ROUTE"

/**
 * The process-wide [SettingsStore] and [HistoryStore].
 *
 * Building these in [MainActivity.onCreate] is what this exists to prevent. The activity's
 * `ViewModelStore` survives a configuration change but the activity object does not, so a
 * store constructed in `onCreate` is a *new* instance that the surviving `CalculatorViewModel`
 * never sees: after one rotation the view model would insert history rows into store #1 while
 * the drawer collected store #2's flow, and no calculation would ever appear in the drawer
 * again for the rest of the session. Angle mode desynchronises the same way, and each
 * discarded [HistoryStore] leaks its `SQLiteOpenHelper` connection.
 *
 * Both `LocalSettingsStore` and `LocalHistoryStore` document that a single shared instance is
 * mandatory; this is where that promise is actually kept.
 */
private object AppStores {

    private var settingsStore: SettingsStore? = null
    private var historyStore: HistoryStore? = null

    /**
     * @param context any context; only its application context is retained.
     * @return the process's one settings store, creating it on first use.
     */
    @Synchronized
    fun settings(context: Context): SettingsStore =
        settingsStore ?: SettingsStore(context.applicationContext).also { settingsStore = it }

    /**
     * @param context any context; only its application context is retained.
     * @return the process's one history store, creating it on first use.
     */
    @Synchronized
    fun history(context: Context): HistoryStore =
        historyStore ?: HistoryStore(context.applicationContext).also { historyStore = it }
}

/**
 * The app's only activity.
 *
 * Every mode is a Compose destination inside it rather than an activity of its own, so
 * that switching modes never costs a window transition on a preloaded device where the
 * calculator is expected to be instant.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    private lateinit var history: HistoryStore

    /**
     * Shortcut taps that arrive while the app is already running.
     *
     * A [Channel] rather than a `mutableStateOf(Route)`, because a route request is an
     * *event*, not a value. The activity is `singleTask`, so tapping "Unit converter" a
     * second time re-delivers to [onNewIntent] rather than recreating anything; assigning
     * the same `Route` data object over itself is structurally equal, so a state holder
     * would record no change and the app would come to the foreground still showing
     * whatever screen the user had since navigated back to.
     *
     * Conflated: if two shortcuts arrive before the composition reads either, only the last
     * one asked for is worth honouring.
     */
    private val routeRequestChannel: Channel<Route> = Channel(Channel.CONFLATED)

    /** Exposed as a stable instance so the collecting `LaunchedEffect` never restarts. */
    private val routeRequests: Flow<Route> = routeRequestChannel.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Once, before setContent: called later the first frame is laid out inside the
        // old insets and the keypad visibly jumps as the window goes edge to edge.
        enableEdgeToEdge()

        settings = AppStores.settings(this)
        history = AppStores.history(this)

        // Only on a genuinely fresh start. On a recreation the composition restores the
        // route the user was actually on from its own saved state, and re-reading the
        // launch intent here would throw them back to the shortcut's destination — or to
        // the plain calculator — on every rotation, dark-mode toggle or font-scale change.
        val launchRoute: Route = if (savedInstanceState == null) {
            val named: Route? = routeFrom(intent)
            consumeRoute(intent)
            named ?: Route.Calculator
        } else {
            Route.Calculator
        }

        setContent {
            val themeMode: ThemeMode by settings.themeMode.collectAsStateWithLifecycle()
            val dynamicColor: Boolean by settings.dynamicColor.collectAsStateWithLifecycle()
            val haptics: Boolean by settings.hapticsEnabled.collectAsStateWithLifecycle()

            CalculatorTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                CompositionLocalProvider(
                    LocalSettingsStore provides settings,
                    LocalHistoryStore provides history,
                    LocalHapticsEnabled provides haptics,
                ) {
                    CalculatorApp(initialRoute = launchRoute, routeRequests = routeRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Null for a plain launcher tap, which for a singleTask activity also arrives here
        // when the task is brought forward. Navigating on that would eject a user who was
        // mid-way through the loan calculator back to the plain calculator.
        routeFrom(intent)?.let { requested -> routeRequestChannel.trySend(requested) }
        consumeRoute(intent)
    }

    /**
     * Reads the destination a launcher shortcut asked for.
     *
     * @param intent the intent that started or re-delivered to this activity.
     * @return the named route, or null when the intent names none. A plain launcher tap
     *   carries no extra, and a shortcut pinned by an older build may name a mode that no
     *   longer exists — in both cases a preloaded calculator must open as a calculator
     *   rather than guess.
     */
    private fun routeFrom(intent: Intent?): Route? {
        val named: String? = intent?.getStringExtra(EXTRA_ROUTE)
            // The shortcut also carries the id in its data URI. The extra is authoritative;
            // this is the fallback for a shortcut restored by a launcher that dropped the
            // extras bundle, which some third-party launchers do on backup and restore.
            ?: intent?.data?.lastPathSegment
        return Route.fromId(named)
    }

    /**
     * Strips the route markers from an intent that has already been acted on.
     *
     * `getIntent()` keeps returning the same object for the life of the task, so without
     * this a shortcut tapped once would keep naming its destination to every later reader
     * of the launch intent — a route request that fired months ago replaying itself.
     */
    private fun consumeRoute(intent: Intent?) {
        intent ?: return
        intent.removeExtra(EXTRA_ROUTE)
        intent.data = null
    }
}
