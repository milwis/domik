import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Czysty Kotlin/JVM (bez android.*, bez sieci i BLE): termostaty pokojowe – dekoder reklam BLE termometrów
// Xiaomi (pvvx/ATC1441) i CalibrationCorrector (kalibracja czujnika Deimic, 0x131/0x132, wg notes/THERMOSTATS.md §3).
// Skanowanie BLE, wysyłkę do sterownika i trwały zapis kalibracji bazowej robi warstwa aplikacji; ten moduł tylko liczy.
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
    // Tylko rekord kalibracji wejścia (InputCalibration) – jest w publicznym API modułu, stąd api.
    api(project(":deimic-protocol"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
