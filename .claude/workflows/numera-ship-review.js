export const meta = {
  name: 'numera-ship-review',
  description: 'Adversarially review every subsystem of Numera, verify the findings, fix the confirmed ones, and prove the build is still green',
  phases: [
    { title: 'Review', detail: 'eight reviewers, one per subsystem' },
    { title: 'Verify', detail: 'fresh eyes try to refute each finding' },
    { title: 'Fix', detail: 'one fixer per subsystem, disjoint files' },
    { title: 'Integrate', detail: 'build, test, lint until green' },
  ],
}

// ---------------------------------------------------------------------------
const CONTEXT = `
# Numera — an exact-arithmetic calculator for Android

Root: /Users/gurpreetsingh/dev/numera  (NOT ~/Desktop — that path is stale)
Package app.numera.calculator. Modules :math and :units are pure Kotlin JVM; :app is Android.
About 11,000 lines of Kotlin, 176 passing unit tests, lint clean, release AAB builds.

The app is about to be installed on a real phone and submitted to Google Play. It has NEVER
been run — not on a device, not on an emulator. So static review is the only safety net that
exists right now. Assume nothing has been observed working.

## What it must do

Seven surfaces: calculator (basic + scientific), unit converter, programmer, graphing,
date calculator, financial calculators, settings. Plus persistent history, process-death
restore, and precision-preserving copy/paste.

## The invariants that make it worth shipping

Do NOT "fix" these into ordinary floating-point behaviour — they are the entire point:
- 1÷3×3 is exactly 1; √2×√2 is exactly 2; 0.1+0.2 is exactly 0.3; √2+√8 is exactly 3√2
- sin(30°) is exactly 1/2; sin(π rad) is exactly 0; tan(90°) is a divide-by-zero ERROR,
  never 1.633e16
- log(0.001) is exactly -3; e^ln(5) is exactly 5; 2^10 is exactly 1024; π÷π is exactly 1
- 1÷7 scrolls to 1000+ correct digits; 1÷4 shows "0.25" with NO ellipsis
- Unit conversions are exact rationals: inch→cm→inch returns the identical value
- History stores the EXPRESSION, never the answer, so tapping a row keeps full precision
- The app holds NO permissions, especially not INTERNET

## Non-negotiable house rules

- androidx only. No Hilt/Koin/Room/Retrofit/analytics/ads. androidx.test in androidTest is fine.
- Compose + Material3 only; no XML layouts. MVVM with StateFlow, collected via
  collectAsStateWithLifecycle(). No DI framework — hand-rolled viewModelFactory.
- Plain SharedPreferences, framework SQLiteOpenHelper (not Room).
- :math and :units must contain ZERO Android imports.
- Persist enums by name via enumValues<T>().firstOrNull { it.name == stored }, never valueOf.
- Serialise KeyId with explicit integer tags, never ordinal.
- MissingTranslation and HardcodedText are FATAL lint checks. 12 locales ship:
  en ar de es fr hi it ja ko pt-BR ru zh-CN. Any NEW user-visible string needs an entry in
  res/values/strings_<feature>.xml AND in all 11 values-<locale>/strings_<feature>.xml files,
  or the release build will not produce.
- Comments must explain the failure mode a piece of code prevents, not restate the code.

## Known traps in this codebase

- java.math.BigInteger: Kotlin resolves .signum / .bitLength to package-private FIELDS.
  You must write .signum() and .bitLength() with parentheses.
- AGP 9 supplies Kotlin itself; applying org.jetbrains.kotlin.android is a hard error.
- BoundedRational arithmetic returns a NULLABLE, and null means "too big to stay exact,
  fall through to ConstructiveReal". It is NOT an error condition.
- AbortedException is thrown from inside approximation loops when the user presses another
  key. Swallowing it in a broad catch turns a cancelled computation into a WRONG ANSWER.
- Long-running work must go through runInterruptible, not plain withContext: thread
  interruption is what CalculationLimits.checkNotAborted() actually detects.
- Result scrolling must grow digit requests geometrically or it becomes quadratic.
- Reading Locale.getDefault() inside a composable is a lint error (NonObservableLocale);
  use LocalConfiguration.
- HistoryStore methods are suspend and dispatch their own IO on purpose.
- CalculatorExpr.fromText() runs the parser and returns null for an in-progress expression
  like "1+", which is why ExprCodec exists for persistence.

## Build commands

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :math:test :units:test :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:bundleRelease

IMPORTANT: every ./gradlew invocation needs dangerouslyDisableSandbox set to true, because
the sandbox blocks DNS for Maven Central. Always ./gradlew, never the system gradle.
`

