import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Deliberately a plain JVM library, not an Android one. The exact-arithmetic engine is
// the riskiest code in the project and carries the most tests; keeping it off the Android
// classpath makes those tests run in milliseconds without a device or Robolectric, and
// makes it a compile error for the engine to reach for an Android API.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
