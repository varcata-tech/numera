# Play Console — listing copy and form answers

Everything the Console asks for, worked out in advance. Copy from here rather than
improvising in the form, because several of these answers are commitments you have to keep
true as the app changes.

---

## Store listing

**App name** (30 char limit — this is 24)

```
Numera: Exact Calculator
```

Three names are in play and the difference is deliberate, so do not "tidy" one into another:
the **store listing** is `Numera: Exact Calculator`, because a listing has to say what the app
is to someone who has never heard of it; the **launcher label** (`app_name`) is
`Numera Calculator`, translated per locale, because a home screen needs the brand *and* the
common noun; the **project name** is Numera. Changing the launcher label means editing
`res/values/strings.xml` and all eleven `res/values-<locale>/strings.xml`.

**Short description** (80 char limit — this is 69)

```
The calculator where 1÷3×3 is exactly 1. Offline, no ads, no tracking.
```

**Full description** (4000 char limit)

```
Most calculators quietly lie to you. Ask one for 1 ÷ 3 × 3 and it says 0.9999999999999998.
Ask for √2 × √2 and it says 2.0000000000000004. Ask for 0.1 + 0.2 and it says
0.30000000000000004.

Numera says 1, 2, and 0.3 — because it does not compute with floating-point approximations.
It works with exact rational numbers and constructive reals, keeping values symbolic until
the moment they are displayed. π stays π. √2 stays √2. Nothing is rounded until it has to be.

EXACT ARITHMETIC
• 1 ÷ 3 × 3 = 1, exactly
• √2 × √2 = 2, exactly
• sin(30°) = 0.5 exactly, and tan(90°) is reported as undefined rather than 1.6e16
• 100! computed in full, all 158 digits
• Scroll any result sideways for more digits — 1 ÷ 7 is correct past a thousand places

SEVEN CALCULATORS IN ONE
• Scientific — trigonometry, logarithms, powers, roots, factorials, degrees and radians
• Unit converter — 16 categories and 180+ units, with exact conversion factors, so an
  inch → centimetre → inch round trip returns precisely what you typed
• Programmer — hex, decimal, octal and binary at once, a tappable bit grid, 8/16/32/64-bit
  words, signed and unsigned, bitwise operations, shifts and rotates
• Graphing — plot up to four functions, pinch to zoom, trace, and find roots
• Date calculator — days between dates, add or subtract durations, business days, age
• Financial — loans with a full amortisation schedule, compound interest, tips, discounts
  and tax

BUILT TO RESPECT YOU
• No internet permission, and no permission that grants access to your data, your sensors
  or your files — so Numera is incapable of sending anything anywhere. This is enforced by
  Android, not by our promises.
• No ads. No tracking. No analytics. No accounts.
• No third-party code beyond general-purpose Apache-2.0 libraries: Google's AndroidX, the
  Kotlin standard library and kotlinx-coroutines. No SDK of any other kind.
• Your calculation history stays on your device and is deliberately excluded from cloud
  backup.

THOUGHTFUL DETAILS
• History that stores the calculation, not its answer — tap an old entry and continue from
  the exact value, not a rounded decimal
• Copy a result and paste it back with no loss of precision
• Come back to a half-typed expression exactly where you left it, even after Android has
  reclaimed the app in the background
• Material You theming, a true-black OLED mode, and per-app language on Android 13+
• Full TalkBack support, including reading extra digits of a long result
• Twelve languages, with locale-correct digits, grouping and decimal separators

Numera is free, and it always will be. There is nothing to buy and nothing to subscribe to.
```

**Category:** Tools
**Tags:** Calculator, Unit converter, Productivity
**Contact email:** `gurpreet@varcata.com`

Play shows this address publicly on the listing. It is the same address the privacy policy
gives, and that is not an accident — a reviewer who finds two different contact addresses
treats the policy as boilerplate. If it is ever swapped for a dedicated alias, change it in
all four places at once: here, `docs/privacy-policy.md`, `docs/index.md` and
`app/src/main/assets/privacy-policy.txt`.

**Privacy policy URL:** `https://varcata-tech.github.io/numera/privacy-policy/`

Built from `docs/privacy-policy.md`, whose front matter pins that exact permalink, and served
under the `baseurl` set in `docs/_config.yml`. Play **fetches and validates this URL during
review**, so open it in a private window and confirm it returns the styled policy page — not a
404, and not the raw Markdown source — before pasting it into the Console. Both halves of the
URL are derived from the GitHub organisation name: if the repository is not `varcata-tech/numera`,
`docs/_config.yml`, this line and the two contact sections are all wrong together.

---

## Data Safety form

The whole form collapses to one answer, but Play still walks you through it.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all data encrypted in transit? | *(not asked once you answer No)* |
| Do you provide a way to request data deletion? | *(not asked once you answer No)* |

**Why "No" is correct, and not a shortcut.** Play defines *collection* as transmitting data
off the device. Numera stores history and settings in private app storage and has no
`INTERNET` permission, so nothing is transmitted at all. Local-only storage is explicitly not
collection under Play's definition.