const NO_BUILD = `
# DO NOT RUN GRADLE, DO NOT EDIT ANY FILE

Several agents are working at once. Concurrent Gradle invocations fight over the daemon and
the build directory and corrupt each other, and concurrent edits corrupt each other's work.
You are READ-ONLY: use Read, Grep, Glob and shell commands that do not write.

You therefore cannot execute the code. Reason from the source. Where you are unsure whether
something actually misbehaves, say so honestly rather than asserting it — a later phase will
try to refute every finding, and confident-but-wrong findings waste that effort.
`

const FINDING_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          category: { type: 'string' },
          title: { type: 'string' },
          detail: { type: 'string' },
          failureScenario: { type: 'string' },
          suggestedFix: { type: 'string' },
        },
        required: ['id', 'file', 'severity', 'title', 'detail', 'failureScenario', 'suggestedFix'],
        additionalProperties: false,
      },
    },
    areaSummary: { type: 'string' },
  },
  required: ['findings', 'areaSummary'],
  additionalProperties: false,
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          title: { type: 'string' },
          detail: { type: 'string' },
          failureScenario: { type: 'string' },
          suggestedFix: { type: 'string' },
          verdict: { type: 'string', enum: ['CONFIRMED', 'REFUTED', 'UNCERTAIN'] },
          verifierNote: { type: 'string' },
        },
        required: ['id', 'file', 'severity', 'title', 'detail', 'suggestedFix', 'verdict', 'verifierNote'],
        additionalProperties: false,
      },
    },
    missedByReviewer: { type: 'array', items: { type: 'string' } },
  },
  required: ['findings'],
  additionalProperties: false,
}

