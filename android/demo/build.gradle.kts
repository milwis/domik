import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Czysty Kotlin/JVM (bez android.*): tryb demo. `FakeController` udaje sterownik Deimic w pamięci – odpowiada
// ramkami protokołu przez fałszywy transport (`InMemoryTransport`), więc aplikacja w trybie demo korzysta z tych
// samych ścieżek co z prawdziwym sterownikiem (Connection, DeviceCommands, ConfigStore). Fikcyjny dom – bez żadnych
// danych prawdziwego domu.
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

    // Testy sprawdzają też, że ConfigStore / HomeConfigParser czytają syntetyczną bazę fikcyjnego domu.
    testImplementation(project(":deimic-config"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