**This answer must be revisited if the app ever gains a network permission or an SDK.** An
inaccurate Data Safety declaration is grounds for removal, and it is the single most common
way an otherwise honest app gets pulled.

---

## Content rating (IARC questionnaire)

| Question | Answer |
|---|---|
| Category | Utility, Productivity, Communication or Other |
| Violence, sexuality, profanity, controlled substances, gambling | None |
| User-generated content or user interaction | None |
| Shares location | No |
| Digital purchases | No |

Expected outcome: **Everyone / PEGI 3 / USK 0.**

---

## Target audience and content

Choose **13+ or older**. A calculator is obviously safe for any age, but ticking an age band
under 13 opts the app into the **Families policy**, which brings additional requirements
around ads, SDKs and content review. There is no benefit here in exchange for that overhead.

**Ads declaration:** No, this app does not contain ads.

**Financial features:** None to declare. The financial *calculators* compute arithmetic; they
do not facilitate lending, investment or payments, so the Financial Services policy does not
apply. The in-app disclaimer ("For information only. Not financial advice") makes that plain
to users as well as to a reviewer.

---

## App content declarations

| Declaration | Answer |
|---|---|
| Government app | No |
| Financial features | None |
| Health apps | No |
| News app | No |
| COVID-19 tracing | No |
| Data safety | No data collected |
| Advertising ID | Not used — do **not** declare it, the permission is absent |

---

## Release checklist

- [ ] Create the developer account, pay the one-off $25, complete identity verification.
- [x] Publish `docs/` to GitHub Pages (Settings → Pages → branch `master`, folder `/docs`);
      confirm `https://varcata-tech.github.io/numera/privacy-policy/` loads publicly, styled,
      in a browser with no session for the repository. The repository is public for this
      reason: a free organisation cannot serve Pages from a private one.
- [ ] Create the app in the Console; enrol in **Play App Signing**.
- [ ] Upload `app/build/outputs/bundle/release/app-release.aab` — **the bundle, not the
      APK**. No `archivesName` is configured, so that is the literal filename AGP writes.
      **Nothing in `dist/` is uploadable.** That directory is gitignored scratch space and its
      contents are whatever was last built by hand, which is not necessarily this commit; a
      friendlier filename there has already been mistaken for the artifact once. Upload only
      out of `app/build/outputs/`, and only after `bundleRelease` has run on the commit you
      are shipping.
- [ ] Complete: store listing, Data Safety, content rating, target audience, app content.
- [ ] Add screenshots: at least 2 phone screenshots, plus a 512×512 icon and a 1024×500
      feature graphic. All of these are in `art/play-listing/`: eight phone screenshots
      captured from the release build on the Pixel 8 emulator and cropped to 1080×2160 —
      Play refuses anything taller than 2:1, and the raw 1080×2400 capture is 2.22:1 —
      plus `icon_512.png` and `feature_1024x500.png` rendered from the launcher vector.
      Recapture the screenshots whenever a screen they show changes.
- [ ] Start a **closed test** and recruit **12 testers**.
- [ ] Keep them opted in for **14 continuous days**. Updates during the window do not reset
      the clock, so improvements can ship while it runs.
- [ ] Apply for production access; review typically takes under 7 days.

## Things to check before every upload

```sh
./gradlew :math:test :units:test :app:testDebugUnitTest
./gradlew :app:lintDebug          # MissingTranslation is fatal; HardcodedText cannot see Compose
./gradlew :app:bundleRelease

# Check what is actually going up. bundleRelease writes an .aab and never produces an APK, so
# aiming aapt2 at app/build/outputs/apk/ either fails outright or — worse — silently reports
# on a stale APK left behind by an earlier assembleRelease. A bundle stores its manifest as
# protobuf rather than binary XML, so read it with bundletool, not aapt2:
bundletool dump manifest --bundle=app/build/outputs/bundle/release/app-release.aab \
  | grep -i permission

# Expect no INTERNET, and only two lines — a <permission> declaration and the matching
# <uses-permission> — both naming the same signature-level
# app.numera.calculator.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION that androidx.core adds for
# its own broadcast receivers (see PRIVACY.md). Anything else is a regression and the four
# privacy documents have to be revisited before uploading.
#
# Without bundletool installed, build the APK as well and use aapt2 on the real path:
#   ./gradlew :app:assembleRelease
#   aapt2 dump permissions app/build/outputs/apk/release/app-release.apk

# Confirm no language splits. app/build.gradle.kts sets bundle { language { enableSplit =
# false } } so all twelve locales ride in the base APK; if that block is ever lost, Play
# installs only the splits matching the device's system languages while
# res/xml/locales_config.xml keeps offering all twelve to the per-app language picker, and
# picking an uninstalled one silently renders the app in English.
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=/tmp/numera.apks --overwrite
unzip -l /tmp/numera.apks | grep split_config

# Expect ABI and density splits only. A single split_config.<lang>.apk line is the regression.
```

Bump `versionCode` in `app/build.gradle.kts` for every upload — Play rejects a repeat.
`versionName` is the string users see and can lag behind.