// ---------------------------------------------------------------------------
const AREAS = [
  {
    key: 'engine',
    label: 'review:exact-arithmetic-engine',
    effort: 'high',
    scope: `math/src/main/kotlin/app/numera/calculator/math/*.kt — BoundedRational, ConstructiveReal,
ConstructiveRealOps, Factor, UnifiedReal, CalculationLimits. Also their tests in
math/src/test/kotlin/app/numera/calculator/math/.`,
    focus: `This is the code the whole product rests on, and a defect here produces a WRONG ANSWER
that looks perfectly plausible. Check with real rigour:

- The scaling arithmetic in every ConstructiveRealOps subclass. An off-by-one in a shift
  count does not crash; it silently returns wrong digits far to the right of the point.
  Re-derive the precision each operator needs from its operands and check the code matches.
- Argument reduction in exp, ln, sin, cos, atan, asin, acos. Verify each reduction actually
  terminates for extreme inputs (very large, very small, negative, near-asymptote) and that
  the reduced argument really is inside the series' valid range.
- UnifiedReal's Factor algebra: does every combination rule preserve the value? Look hard at
  sqrt normalisation, Exp/Log cancellation, the Pi power arithmetic, and the exact
  degree/radian trig tables. Are there argument values where a "simplification" is wrong?
- The three runaway guards: cooperative abort, precision overflow, memory bound. Is there a
  loop anywhere that can spin without hitting a checkNotAborted()?
- Division: is every divisor proven non-zero before use, or can something hang trying to
  decide? Are there paths that throw a raw ArithmeticException instead of the typed ones?
- Integer overflow in Int precision arithmetic (shift counts, msd, exponents).
- BigInteger .signum / .bitLength written without parentheses anywhere.
- Are the existing tests actually testing the hard cases, or only the easy ones? Name
  specific untested behaviours that could plausibly be wrong.`,
  },
  {
    key: 'expr',
    label: 'review:expression-layer',
    effort: 'high',
    scope: `math/src/main/kotlin/app/numera/calculator/math/expr/*.kt — KeyId, Token, CalculatorExpr,
ExprParser, ExprEvaluator, ExprCodec. And math/.../format/ResultFormatter.kt. Plus their tests.`,
    focus: `- Parser precedence and associativity: -2^2 must be -4, 2^3^2 must be 512. Check unary
  minus, implicit multiplication, factorial and percent binding against the documented grammar.
- The contextual percent rule: 100+10% is 110, but 50% alone is 0.5. Is the "relative"
  detection correct for nested and chained cases like 100+10%+5% or 2*50%?
- appendSmartParen and deleteLastToken: can they produce a token stream the parser rejects,
  or lose a token silently? What about deleting into the middle of a number?
- ExprEvaluator.evaluate claims never to throw. Verify EVERY path. Look for exceptions that
  escape the catch list, including StackOverflowError from deep recursion and anything the
  formatter could throw.
- ExprCodec: is the encoding genuinely unambiguous? Can a crafted or truncated byte array
  cause an exception, an enormous allocation, or an infinite loop? Is the length byte read
  as unsigned everywhere?
- ResultFormatter: the ellipsis must appear if and ONLY IF digits were actually dropped.
  Check the exact-vs-truncated decision, the scientific-notation thresholds, negative
  exponents, values very close to a power of ten, and zero. Check locale grouping uses the
  locale's group SIZE rather than a hardcoded 3, and that the minus sign is U+2212.
- Does formatWithDigits behave correctly for digits = 0 and for very large digit counts?`,
  },
  {
    key: 'calc',
    label: 'review:calculator-history-clipboard',
    effort: 'high',
    scope: `app/src/main/kotlin/app/numera/calculator/feature/calc/**, feature/history/**, data/**.`,
    focus: `This is the screen the user spends all their time in, and it has never been run.

- CalculatorViewModel state machine: walk every transition between INPUT, RESULT and ERROR.
  After equals, does pressing an operator continue from the EXACT value and a digit start
  fresh? Does backspace out of a result behave? Can any sequence leave the display and the
  internal expression disagreeing?
- Process-death restore: does restore() reconstruct everything the user could see? What
  happens if the saved expression decodes but the saved result expression does not? Is
  persist() called on EVERY state change, or are there paths that mutate and forget?
- Job management: previewJob and evaluateJob. Can a stale evaluation land after a newer one
  and overwrite the display with an old answer? Check the ordering carefully.
- Incremental digits: verify the growth really is geometric and that digitsShown cannot get
  out of sync with what is displayed.
- HistoryStore: SQL correctness, cursor column indices matching the projection, the trim
  query, transaction safety, and whether a decode failure can lose more than one row.
- The clipboard: does the two-item ClipData survive a round trip? What if another app puts a
  single-item clip on the clipboard with a matching label? Is getItemAt(1) guarded?
- The drawer: DrawerState settle logic against the composable that drives it. Does the drag
  actually arbitrate against the formula's horizontal scroll, or will one starve the other?
  What happens if the drawer is open and the device rotates?
- Compose correctness: unstable lambdas causing recomposition storms, remember keys, state
  hoisting, work done in composition that should be in a side effect.`,
  },
  {
    key: 'units',
    label: 'review:converter-and-units',
    effort: 'high',
    scope: `units/src/main/kotlin/app/numera/calculator/units/** and its tests, plus
app/src/main/kotlin/app/numera/calculator/feature/converter/**.`,
    focus: `- Verify the actual NUMERIC VALUE of every conversion factor against the real definition.
  This is a correctness audit, not a style review. Specifically re-derive: inch = 25.4mm,
  pound = 0.45359237kg, atmosphere = 101325Pa, calorie = 4.184J, BTU = 1055.05585262J,
  electronvolt (SI 2019), mmHg = 133.322387415Pa, nautical mile = 1852m, metric hp =
  735.49875W, mechanical hp = 550 ft·lbf/s, pound-force, poundal, slug, US gallon = 231in³,
  imperial gallon = 0.00454609m³, the Gregorian mean month and year, and the parsec.
  Report any that are wrong, and any that are conventions presented as if exact.
- Temperature is affine. Verify -40°C = -40°F, 0K = -273.15°C, and the Rankine and Réaumur
  scales. Check the reverse direction too.
- Fuel economy is INVERSE. Verify km/L to L/100km both ways, and that mpg US vs imperial are
  not swapped.
- Angles carry π symbolically. Does 180° actually produce exactly π rather than a decimal?
- Does any conversion divide by a user-supplied zero without guarding? Reciprocal units are
  undefined at zero.
- The UI: is the active/inactive field logic right? Does swapping preserve meaning? Does the
  "common conversions" strip pick sensible units, and can it be expensive?
- UnitNames must use direct R.string constants — a name-based lookup would let R8 strip all
  182 unit names from the RELEASE build only.
- Cross-check that every unit id in the catalogue has both a name and a symbol string, and
  that no string exists for a unit that was removed.`,
  },
  {
    key: 'modes',
    label: 'review:programmer-dates-financial',
    effort: 'high',
    scope: `app/src/main/kotlin/app/numera/calculator/feature/programmer/**, feature/dates/**,
feature/financial/** and their tests.`,
    focus: `PROGRAMMER — the emulated machine word must behave like hardware, not like a JVM Long:
- Sign extension and masking for 8/16/32/64-bit, signed and unsigned.
- Overflow detection, especially at exactly 64 bits where the JVM has already wrapped.
- Arithmetic vs logical right shift on negative values; rotate wraparound; byte swap.
- Division and remainder by zero; Long.MIN_VALUE / -1 which overflows in two's complement.
- parse() rejecting out-of-range and out-of-base input; base round-tripping.
- The ViewModel: does the entry buffer stay consistent with the value when the base or word
  size changes mid-entry? Is toInt() on a shift amount safe for huge values?

DATES — java.time, minSdk 31 so no desugaring:
- Month/year clamping (31 Jan + 1 month), leap years including 1900 and 2000, the
  36524-day check, business days with holidays landing on weekends, age on a leap-day birth.
- Any place a LocalDate question is answered with a time zone involved.

FINANCIAL — BigDecimal, and rounding is part of the domain:
- The amortisation schedule's principal column must sum to EXACTLY the principal, and the
  final balance must be exactly zero. Verify the final-instalment adjustment really achieves
  that and cannot go negative.
- Zero rate must not divide by zero. Very high rates and very long terms.
- Continuous compounding must use exp, not a large frequency. Check the Double round trip
  through BigDecimal for precision loss.
- Tip remainder accounting; successive discounts composing not adding; tax add/remove
  round-tripping exactly.
- Any unguarded BigDecimal.divide that could throw ArithmeticException for a non-terminating
  decimal expansion.
- The UI: every screen parses user text. Can any input crash it — empty, minus, multiple
  dots, enormous numbers, zero people?`,
  },
  {
    key: 'graph',
    label: 'review:graphing',
    scope: `app/src/main/kotlin/app/numera/calculator/feature/graphing/** and its tests.`,
    focus: `- Viewport transforms: round-tripping, panning direction, zoom about a focal point, the
  span clamps, and squared axes. Any division by a zero pixel dimension before layout?
- GraphSampler: does breaksBetween actually prevent the false vertical line through 1/x and
  tan(x)? Is the threshold sensible at extreme zoom levels?
- RootFinder: bisection correctness, the pole-rejection heuristic (can it reject a REAL
  root?), the iteration cap, and behaviour when f is discontinuous or returns NaN.
- Intersections between functions.
- Performance: is resampling genuinely off the main thread and debounced? Does a pinch
  gesture avoid resampling per frame? Is anything O(n²) in the number of samples?
- The trace: does it handle no functions, NaN results, and taps outside the viewport?
- Compose: is the Canvas doing work in the draw phase that should be cached? Is
  onCanvasResized called from the draw scope, and does that cause a recomposition loop?
  That last one is a serious risk — check it specifically.`,
  },
  {
    key: 'platform',
    label: 'review:platform-lifecycle-a11y',
    effort: 'high',
    scope: `app/src/main/kotlin/app/numera/calculator/MainActivity.kt, nav/**, ui/**, settings/**,
feature/settings/**, app/src/main/AndroidManifest.xml, app/src/main/res/xml/**.`,
    focus: `- Threading: find EVERY disk or database access that could land on the main thread. Check
  SettingsStore's constructor and setters, the assets reads in SettingsScreen, and anything
  in a composable body or a LaunchedEffect that defaults to Dispatchers.Main.
- Lifecycle: leaks from a retained Context, a ViewModel outliving its scope, coroutines that
  escape viewModelScope, or a composition local holding an Activity.
- Configuration changes and process death across ALL screens, not just the calculator.
  Rotating on the converter, programmer, graph, dates and financial screens — what is lost?
  Is rememberSaveable used where it should be?
- Predictive back at targetSdk 36 in ModeScaffold: is the handler correct, and does back
  from a mode really reach the calculator?
- The launcher shortcuts: do the intent extras and the targetPackage match the real
  applicationId? Note the debug build appends .debug — will shortcuts break there?
- Accessibility, carefully: every key needs a SPOKEN description; the result needs a live
  region; disabled keys need a stateDescription; touch targets at least 48dp; the app must
  survive a 2.0 font scale without clipping.
- RTL: the keypad must NOT mirror in Arabic, but the chrome must. Verify the LTR pinning is
  applied at the right level and does not leak into places that should mirror.
- Theme: the four-way ThemeMode, the OLED variant, dynamic colour gating, and the
  status-bar icon polarity. Are the hand-written colour pairs actually contrast-compliant?
- Manifest: exported components, backup rules correctness, and that no permission has crept in.`,
  },
  {
    key: 'release',
    label: 'review:build-release-compliance',
    effort: 'high',
    scope: `build.gradle.kts, settings.gradle.kts, app/build.gradle.kts, gradle/libs.versions.toml,
app/proguard-rules.pro, gradle.properties, app/src/main/res/values*/**, docs/**, README.md,
CLAUDE.md, NOTICE.md, PRIVACY.md.`,
    focus: `- R8 and resource shrinking: is anything reachable only by reflection or by name that the
  release build would strip? The 182 unit-name resources are the obvious risk. Are the
  proguard rules adequate, or overly broad and hiding a real problem?
- The signing block: is it genuinely impossible to emit an unsigned release? Is the
  configuration-cache-safe task-name check still correct?
- Localisation audit — do this properly, it is mechanical and important:
  For EVERY translatable string in res/values/strings*.xml, confirm an entry exists in all 11
  values-<locale> directories. Then confirm every format specifier (%1$s, %1$d, %%) appears
  identically in every translation — a mismatched specifier is a guaranteed runtime crash in
  that locale, and lint does not always catch it. Report any locale file with a string the
  base file does not have.
- Are any strings that should be translatable marked translatable="false", or vice versa?
- Play compliance: no INTERNET permission, no ads SDK, no analytics; the privacy policy in
  docs/ must match what the app actually does; NOTICE.md must correctly attribute the
  Apache-2.0 derivation; nothing in the store listing copy may mislead about a relationship
  to another app.
- Version catalog hygiene, unused dependencies, and whether any dependency is third-party
  rather than androidx.
- versionCode/versionName readiness for a first upload.`,
  },
]

