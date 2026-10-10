import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Czysty Kotlin/JVM – bez Androida i bez sieci. Kod bajtowy JVM 17 (kompilacja dowolnym JDK >= 17).
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
