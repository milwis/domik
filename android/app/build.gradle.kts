import java.util.Properties
import javax.inject.Inject

// AGP 9: wbudowana obsługa Kotlina – bez wtyczki org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun loadProperties(name: String): Properties? {
    val file = rootProject.file(name)
    if (!file.isFile) return null
    return Properties().apply { file.inputStream().use { load(it) } }
}

val localProperties = loadProperties("local.properties")
// Opcjonalny podpis release – android/keystore.properties (poza gitem). Brak pliku = release niepodpisany.
val keystoreProperties = loadProperties("keystore.properties")

// Pełne teksty prawne w aplikacji (offline): przy buildzie kopiujemy 4 dokumenty z katalogu legal/ (jedyne źródło
// prawdy, żadnych duplikatów w repozytorium) do wygenerowanego katalogu assets (assets/legal/…). Makiety ekranów
// (legal/ekrany-aplikacji.md, legal/en/app-screens.md) NIE są kopiowane. Lista musi się zgadzać z LegalDocument
// (pilnuje tego LegalDocumentsTest).
abstract class CopyLegalAssets @Inject constructor(private val fileSystem: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val legalDir: DirectoryProperty

    @get:Input
    abstract val files: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val source = legalDir.get().asFile
        val missing = files.get().filterNot { source.resolve(it).isFile }
        if (missing.isNotEmpty()) throw GradleException("Brak dokumentów prawnych w ${source.path}: $missing")
        fileSystem.sync {
            from(source) { include(files.get()) }
            into(outputDir.get().dir("legal"))
        }
    }
}

val copyLegalAssets = tasks.register<CopyLegalAssets>("copyLegalAssets") {
    // android/ jest korzeniem projektu Gradle, legal/ leży obok (w repozytorium publicznym tak samo).
    legalDir.set(rootProject.layout.projectDirectory.dir("../legal"))
    files.set(listOf("regulamin.md", "polityka-prywatnosci.md", "en/terms.md", "en/privacy-policy.md"))
    outputDir.set(layout.buildDirectory.dir("generated/legalAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalAssets, CopyLegalAssets::outputDir)
    }
}

android {
    namespace = "app.domik"
    compileSdk = 37

    defaultConfig {
        applicationId = localProperties?.getProperty("domik.applicationId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "app.domik"
        minSdk = 26
        // targetSdk 37 (Android 17) wymusza uprawnienie runtime ACCESS_LOCAL_NETWORK dla połączeń TCP w LAN –
        // na razie zostajemy na 36, dopóki aplikacja nie obsługuje tego uprawnienia.
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Testy jednostkowe (JVM) w JUnit Jupiter – jak w pozostałych modułach.
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(project(":deimic-protocol"))
    implementation(project(":deimic-client"))
    implementation(project(":deimic-config"))
    implementation(project(":demo"))
    implementation(project(":thermo"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