// ---------------------------------------------------------------------------
phase('Review')

const reviewed = await pipeline(
  AREAS,
  (area) =>
    agent(
      CONTEXT + NO_BUILD + `
# YOUR TASK: review one subsystem, adversarially

## Scope
${area.scope}

## What to look for
${area.focus}

## How to report

Read the actual source. Do not skim and do not guess. For every finding give a concrete
FAILURE SCENARIO: specific inputs or a specific sequence of user actions, and the wrong
behaviour that results. "This could be a problem" is not a finding; "entering X then Y
displays Z, which is wrong because W" is.

Severity means:
- critical: wrong answers, data loss, a crash, or a Play-policy violation
- high: a feature does not work as intended in a case users will hit
- medium: a real defect in an edge case, or a significant performance problem
- low: robustness, clarity, or a latent hazard

Report ONLY defects. Do not report stylistic preferences, and do not propose rewriting
working code. Explicitly do not flag the exactness invariants listed above as bugs — they
are deliberate. If a piece of code looks odd but is correct, leave it alone.

If the subsystem is genuinely sound, returning few or zero findings is the right answer and
is more useful than padding the list.
`,
      { label: area.label, phase: 'Review', effort: area.effort, schema: FINDING_SCHEMA },
    ).then((r) => ({ area, review: r })),

  ({ area, review }) =>
    agent(
      CONTEXT + NO_BUILD + `
# YOUR TASK: try to REFUTE another reviewer's findings

A reviewer examined this scope:
${area.scope}

They reported these findings. You did not write them and have no stake in them being right.

${JSON.stringify(review?.findings ?? [], null, 2)}

For each one, go to the source and decide honestly:

- CONFIRMED — you reproduced the reasoning and the defect is real. Say what convinced you.
- REFUTED — the code actually handles this. Say exactly where and how. Be specific; "looks
  fine" is not a refutation.
- UNCERTAIN — genuinely cannot tell without running it.

**Default to REFUTED when you are unsure whether a defect is real**, EXCEPT where the
consequence would be a wrong numeric answer, a crash, or a Play-policy violation — for those,
prefer UNCERTAIN over REFUTED, because the cost of missing one is far higher than the cost of
checking it again.

Watch for these specific false positives, which reviewers of this codebase produce often:
- A nullable BoundedRational treated as an error. It means "fall through to
  ConstructiveReal" and is normal.
- The exactness invariants listed above reported as floating-point bugs.
- Code that looks unreachable but is reached through the Compose recomposition path.
- A "missing null check" on something the type system already guarantees.

Also add anything the reviewer MISSED that you noticed while reading — put those in
missedByReviewer as short descriptions. Only include real defects.

Carry each finding through with its original id, file, line, severity, title, detail and
suggestedFix so the fix phase has everything it needs.
`,
      { label: `verify:${area.key}`, phase: 'Verify', effort: 'high', schema: VERDICT_SCHEMA },
    ).then((v) => ({ area, verified: v })),
)

