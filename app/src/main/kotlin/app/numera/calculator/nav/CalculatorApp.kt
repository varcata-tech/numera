package app.numera.calculator.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.numera.calculator.feature.calc.CalculatorScreen
import app.numera.calculator.feature.converter.ConverterScreen
import app.numera.calculator.feature.dates.DateTimeScreen
import app.numera.calculator.feature.financial.FinancialScreen
import app.numera.calculator.feature.graphing.GraphingScreen
import app.numera.calculator.feature.programmer.ProgrammerScreen
import app.numera.calculator.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Which destination is on screen.
 *
 * Deliberately not `navigation-compose`: seven flat destinations with no arguments, no
 * deep links beyond a launcher shortcut and no back stack do not repay a navigation
 * graph, a dependency and a serialization format.
 *
 * @param initialRoute where to start; [Route.Calculator] unless a shortcut named another.
 */
@Stable
class CalculatorAppState(initialRoute: Route) {
    private val _route: MutableStateFlow<Route> = MutableStateFlow(initialRoute)

    /** The destination currently rendered. */
    val route: StateFlow<Route> = _route.asStateFlow()

    /**
     * Moves to [target].
     *
     * @param target the destination to show.
     */
    fun navigateTo(target: Route) {
        if (_route.value != target) {
            _route.value = target
        }
    }

    /**
     * Returns to the calculator.
     *
     * Back has exactly one meaning in this app, and it is not "the previous screen": every
     * mode is entered from the calculator, so a history stack would only ever be able to
     * disagree with the user's expectation.
     */
    fun backToCalculator() {
        navigateTo(Route.Calculator)
    }

    /** The token this state writes into the saved-instance bundle. */
    fun saveToken(): String = _route.value.id

    companion object {
        /**
         * Rebuilds a state holder from a token previously produced by [saveToken].
         *
         * @param routeId the saved token, or null.
         * @return a state showing that route, or the calculator when the token names a mode
         *   this build no longer has — a bundle written by an earlier version outlives the
         *   upgrade that removed the mode, and restoring must not crash.
         */
        fun restoreFrom(routeId: String?): CalculatorAppState =
            CalculatorAppState(Route.fromId(routeId) ?: Route.Calculator)

        /**
         * Persists the route across activity recreation and process death.
         *
         * A plain `remember` here is what threw the user off their current mode on every
         * rotation, system dark-mode toggle, per-app language change and split-screen
         * resize: the activity declares no `configChanges`, so it is destroyed and rebuilt,
         * and the route simply started over. [Route.id] is already a stable persisted token,
         * so a String is all the bundle needs to carry.
         */
        val Saver: Saver<CalculatorAppState, String> = Saver(
            save = { state -> state.saveToken() },
            restore = { token -> restoreFrom(token) },
        )
    }
}

/**
 * Remembers a [CalculatorAppState] across recomposition, recreation and process death.
 *
 * @param initialRoute the cold-start destination. Ignored when a saved route is restored:
 *   the activity's launch intent describes where the user *entered*, not where they are.
 * @return the state holder for this composition.
 */
@Composable
fun rememberCalculatorAppState(initialRoute: Route): CalculatorAppState =
    rememberSaveable(saver = CalculatorAppState.Saver) { CalculatorAppState(initialRoute) }

/**
 * The whole app below the theme: route state plus the destination currently rendered.
 *
 * @param initialRoute where to open on a cold start. Defaults to [Route.Calculator] because
 *   a preloaded calculator that opens into, say, the loan screen is a calculator the user
 *   cannot trust to open as one. Only an explicit launcher shortcut overrides this.
 * @param routeRequests shortcut taps that arrive while the app is already running. A stream
 *   of events rather than a state value, so that tapping the same shortcut twice navigates
 *   twice — the second tap of "Unit converter" carries the identical [Route], and as a value
 *   it would be indistinguishable from no request at all.
 */
@Composable
fun CalculatorApp(
    initialRoute: Route = Route.Calculator,
    routeRequests: Flow<Route> = emptyFlow(),
) {
    val state: CalculatorAppState = rememberCalculatorAppState(initialRoute)

    LaunchedEffect(routeRequests, state) {
        routeRequests.collect { requested -> state.navigateTo(requested) }
    }

    val route: Route by state.route.collectAsStateWithLifecycle()

    // Without this every screen's rememberSaveable state is discarded the moment another
    // mode replaces it in the `when` below, so leaving the financial or date calculator for
    // the converter and coming straight back clears everything the user had typed. The
    // converter, programmer and graphing screens keep their state in activity-scoped view
    // models and would otherwise be the only three modes that behave.
    val screenState: SaveableStateHolder = rememberSaveableStateHolder()
    screenState.SaveableStateProvider(route.id) {
        when (route) {
            Route.Calculator -> CalculatorScreen(onOpenMode = state::navigateTo)
            Route.Converter -> ConverterScreen(onBack = state::backToCalculator)
            Route.Programmer -> ProgrammerScreen(onBack = state::backToCalculator)
            Route.Graphing -> GraphingScreen(onBack = state::backToCalculator)
            Route.DateTime -> DateTimeScreen(onBack = state::backToCalculator)
            Route.Financial -> FinancialScreen(onBack = state::backToCalculator)
            Route.Settings -> SettingsScreen(onBack = state::backToCalculator)
        }
    }
}
