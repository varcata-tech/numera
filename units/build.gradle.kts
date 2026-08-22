import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Depends on :math so conversion factors are exact rationals rather than doubles —
// an inch is 127/5000 m exactly, and a round trip through it has to come back unchanged.
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
    implementation(project(":math"))
    testImplementation(libs.junit)
}
