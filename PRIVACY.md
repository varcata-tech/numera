# Privacy — Numera

Numera collects nothing, stores nothing off-device, and cannot reach the network.

The canonical, user-facing policy is `docs/privacy-policy.md`, published to GitHub Pages and
linked from the Play listing. This file is the engineering summary and must not contradict it.

- **No permission that grants access to any data, sensor or device capability.** In
  particular there is no `INTERNET` permission, so the app is incapable of making a network
  request even if a future dependency tried to.
- `app/src/main/AndroidManifest.xml` declares no permission at all, but manifest merging adds
  one and the packaged manifest is what a reviewer inspects. `androidx.core` contributes
  `app.numera.calculator.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a
  `protectionLevel="signature"` permission the app defines for itself so that
  `ContextCompat.registerReceiver` can restrict its own runtime broadcast receivers to this
  app. It grants nothing to anyone and carries no privacy implication. **Do not strip it with
  `tools:node="remove"`** — androidx relies on it, and the wording here is deliberately the
  claim that survives `aapt2 dump permissions` on the uploaded artifact.
- **No analytics, no ads, no crash SDK, no third-party libraries.** AndroidX only.
- **No accounts, no identifiers.** Nothing distinguishes one install from another.

## What is stored on the device

| What | Where | In cloud backup? |
|---|---|---|
| Calculation history | `numera_history.db`, a private SQLite database | **No** — excluded by omission |
| Settings | `calculator_settings.xml`, private SharedPreferences | Yes |

`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` are **include-only** and
name the preferences file alone. History is therefore excluded from Android Backup by
omission, deliberately: settings are trivial and convenient to restore, whereas what someone
calculates is not something to put on Google's servers on their behalf.

**This is why history lives in its own database rather than in `calculator_settings`.**
Adding history keys to that preferences file would silently start backing calculations up and
would make both this document and the published policy false.

Both stores are removed when the app's data is cleared or the app is uninstalled. History can
be cleared from inside the app at any time.
