# Third-party origins — Numera

## What is bundled

Three families of general-purpose library, all under the Apache License 2.0, and nothing else:

- **AndroidX** (Google) — Compose, Material 3, lifecycle, activity, core.
- **The Kotlin standard library** (JetBrains) — supplied by the toolchain; visible in the APK
  as `kotlin/*.kotlin_builtins`.
- **kotlinx-coroutines** (JetBrains) — arrives transitively with androidx.lifecycle; visible
  as `META-INF/kotlinx_coroutines_core.version` and `..._android.version`.

There is no advertising, analytics, crash-reporting or networking SDK of any kind, and no
library outside those three. `unzip -l` on the release APK is the check; anything else
appearing there means this file, `README.md`, `PRIVACY.md`, `docs/privacy-policy.md`,
`app/src/main/assets/privacy-policy.txt` and the `about_attribution` string are all out of
date together.

The rest of this notice concerns *derived algorithms*, which are a separate question from
bundled code.

## Constructive reals and exact arithmetic

`math/src/main/kotlin/app/numera/calculator/math/` — specifically `ConstructiveReal.kt`,
`ConstructiveRealOps.kt`, `BoundedRational.kt`, `Factor.kt` and `UnifiedReal.kt` — is a
Kotlin reimplementation informed by two sources:

- **Hans-J. Boehm's constructive reals library** (`com.hp.creals`), and the paper
  *"The Constructive Reals as a Java Library"*, Journal of Logic and Algebraic Programming,
  2005. The `approximate(precision)` contract, the memoised `getAppr` refinement, the
  argument-reduction strategies and the Taylor-series scaling arithmetic all follow it.
- **The Android Open Source Project's ExactCalculator**
  (`packages/apps/ExactCalculator`, Apache License 2.0), whose `BoundedRational` and
  `UnifiedReal` classes are the basis for the bounded-rational fallback and for the
  `rational × symbolic factor` representation.

This is a **derivative work, not a clean-room implementation**. It was written with the
published algorithms and the Apache-2.0 AOSP Java open beside it, not from a specification
handed to someone who had never seen them. It has been restructured, renamed and
re-documented, and any defects in it are ours.

The Apache License 2.0 text is in `LICENSES/apache-2.0.txt`, and ships in the app as
`app/src/main/assets/apache-2.0.txt` (Settings → About). It is the licence of the AOSP
ExactCalculator sources named above, and it is the only licence text this repository needs to
carry.

**Why there is no `com.hp.creals` licence in `LICENSES/`.** Not an oversight: no file, line or
identifier from that library is copied into this repository or bundled into the APK. What
carried over is the *design* — the `approximate(precision)` contract, the memoised `getAppr`
refinement, the argument reductions and the Taylor-series scaling — which is described in the
published paper cited above and implemented again, in Kotlin, against the Apache-2.0 AOSP
sources. This notice therefore makes no claim, either way, about the licence terms of the
`com.hp.creals` distribution itself, because nothing here depends on them.

**What would change that.** Copying from that library rather than from the paper or the AOSP
tree — even a single method body, table of constants or comment — makes its licence load
bearing. Establish the terms on the specific sources used and check the text in alongside
`LICENSES/apache-2.0.txt` in the same commit that introduces the code, not afterwards.

**This file is the authoritative attribution.** The `about_attribution` string in
`app/src/main/res/values*/strings_common.xml` and the closing paragraph of
`docs/privacy-policy.md` and `app/src/main/assets/privacy-policy.txt` must not say more than
it does.

## Google Calculator

The user-facing behaviour is modelled on Google Calculator. No Google code, resources or
branding are used. Key glyphs, operator symbols and accessibility descriptions follow the
conventions established by AOSP's Calculator, which is Apache-2.0 licensed as above.

This paragraph is deliberately *not* mirrored in the shipped `about_attribution` string. Play's
impersonation policy covers in-app elements that suggest a relationship to another app, and a
line inside the app naming Google Calculator does that even while denying it — the more so
because the protective half of the sentence ("no Google code, resources or branding are used")
existed only in English and could not be translated into being. The user-facing text now cites
AOSP's ExactCalculator, which is the actual derivation and the actual licence obligation, and
says the same thing in all twelve locales.
