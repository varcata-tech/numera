package app.numera.calculator.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.numera.calculator.R
import app.numera.calculator.math.AngleMode
import app.numera.calculator.settings.AngleModeSetting
import app.numera.calculator.settings.LocalSettingsStore
import app.numera.calculator.settings.SettingsStore
import app.numera.calculator.ui.common.ModeScaffold
import app.numera.calculator.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The file the Apache-2.0 text is copied to at build time; read verbatim, never parsed. */
private const val LICENSE_ASSET: String = "apache-2.0.txt"

/** The privacy policy, bundled so it is readable without a network the app does not have. */
private const val PRIVACY_ASSET: String = "privacy-policy.txt"

/**
 * Appearance, calculation defaults, and attribution.
 *
 * @param onBack invoked to return to the calculator.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store: SettingsStore = LocalSettingsStore.current
    val themeMode: ThemeMode by store.themeMode.collectAsStateWithLifecycle()
    val dynamicColor: Boolean by store.dynamicColor.collectAsStateWithLifecycle()
    val haptics: Boolean by store.hapticsEnabled.collectAsStateWithLifecycle()
    val angleMode: AngleMode by store.angleMode.collectAsStateWithLifecycle()

    ModeScaffold(title = stringResource(R.string.title_settings), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))

            // selectableGroup is what gives the accessibility framework a collection to count:
            // without it TalkBack reads four unrelated radio buttons and never announces
            // "1 of 4", so a blind user cannot tell how many themes exist or where they are.
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    ChoiceRow(
                        label = stringResource(themeModeLabel(mode)),
                        selected = themeMode == mode,
                        onSelect = { store.setThemeMode(mode) },
                    )
                }
            }

            ToggleRow(
                title = stringResource(R.string.settings_dynamic_color),
                summary = stringResource(R.string.settings_dynamic_color_summary),
                checked = dynamicColor,
                onCheckedChange = store::setDynamicColor,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_feedback))

            ToggleRow(
                title = stringResource(R.string.settings_haptics),
                summary = stringResource(R.string.settings_haptics_summary),
                checked = haptics,
                onCheckedChange = store::setHaptics,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_calculation))

            Column(modifier = Modifier.selectableGroup()) {
                AngleModeSetting.entries.forEach { setting ->
                    ChoiceRow(
                        label = stringResource(angleModeLabel(setting)),
                        selected = AngleModeSetting.from(angleMode) == setting,
                        onSelect = { store.setAngleMode(setting.toAngleMode()) },
                    )
                }
            }

            HorizontalDivider()
            AboutSection()
        }
    }
}

/** @return the string resource naming [mode] in the theme picker. */
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.BLACK -> R.string.theme_black
}

/** @return the string resource naming [setting] in the angle-unit picker. */
private fun angleModeLabel(setting: AngleModeSetting): Int = when (setting) {
    AngleModeSetting.DEG -> R.string.angle_degrees
    AngleModeSetting.RAD -> R.string.angle_radians
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // selectable, not clickable: it puts the radio-group semantics on the whole row,
            // so TalkBack announces "selected" for the row rather than for a 20dp circle the
            // user has to find, and the tap target is the full width.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // toggleable, not clickable: clickable contributes a role and a click action but
            // no ToggleableState, so the merged node announced "switch" and never "on" or
            // "off" — a TalkBack user could not tell whether haptics were already enabled,
            // nor hear anything change when they double-tapped. This is the switch's
            // counterpart to ChoiceRow's selectable.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 64.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // null: the row above owns the click and, now, the ToggleableState, and a second
        // handler here would make TalkBack announce two separate toggles.
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun AboutSection() {
    val context: Context = LocalContext.current
    var licenseShown: Boolean by rememberSaveable { mutableStateOf(false) }
    var privacyShown: Boolean by rememberSaveable { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.settings_about))

    Text(
        text = stringResource(R.string.about_version, rememberVersionName(context)),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
    Text(
        text = stringResource(R.string.about_attribution),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )

    // Bundled rather than linked. Play requires the policy to be reachable inside the app,
    // and an app that holds no INTERNET permission cannot be the one to fetch it — sending
    // the user to a browser to read why we collect nothing would be a poor joke.
    TextButton(
        onClick = { privacyShown = !privacyShown },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(
                if (privacyShown) R.string.about_privacy_hide else R.string.about_privacy_show
            )
        )
    }

    if (privacyShown) {
        AssetText(
            asset = PRIVACY_ASSET,
            softWrap = true,
            loadingLabel = R.string.about_privacy_loading,
        )
    }

    TextButton(
        onClick = { licenseShown = !licenseShown },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(
                if (licenseShown) R.string.about_license_hide else R.string.about_license_show
            )
        )
    }

    if (licenseShown) {
        AssetText(
            asset = LICENSE_ASSET,
            softWrap = false,
            loadingLabel = R.string.about_license_loading,
        )
    }
}

