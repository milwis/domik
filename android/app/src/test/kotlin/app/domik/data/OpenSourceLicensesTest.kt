package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class OpenSourceLicensesTest {
    private val items = OpenSourceLicenses.items

    @Test
    fun `kazdy skladnik wystepuje na liscie dokladnie raz`() {
        assertEquals(OpenSourceComponent.entries.toSet(), items.map { it.component }.toSet())
        assertEquals(OpenSourceComponent.entries.size, items.size)
    }

    @Test
    fun `biblioteki i ikony maja licencje Apache-2_0 a dane pogodowe CC BY 4_0`() {
        for (item in items) {
            val expected = if (item.component == OpenSourceComponent.OpenMeteo) LicenseKind.CcBy4 else LicenseKind.Apache2
            assertEquals(expected, item.license, item.component.name)
        }
    }

    @Test
    fun `atrybucja Open-Meteo prowadzi do serwisu a tylko Conscrypt i ikony maja dolaczona informacje o licencji`() {
        val weather = items.single { it.component == OpenSourceComponent.OpenMeteo }
        assertEquals(ProjectLinks.OPEN_METEO, weather.link)
        assertEquals(listOf(OpenSourceComponent.Conscrypt, OpenSourceComponent.MaterialIcons), items.filter { it.hasNoticeText }.map { it.component })
        assertNull(items.single { it.component == OpenSourceComponent.Kotlin }.link)
    }

    @Test
    fun `adresy licencji sa poprawnymi adresami https`() {
        for (kind in LicenseKind.entries) assertTrue(isHttpsUrl(kind.url), kind.name)
    }

    @Test
    fun `plik z informacja o licencji ikon zawiera pelny tekst Apache-2_0`() {
        val text = moduleFile("src/main/res/raw/notice_material_icons.txt").readText()
        assertTrue(text.contains("Material Icons"))
        assertTrue(text.contains("Apache License"))
        assertTrue(text.contains("Version 2.0, January 2004"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
    }

    @Test
    fun `plik z informacja o licencji Conscrypt zawiera noty Conscrypt BoringSSL i pelny tekst Apache-2_0`() {
        val text = moduleFile("src/main/res/raw/notice_conscrypt.txt").readText()
        assertTrue(text.contains("Conscrypt"))
        assertTrue(text.contains("BoringSSL"))
        assertTrue(text.contains("Copyright 2016 The Android Open Source Project"))
        assertTrue(text.contains("Version 2.0, January 2004"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.contains("fiat-crypto"))
    }

    /** Biblioteki z katalogu wersji, które nie trafiają do APK (testy, narzędzia debug). */
    private val notShipped = setOf("androidx-compose-ui-tooling", "kotlinx-coroutines-test", "junit-bom", "junit-jupiter", "junit-platform-launcher")

    /** Grupa Gradle → składnik z listy licencji. */
    private val componentByGroup = mapOf(
        "androidx.core" to OpenSourceComponent.AndroidX,
        "androidx.activity" to OpenSourceComponent.AndroidX,
        "androidx.lifecycle" to OpenSourceComponent.AndroidX,
        "androidx.compose" to OpenSourceComponent.JetpackCompose,
        "androidx.compose.ui" to OpenSourceComponent.JetpackCompose,
        "androidx.compose.material3" to OpenSourceComponent.Material3,
        "org.jetbrains.kotlinx" to OpenSourceComponent.KotlinxCoroutines,
        "org.conscrypt" to OpenSourceComponent.Conscrypt,
    )

    @Test
    fun `lista licencji obejmuje wszystkie biblioteki z katalogu wersji`() {
        val libraries = moduleFile("../gradle/libs.versions.toml").readLines()
            .map { it.trim() }
            .filter { it.contains("group = \"") }
            .map { line ->
                val alias = line.substringBefore('=').trim()
                val group = line.substringAfter("group = \"").substringBefore('"')
                alias to group
            }
        assertTrue(libraries.isNotEmpty(), "nie odczytano żadnych bibliotek z libs.versions.toml")
        val listed = items.map { it.component }.toSet()
        for ((alias, group) in libraries) {
            if (alias in notShipped) continue
            val component = componentByGroup[group]
                ?: fail("Biblioteka $alias (grupa $group) nie ma składnika na liście licencji – dopisz ją do OpenSourceLicenses i NOTICE.md")
            assertTrue(component in listed, "Składnik $component (biblioteka $alias) nie jest na liście licencji")
        }
    }
}
