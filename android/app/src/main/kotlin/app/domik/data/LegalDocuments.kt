package app.domik.data

/**
 * Pełne teksty prawne dostępne w aplikacji bez internetu. Pliki Markdown nie leżą w repozytorium aplikacji –
 * zadanie Gradle `copyLegalAssets` (`android/app/build.gradle.kts`) kopiuje przy każdym buildzie 4 dokumenty
 * z katalogu `legal/` do `assets/legal/`, więc źródłem prawdy jest zawsze `legal/` (żadnych duplikatów).
 * Wersja językowa jest zgodna z językiem interfejsu (zasób `legal_language`: polski w `values`, angielski w `values-en`).
 */
enum class LegalDocument(private val plPath: String, private val enPath: String) {
    TERMS("regulamin.md", "en/terms.md"),
    PRIVACY("polityka-prywatnosci.md", "en/privacy-policy.md"),
    ;

    /** Ścieżka pliku w `assets/` dla [language] (`"pl"` = polski, każdy inny = angielski). */
    fun assetPath(language: String): String = ASSET_DIR + "/" + if (language == LANGUAGE_PL) plPath else enPath

    companion object {
        const val ASSET_DIR = "legal"
        const val LANGUAGE_PL = "pl"

        /** Pliki kopiowane do assets (względem `legal/`); `LegalDocumentsTest` pilnuje zgodności z listą w build.gradle.kts. */
        val sourceFiles: List<String> = entries.flatMap { listOf(it.plPath, it.enPath) }

        /**
         * Dokument, do którego prowadzi względny odnośnik z treści ([destination], np. `polityka-prywatnosci.md`
         * albo `privacy-policy.md`), albo null dla innych odnośników.
         */
        fun linkedFrom(destination: String): LegalDocument? {
            val file = destination.substringBefore('#').substringAfterLast('/')
            return entries.firstOrNull { it.plPath.substringAfterLast('/') == file || it.enPath.substringAfterLast('/') == file }
        }
    }
}
