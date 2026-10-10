import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Czysty Kotlin/JVM (bez android.*): baza konfiguracji sterownika (SQLite, polecenie 0x212) -> model urządzeń domu.
// Zawiera własny, minimalny czytnik plików SQLite (tylko odczyt) – dzięki temu działa tak samo na JVM i na Androidzie
// i nie wymaga żadnych zewnętrznych bibliotek.
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

// Wspólne dane testowe (Python / Kotlin) leżą poza android/: <repo>/testdata
val sharedTestData = rootProject.layout.projectDirectory.dir("../testdata")

// Opcjonalny test lokalny na prawdziwej bazie sterownika: -Pdomik.realDatabase=<ścieżka> (plik poza gitem;
// ścieżka względna liczona od katalogu android/).
val realDatabase: String? = providers.gradleProperty("domik.realDatabase").orNull
    ?.let { rootProject.file(it).absolutePath }

tasks.test {
    useJUnitPlatform()
    systemProperty("domik.testdata", sharedTestData.asFile.canonicalPath)
    realDatabase?.let { systemProperty("domik.realDatabase", it) }
    inputs.dir(sharedTestData)
        .withPropertyName("sharedTestData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Plik poza projektem – Gradle musi wiedzieć, że zmiana ścieżki zmienia wynik testów.
    inputs.property("realDatabase", realDatabase ?: "")
}