const byArea = new Map()
let confirmedCount = 0
let refutedCount = 0
for (const entry of reviewed.filter(Boolean)) {
  const list = (entry.verified?.findings ?? []).filter((f) => {
    if (f.verdict === 'REFUTED') { refutedCount++; return false }
    return true
  })
  confirmedCount += list.length
  const missed = entry.verified?.missedByReviewer ?? []
  if (list.length || missed.length) byArea.set(entry.area.key, { area: entry.area, findings: list, missed })
}
log(`verified: ${confirmedCount} findings survive, ${refutedCount} refuted, across ${byArea.size} subsystems`)

// ---------------------------------------------------------------------------
phase('Fix')

const targets = Array.from(byArea.values())
const fixes = targets.length === 0 ? [] : await parallel(
  targets.map(({ area, findings, missed }) => () =>
    agent(
      CONTEXT + `
# DO NOT RUN GRADLE

Other fixers are editing other files at the same time, and concurrent Gradle invocations
corrupt each other. Write your changes; a later integration phase compiles everything and
fixes any fallout. Because you cannot compile, write conservative Kotlin: explicit types,
explicit imports, and only APIs you have confirmed exist by reading the source.

# YOUR TASK: fix the confirmed defects in ONE subsystem

You own ONLY these paths and must not write outside them:
${area.scope}

(Exception: if a fix genuinely needs a NEW user-visible string, add it to
res/values/strings_<yourfeature>.xml AND to all 11 values-<locale>/strings_<yourfeature>.xml
files. Missing any locale breaks the release build. Translate properly — do not paste English
into every locale.)

## Findings to fix

${JSON.stringify(findings, null, 2)}

## Additional issues a second reviewer noticed

${JSON.stringify(missed, null, 2)}

## How to fix

- Fix the CAUSE, not the symptom. A guard that hides a wrong calculation is worse than the
  original bug because it makes it invisible.
- Anything marked UNCERTAIN: investigate it properly. Fix it if it is real; if it is not,
  leave the code alone and say so in your report. Do not "fix" working code defensively.
- Preserve every exactness invariant. If a fix would trade exactness for simplicity, do not
  make it — report the trade-off instead.
- Add or extend a unit test for each behavioural fix, in the matching test source set, in the
  house style (JUnit 4, backticked sentence names, a KDoc explaining why the case matters).
  A fix without a test will regress.
- Match the surrounding code: KDoc on public types, and comments that state the failure mode
  prevented rather than restating the code.
- Do not refactor beyond what the fix needs. Do not reformat untouched code.

Report what you changed, what you deliberately did not change and why, and any finding you
concluded was not real.
`,
      { label: `fix:${area.key}`, phase: 'Fix', effort: 'high' },
    )
  ),
)
log(`fix agents completed: ${fixes.filter(Boolean).length}/${targets.length}`)

