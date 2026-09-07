# Numera

An exact-arithmetic calculator for Android. `1÷3×3` is `1`, `√2×√2` is `2`, and `1÷7` scrolls
past a thousand correct digits. Everything else in this file exists to keep that true.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :math:test :units:test :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease        # AAB for Play — not an APK
./gradlew :app:lintDebug
```

**Gradle needs the network sandbox disabled.** DNS for Maven Central is blocked in the default
sandbox and Gradle dies with `UnknownHostException`. Run every `./gradlew` invocation with
`dangerouslyDisableSandbox: true`.

**Keep this project out of `~/Desktop` and `~/Documents`.** Both are iCloud-synced on this
machine. iCloud races Gradle's build output and leaves conflict copies named `foo 2.xml`,
which then fail AGP's resource-filename validation with an error that looks nothing like its
cause. The project lives in `~/dev/numera` for that reason.

**Always `./gradlew`, never the system `gradle`** — the latter launches on a JDK that AGP rejects.

## Emulator

There is no `androidTest` source set: the emulator is for the things a JVM test cannot see —
RTL mirroring, locale digits, drawer gestures, and whether the licence screen survived R8 in a
*release* build. `scripts/emu.sh` wraps it.

```sh
./scripts/emu.sh start        # boot numera-api36 (Pixel 8, API 36, arm64, google_apis)
./scripts/emu.sh run          # boot if needed, :app:installDebug, launch
./scripts/emu.sh shot out.png # screencap
./scripts/emu.sh lang ar-EG   # per-app locale; no argument resets to the system language
./scripts/emu.sh fresh        # pm clear, to test genuine first-run state
./scripts/emu.sh log          # crashes and app lines out of logcat
```

**The installed package is `app.numera.calculator.debug`, not `app.numera.calculator`.** The
debug build sets `applicationIdSuffix`, so launching the unsuffixed id fails with the
misleading "No activities found to run, monkey aborted" — which reads like a broken manifest
rather than a wrong package name.

**Every adb call must be pinned to our serial.** Other projects on this machine run their own
AVDs, and an unpinned `adb`/`installDebug` either refuses with "more than one device/emulator"
or installs onto whichever device answers first. `emu.sh` resolves the serial by matching
`adb -s <serial> emu avd name` against the AVD name; do not "simplify" that to bare `adb`.

**`avdmanager create avd` writes placeholders that must be corrected.** It leaves
`avd.id`/`avd.name` as the literal `<build>` and, worse, `disk.dataPartition.path=<temp>` —
which throws away `shared_prefs` and the history database on every boot, quietly making
persistence untestable. It also defaults to `hw.gpu.enabled=no`. The checked-in AVD already
has these fixed; recreate it and you must fix them again. The `Could not load devices from
.../devices.xml` error it prints is harmless — the `-d` hardware profile is still applied.

## Traps

These are the things that have actually cost time here. Read them before editing.

**AGP 9 supplies Kotlin itself.** Applying `org.jetbrains.kotlin.android` is a hard error, not
a redundancy. `jvmTarget` goes in the top-level `kotlin { compilerOptions { } }` block;
`android { kotlinOptions { } }` no longer exists.

**`BigInteger.signum` and `.bitLength` need parentheses in Kotlin.** Without them Kotlin
resolves to `java.math.BigInteger`'s package-private *fields* and fails with a confusing
"cannot access" error. Always write `.signum()` and `.bitLength()`.

**`BoundedRational` arithmetic returns a nullable, and `null` is not an error.** It means "this
would cost more to keep exact than it is worth — fall through to `ConstructiveReal`". Callers
must handle it as a change of representation, never as a failure.

**`AbortedException` must propagate.** It is thrown from deep inside an approximation loop when
the user presses another key. A `catch (e: ArithmeticException)` that swallows it turns a
cancelled computation into a wrong answer.

**`CalculatorExpr.fromText()` cannot round-trip an incomplete expression.** It runs the parser
and returns `null` on any exception, so `1+`, `sin(2` and `(3×` all fail. For persistence use
`ExprCodec`, which is token-level and handles partial input. `display()`/`fromText()` is a
lossless form only for *valid* expressions.

**Every `HistoryStore` method is `suspend` and dispatches to `Dispatchers.IO` itself.** Do
not "simplify" that away. The drawer's first load and the clear-history confirmation both run
from ordinary UI coroutines, which default to the main dispatcher — leaving the dispatch to
callers puts a disk read on the frame thread.

**Read the locale through `LocalConfiguration`, never `Locale.getDefault()`, inside a
composable *or in anything a composable reads from*.** The latter is invisible to Compose, so
switching the app language at runtime would leave the keypad rendering the old locale's digits
while the answer above it keeps the old ones. A retained `ViewModel` is the same trap one step
removed: it outlives the configuration change a per-app language switch causes, so a locale it
captured is stale by definition. Pass the locale down from the composable that reads
`LocalConfiguration`. **Nothing enforces this but review** — there is no lint module in this
build, and any comment claiming a `NonObservableLocale` check is wrong.

**History must never live in `calculator_settings`.** `backup_rules.xml` and
`data_extraction_rules.xml` are include-only and list that prefs file alone, deliberately, so
that settings sync to the cloud and calculations do not. Adding history keys there would
silently start backing users' calculations up to Google and contradict our own privacy policy.

**Anything asked to run for an unbounded time goes through `runInterruptible`.** Plain
`withContext` returns on cancellation while the worker thread keeps a core busy forever;
thread interruption is what the engine's `CalculationLimits.checkNotAborted()` actually detects.

**Every locale ships in the base APK — `bundle { language { enableSplit = false } }`.** With
language splits on (the bundletool default), Play installs only the splits matching the
device's *system* languages, while `res/xml/locales_config.xml` offers all twelve to the
Android 13+ per-app language picker. Picking one whose split was never installed falls back to
English with no error anywhere, and the app cannot fetch it: `SplitInstallManager` lives in
`com.google.android.play:feature-delivery`, which is not androidx.

**Result scrolling must grow geometrically.** Asking for a fixed increment more digits makes
scrolling quadratic and it visibly dies around three hundred digits. Request
`max(needed + 30, current * 2)`.

## House rules

- **No third-party dependencies.** androidx only — no Hilt, no Koin, no Room, no Retrofit, no
  analytics, no ad SDK. `androidx.test` in `androidTest` is fine; it is androidx. What the APK
  actually carries is androidx *plus* the Kotlin standard library and kotlinx-coroutines,
  which arrive with the toolchain and transitively through lifecycle — all Apache-2.0. The
  shipped wording (`about_attribution`, `docs/privacy-policy.md`,
  `assets/privacy-policy.txt`, `NOTICE.md`, README) names those three by hand, so a claim of
  "androidx only" in user-facing text is a bug: check the APK's `META-INF/*.version` entries
  before writing one.
- **No permissions in `AndroidManifest.xml`.** Especially not `INTERNET`. The app computes
  entirely on device, and "collects nothing" is a feature we advertise. Adding one requires
  updating the Play Data Safety form and the privacy policy. Note the *merged* manifest is
  not empty: `androidx.core` injects a `signature`-level
  `app.numera.calculator.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` for its own broadcast
  receivers, which is why the shipped wording is "no permission granting access to any data,
  sensor or capability" rather than "no permissions at all". Do not remove it — see
  `PRIVACY.md`.
- **Compose + Material3 only.** No XML layouts; XML is for `values/`, `xml/` and drawables.
- **MVVM with StateFlow.** `stateIn(viewModelScope, WhileSubscribed(5000), initial)`, collected
  with `collectAsStateWithLifecycle()`. Composables are stateless and take hoisted lambdas.
- **No DI framework.** View models are built by hand-rolled `viewModelFactory { initializer { } }`.
- **Plain `SharedPreferences`**, not DataStore. Framework `SQLiteOpenHelper`, not Room — Room
  would drag in KSP for one table.
- Persist enums by **name**, read back with `enumValues<T>().firstOrNull { it.name == stored }`.
  Never `valueOf`, which throws on a renamed constant and crashes on downgrade.
- Serialise `KeyId` with **explicit integer tags in a `when`**, never `ordinal`. Reordering the
  enum later must not silently corrupt saved history.

## Modules

| Module | Kind | Rule |
|---|---|---|
| `:math` | pure Kotlin JVM | exact arithmetic, parser, formatter. **Zero Android imports.** |
| `:units` | pure Kotlin JVM | unit catalogue. Depends on `:math`. **Zero Android imports.** |
| `:app` | Android | all Compose UI, view models, persistence |

The two engine modules are plain JVM libraries deliberately: their tests run in milliseconds
with no device and no Robolectric, and the compiler makes it a *compile error* for the engine
to reach for an Android API. Keep it that way — put pure logic for a feature in its own
Android-free file under `:app` if it does not belong in `:math`.

## Resources

- **`MissingTranslation` and `HardcodedText` are fatal lint checks.** A release build will not
  produce until every shipped locale has every string. Twelve locales ship
  (`en ar de es fr hi it ja ko pt-BR ru zh-CN`), so **any new string needs 11 translations**
  before `bundleRelease` will run. `/tmp/l10n.py`-style generation is fine; the per-locale
  files are split per feature exactly like the base ones.
- **Each feature owns its own `strings_<feature>.xml`** in `values/`. Do not edit
  `values/strings.xml` for feature strings — Android merges every file in the directory, and
  per-feature files are what stop parallel edits from colliding.
- Math glyphs (`÷ × − √ π sin`) are notation, not prose: mark them `translatable="false"`.
- **Digits are not string resources.** They come from `DecimalFormatSymbols` at runtime so an
  Arabic locale renders `٧`. Never hardcode `"7"` as a key label.
- Content descriptions must be *spoken* forms — "divided by", not "÷".

## Testing

JUnit 4, backticked sentence names, in `src/test/kotlin`. KDoc on the test class explaining
*why* the cases matter.

Test the thing that would silently be wrong, not the thing that would crash. The engine tests
compare against published expansions of π, e, √2 and ln 2 to a hundred places precisely because
a scaling bug returns plausible wrong digits far to the right where no eyeball would find them.

Pure state machines (`DrawerState`, `BitwiseEngine`, `Viewport`) are extracted from Compose
specifically so they can be tested without a device. Prefer moving logic out of a composable
over writing a UI test for it.

## Comments

Comment the **failure mode a piece of code prevents**, not what the code does. `// Reduce by π,
not 2π — reducing by 2π leaves anything just past π still outside the range and the two calls
recurse against each other until the stack runs out` earns its place. `// loop over the tokens`
does not. KDoc every public type.

## Play Store

Published as **Numera**, `app.numera.calculator`, free with no ads and no tracking.
Upload an **AAB** (`./gradlew :app:bundleRelease`), not an APK, and upload it out of
`app/build/outputs/bundle/release/` — never out of `dist/`, which is gitignored scratch space
holding whatever was last built by hand. `keystore.jks` is the Play App Signing *upload* key,
alias `numera-upload`. Listing copy and the Data Safety answers are in
`docs/play-listing.md`.

- The store listing must not mention Google Calculator, and the icon must not resemble it —
  Play's impersonation policy covers icons, titles and in-app elements that mislead users about
  a relationship to another app.
- The engine is a Kotlin derivative of AOSP's ExactCalculator, which is Apache-2.0, and of the
  algorithms Hans-J. Boehm published for `com.hp.creals`. No creals source is copied or
  bundled, which is why `LICENSES/` holds only `apache-2.0.txt`; copy from that library rather
  than from the paper and you must check its own notice in first. `NOTICE.md` and
  `LICENSES/apache-2.0.txt` must ship, and the in-app licence screen must survive R8 — check
  it in the *release* build, since a resource-shrink bug hides exactly there.
- The privacy policy must be reachable both in-app and from the Console, and must stay accurate.
- targetSdk 36 is mandatory from 31 August 2026.
