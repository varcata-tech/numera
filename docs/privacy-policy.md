# Privacy Policy for Numera

**Last updated: 22 August 2026**

Numera is a calculator. It collects nothing, sends nothing, and has no way to reach the
internet.

## The short version

- **No data is collected.** Not by us, not by anyone else.
- **No data is shared or sold.** There is nothing to share.
- **No accounts, no sign-in, no identifiers.** Numera does not know who you are.
- **No analytics, no advertising, no tracking, no crash-reporting SDK.**
- **No permissions that can reach your data.** Numera holds no permission granting access to
  any data, sensor or device capability — no internet, no storage, no camera, no location,
  no contacts.

## Why we can promise that

Numera does not hold the `INTERNET` permission. An Android app without that permission is
prevented by the operating system itself from making a network connection — this is enforced
by the platform, not by our good intentions. You can verify it yourself: on the Play Store
listing, under *App info*, Numera lists no permission that requests access to anything.

For completeness, one entry does exist in the app's manifest, and we would rather explain it
than have you find it and wonder. It is called
`app.numera.calculator.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Numera does not request it
from you and it grants nobody anything: it is a `signature`-level permission that the app
defines for itself, added automatically by Google's AndroidX libraries, and its only purpose
is to stop *other* apps from sending messages to Numera's internal components. It cannot be
held by any app not signed with the same key. There is no user-facing capability behind it.

Numera also contains no third-party libraries beyond Google's own AndroidX components. There
is no advertising SDK, no analytics SDK, and no crash-reporting SDK embedded in the app.

## What is stored on your device

Two things, both in Numera's private app storage, which no other app can read:

| What | Where | Leaves your device? |
|---|---|---|
| Your calculation history | A private database inside the app | **No** |
| Your settings (theme, angle mode, haptics) | A private preferences file | Only if *you* enable Android Backup |

**Calculation history is explicitly excluded from Android's cloud backup.** Your settings may
be included in a device backup if you have Android Backup switched on, because settings are
small and restoring them is convenient. Your calculations are not, because what you calculate
is nobody's business but yours.

You can erase your history at any time from the history panel. Uninstalling Numera, or
clearing its data in Android Settings, removes everything.

## Children

Numera is suitable for all ages. Because it collects no data whatsoever, it collects no data
from children either.

## Changes to this policy

If this policy ever changes, the revised version will be published at this address and the
date at the top will be updated. Since Numera collects nothing, we do not expect the substance
to change.

## Contact

Questions about this policy can be raised as an issue on the project's repository.

---

*Numera includes an arithmetic engine derived from the Android Open Source Project's
ExactCalculator, which is licensed under the Apache License 2.0, and from the published
algorithms of Hans-J. Boehm's constructive reals library. No third-party code is bundled.
The Apache License 2.0 text is available inside the app under Settings → About, and the full
attribution is in `NOTICE.md` in the project's repository.*
