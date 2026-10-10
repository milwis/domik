import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Czysty Kotlin/JVM (bez android.*): automatyzacje domu – „Auto-wyłączanie” (reguły + silnik z licznikami).
// Ten sam kod działa w aplikacji Android (tablet ścienny) i jako serwis na JVM (np. Raspberry Pi).
// Nie ma własnego I/O sieciowego: steruje sterownikiem przez interfejsy (ControllerEvents, OutputSwitcher),
// a gotowe adaptery do :deimic-client leżą w ConnectionAdapters.kt.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":deimic-protocol"))
    api(project(":deimic-client"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
