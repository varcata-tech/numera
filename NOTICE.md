# Third-party origins — Numera

This app contains no third-party dependencies at runtime — it builds against androidx only.
The notice below concerns *derived algorithms*, not bundled code.

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

This is a **derivative work, not a clean-room implementation**. It was written with
reference to the published algorithms and to the original Java. It has been restructured,
renamed and re-documented, and any defects in it are ours.

The Apache License 2.0 text is in `LICENSES/apache-2.0.txt`, and ships in the app as
`app/src/main/assets/apache-2.0.txt` (Settings → About). It is the licence of the AOSP
ExactCalculator sources named above. This notice deliberately makes no licence claim about
the `com.hp.creals` library: no creals code is bundled, and the licence on the specific
sources that were consulted has not been established from within this repository. **Confirm
it, and check in its text alongside `LICENSES/apache-2.0.txt`, before relying on the
derivation in anything published.** **This file is the authoritative attribution.** The
`about_attribution` string in
`app/src/main/res/values*/strings_common.xml` and the closing paragraph of
`docs/privacy-policy.md` and `app/src/main/assets/privacy-policy.txt` must not say more than
it does.

## Google Calculator

The user-facing behaviour is modelled on Google Calculator. No Google code, resources or
branding are used. Key glyphs, operator symbols and accessibility descriptions follow the
conventions established by AOSP's Calculator, which is Apache-2.0 licensed as above.
