import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Narzędzie wiersza poleceń (JVM) do testu :deimic-client i :deimic-config na żywym sterowniku – WYŁĄCZNIE odczyty.
// Nie jest częścią aplikacji Android. Uruchomienie: ./gradlew :tools-probe:run --args="--help"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":deimic-client"))
    implementation(project(":deimic-config"))
}

application {
    mainClass.set("app.domik.tools.probe.ProbeKt")
    applicationName = "domik-probe"
}

// Katalog roboczy = katalog główny repozytorium: tam domyślnie szukany jest plik konfiguracji domik-probe.local.json.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.parentFile
}
