import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Czysty Kotlin/JVM (bez android.*): połączenie TLS ze sterownikiem, keepalive, reconnect, tester połączenia.
// Ten sam kod może działać w aplikacji Android i jako serwis na JVM (np. Raspberry Pi).
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
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Wspólne wektory testowe (Python / Kotlin) leżą poza android/: <repo>/testdata
val sharedTestData = rootProject.layout.projectDirectory.dir("../testdata")

tasks.test {
    useJUnitPlatform()
    systemProperty("domik.testdata", sharedTestData.asFile.canonicalPath)
    inputs.dir(sharedTestData)
        .withPropertyName("sharedTestData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
