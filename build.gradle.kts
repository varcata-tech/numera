// No build logic at the root: every module declares what it needs, and the version
// catalog is the single place a version is written down.
// org.jetbrains.kotlin.android is deliberately absent, from here and from the version
// catalog: AGP 9 compiles Kotlin itself, and applying that plugin on top is a hard error
// whose message does not say so. Declaring the alias here — even with `apply false` — put it
// one deleted word away from being applied and advertised it as the supported way to add
// Kotlin to a new Android module. An Android module gets Kotlin from android-application
// alone; :math and :units use kotlin-jvm.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