/**
 * What [AssetText] has to draw.
 *
 * Three states rather than a nullable string, because "not read yet" and "cannot be read"
 * need different words on screen and the nullable form could not tell them apart.
 */
private sealed interface AssetState {

    /** The read is still in flight. */
    data object Loading : AssetState

    /** The asset was read. */
    data class Loaded(val text: String) : AssetState

    /** The asset is missing or unreadable, and no amount of waiting will change that. */
    data object Failed : AssetState
}

/**
 * Renders a bundled text asset.
 *
 * @param asset the file under `assets/` to show.
 * @param softWrap false for the licence, whose indentation is meaningful — re-wrapping it to
 *   the screen width makes the numbered clauses unreadable, so it scrolls sideways instead.
 *   True for prose like the privacy policy, which should wrap normally.
 * @param loadingLabel the string resource shown while the read is in flight. A parameter
 *   rather than a constant because this composable is shared: with the licence's wording
 *   hardcoded here, tapping "Privacy policy" put "Loading licence…" on screen underneath a
 *   button reading "Hide privacy policy".
 */
@Composable
private fun AssetText(asset: String, softWrap: Boolean, loadingLabel: Int) {
    val context: Context = LocalContext.current
    // Nine kilobytes off the asset manager is not free on the main thread, and a settings
    // screen that jank-stutters when a disclosure expands is the first thing a reviewer
    // notices. produceState keeps the read off the frame and cancels it if the user leaves.
    val state: AssetState by produceState<AssetState>(AssetState.Loading, context, asset) {
        value = withContext(Dispatchers.IO) {
            try {
                AssetState.Loaded(
                    context.assets.open(asset).bufferedReader().use { reader -> reader.readText() }
                )
            } catch (unreadable: IOException) {
                // Narrow, and it produces a state the user is told about. Folding every
                // failure into a null left the pending line on screen for good instead: a
                // build that had lost the privacy-policy asset would have answered Play's
                // in-app-policy requirement with a sentence saying it was still loading.
                AssetState.Failed
            }
        }
    }

    when (val current: AssetState = state) {
        AssetState.Loading -> AssetNotice(stringResource(loadingLabel))
        AssetState.Failed -> AssetNotice(stringResource(R.string.about_asset_error))
        // Both bundled documents are English, so the direction is pinned rather than
        // inherited: under Arabic the ambient RTL right-aligns the licence and parks its
        // horizontal scroll at the far end, opening it on the ends of its lines.
        is AssetState.Loaded -> CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr
        ) {
            if (softWrap) {
                Text(
                    text = current.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    text = current.text,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** One line of status where an asset's text would otherwise be. */
@Composable
private fun AssetNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

/**
 * Reads the app's version name.
 *
 * `getPackageInfo` is a synchronous binder round trip to `system_server`, not a local
 * lookup, so it is dispatched the same way [AssetText] dispatches its asset read rather than
 * run during composition: with `system_server` busy — mid package install, or during a Play
 * update sweep — a call on the frame thread delays the first frame of this screen by
 * however long the system takes to answer.
 *
 * @param context used for its package manager.
 * @return the version name; an empty string until the call returns, and also if the package
 *   cannot be read — which is possible during an in-place update, and is not worth crashing
 *   an About screen over.
 */
@Composable
private fun rememberVersionName(context: Context): String {
    val version: String? by produceState<String?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val manager: PackageManager = context.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.getPackageInfo(context.packageName, 0)
                }
                info.versionName
            }.getOrNull() ?: ""
        }
    }
    return version ?: ""
}