// ---------------------------------------------------------------------------
phase('Integrate')

const BUILD_SCHEMA = {
  type: 'object',
  properties: {
    buildsClean: { type: 'boolean' },
    testsPass: { type: 'boolean' },
    lintClean: { type: 'boolean' },
    releaseBundleBuilds: { type: 'boolean' },
    testCount: { type: 'integer' },
    remainingProblems: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
  },
  required: ['buildsClean', 'testsPass', 'lintClean', 'releaseBundleBuilds', 'summary'],
  additionalProperties: false,
}

let integration = null
let green = false
for (let round = 1; round <= 3 && !green; round++) {
  integration = await agent(
    CONTEXT + `
# YOUR TASK: make the whole project green again — round ${round} of at most 3

Several fixers just edited this project in parallel WITHOUT being able to compile. You are
the only agent running now. You own the build and every file.

Run these in order, with dangerouslyDisableSandbox true, and fix everything that fails:

1. ./gradlew :math:test
2. ./gradlew :units:test
3. ./gradlew :app:testDebugUnitTest
4. ./gradlew :app:lintDebug            (MissingTranslation and HardcodedText are FATAL)
5. ./gradlew :app:bundleRelease
6. ./gradlew :app:assembleRelease :app:assembleDebug

Expect: missing or wrong imports, signature drift between a caller and a callee two fixers
both touched, a nullable BoundedRational not handled, BigInteger .signum without parentheses,
duplicate string resource names across per-feature files, a new string missing from some
locales, and Compose invocations from a non-composable scope.

Rules while fixing:
- Preserve the intent of every fix. If two fixers conflict, keep the one that is correct and
  say which you dropped.
- Never weaken a test to make it pass. If a test now fails, decide whether the test or the
  code is wrong, fix the right one, and say which.
- Never weaken the exactness invariants.
- Do not silence lint with a baseline or a suppression. Fix the underlying issue.
${round > 1 ? '\nA previous round did not finish. Read the current build output and continue from where it now fails.' : ''}

Report honestly. Set each boolean true only if that command actually succeeded.
`,
    { label: `integrate:round-${round}`, phase: 'Integrate', effort: 'xhigh', schema: BUILD_SCHEMA },
  )
  green = Boolean(
    integration && integration.buildsClean && integration.testsPass &&
    integration.lintClean && integration.releaseBundleBuilds,
  )
  log(`integration round ${round}: ${green ? 'ALL GREEN' : 'still failing'}`)
}

return {
  reviewedAreas: reviewed.filter(Boolean).length,
  confirmed: confirmedCount,
  refuted: refutedCount,
  fixedAreas: targets.map((t) => t.area.key),
  perArea: Array.from(byArea.values()).map(({ area, findings }) => ({
    area: area.key,
    count: findings.length,
    critical: findings.filter((f) => f.severity === 'critical').length,
    high: findings.filter((f) => f.severity === 'high').length,
    titles: findings.map((f) => `[${f.severity}] ${f.title}`),
  })),
  integration,
  green,
}
