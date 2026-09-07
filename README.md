# Numera

An exact-arithmetic calculator for Android 16, built as a preloaded first-party app.
Package `app.numera.calculator` (debug variant `app.numera.calculator.debug`).

Unlike a floating-point calculator, this one computes with exact rationals and constructive
reals, so `1÷3×3` is `1`, `√2×√2` is `2`, and `1÷7` can be scrolled past a thousand correct
digits.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd /Users/gurpreetsingh/dev/numera

./gradlew :math:test :units:test :app:testDebugUnitTest   # the whole unit-test suite
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease                              # the release artifact — see "Signing"
./gradlew :app:lintDebug
```

**What ships is the AAB from `bundleRelease`, never an APK.** Play accepts only a bundle for a
new app, and `docs/play-listing.md` is written around that. `assembleRelease` still exists and
is still signed, but it is for putting a release build on a phone by hand — a release APK left
in `app/build/outputs/apk/release/` is exactly the stale file someone later uploads by mistake.

Always use `./gradlew`, never the system `gradle` — the latter runs on a JDK that AGP rejects.

Toolchain: AGP 9.3.1, Kotlin 2.4.10, Compose BOM 2026.08.00, Gradle 9.7.1, JDK 21.
`compileSdk 37`, `targetSdk 36`, `minSdk 31`.

Note that AGP 9 supplies Kotlin itself — the `org.jetbrains.kotlin.android` plugin must
**not** be applied, and `jvmTarget` is set through the top-level `kotlin { compilerOptions }`
block rather than `android { kotlinOptions }`.

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:math` | pure Kotlin JVM | `BoundedRational`, `ConstructiveReal`, `UnifiedReal`, expression parser, evaluator, result formatter |
| `:units` | pure Kotlin JVM | 16-category unit catalogue with exact conversion factors |
| `:app` | Android | all Compose UI, view models, theme, navigation |

The two engine modules are plain JVM libraries on purpose: their tests run in milliseconds
with no device and no Robolectric, and the compiler makes it an error for the engine to
reach for an Android API.

## Installing on a phone

Over USB, with developer options and USB debugging enabled:

```sh
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices -l                                  # authorise the prompt on the phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No `archivesName` is configured, so `app-debug.apk` is the literal name AGP writes;
`assembleRelease` likewise writes `app/build/outputs/apk/release/app-release.apk`, and
`bundleRelease` writes `app/build/outputs/bundle/release/app-release.aab` — the second of
those is the one that goes to Play.

Or copy the APK across and tap it — the file manager needs "install unknown apps" enabled.
The app appears as **Numera Calculator** — `app_name`, which *is* translated: every locale
keeps the brand and localises the noun, so a German home screen reads "Numera Rechner" and a
Japanese one "Numera 電卓". The Play listing name is longer (`Numera: Exact Calculator`) and
is set in the Console, not here; `docs/play-listing.md` records why the two differ.

## Signing (Play App Signing)

`keystore.jks` is the **upload key** for Google Play App Signing, alias `numera-upload`.
Both it and `keystore.properties` are gitignored.

Under Play App Signing, this key signs only what you *upload*; Google holds the app signing
key and re-signs what users install. That asymmetry matters: an upload key can be reset by
Play support if you lose it, whereas losing an app signing key would strand the app forever.

The password is written into `keystore.properties` because that file never leaves this
machine. Change it before sharing the repository with anyone.

The build **fails loudly rather than emitting an unsigned artifact**, so a missing or
mistyped property is an error rather than a silently unsigned APK. The guard runs twice: once
at configuration time on the task names as typed, and again as `packageRelease` /
`packageReleaseBundle` starts. The second pass is what catches an abbreviation such as
`./gradlew :app:bR`, which Gradle expands to `bundleRelease` only after the first check has
already seen the string `bR`.

## Localisation

Twelve locales ship: `en ar de es fr hi it ja ko pt-BR ru zh-CN`, declared in
`res/xml/locales_config.xml` so Android 13+ offers a per-app language. Every translatable
string is present in every locale — `MissingTranslation` is a fatal lint check, so the
release build cannot be produced otherwise. No string count is quoted here on purpose: a
hand-maintained figure goes stale on the next feature and then misleads, whereas
`lintDebug` is checked by the build and cannot.

Unit names were translated as part of this; a native reader should still review them before
a wide release, since terms like *Ångström* and *Cup (US)* have conventions no dictionary
settles.

Key glyphs (`÷`, `×`, `√`, `π`, `sin`) are marked `translatable="false"` — they are
notation, not prose. Digits are not string resources at all: they come from
`DecimalFormatSymbols`, read through `LocalConfiguration` so the pad re-renders if the user
switches app language at runtime. An Arabic locale shows `٧`, not `7`.

## Things to try on the phone

These demonstrate what the exact engine buys, and each one is wrong on an ordinary calculator:

| Type this | Expect |
|---|---|
| `1 ÷ 3 × 3 =` | `1` — not `0.9999999999999998` |
| `√2 × √2 =` | `2` |
| `0.1 + 0.2 =` | `0.3` |
| `1 ÷ 7 =` then drag the result left | correct digits past 1000 |
| `1 ÷ 4 =` | `0.25` with **no** trailing ellipsis |
| `sin 30 =` in DEG | `0.5` exactly |
| `tan 90 =` in DEG | "Can't divide by 0", not `1.633e16` |
| `100 ! =` | the exact 158-digit integer |
| `100 + 10 % =` | `110` (percent is relative after `+`) |
| `10 ^ 10 ^ 10 =` | "Requires too much memory", app stays responsive |
| Converter: 1 inch → cm → inch | returns exactly `1` |
| Converter: 180 degree → radian | `3.14159265358979…`, scrollable |
| Drag down on the display | History opens; drag sideways scrolls the formula instead |
| Tap a history row, then `×3 =` | Exact — inserts the calculation, not its decimal |
| Long-press the result → Copy, paste back, `×3 =` | Exactly `1` |
| Type `sin(2`, switch away until Android reclaims the app, reopen | The half-typed expression is still there |

Other modes are reached from the `⋮` menu on the calculator screen.

## Third-party origins

See `NOTICE.md`, which is the authoritative statement and the one to keep the shipped
`about_attribution` string in step with. The arithmetic engine is a Kotlin reimplementation
derived from AOSP's ExactCalculator (Apache-2.0) and from the published algorithms of
Hans-J. Boehm's constructive reals library. Nothing is bundled beyond androidx, the Kotlin
standard library and kotlinx-coroutines — all Apache-2.0, all general-purpose, and none of
them an SDK for anything. There is no analytics, advertising or crash-reporting code.

## Privacy

See `PRIVACY.md`. No permission that grants access to any data, sensor or capability — in
particular no `INTERNET`, so nothing can leave the device. The packaged manifest does carry
one `signature`-level permission that `androidx.core` defines for its own broadcast
receivers; `PRIVACY.md` explains why it is there and why it must not be stripped.
