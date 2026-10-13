package app.domik.data

/**
 * Wersja regulaminu i polityki prywatności, którą użytkownik akceptuje przy pierwszym uruchomieniu.
 * **Zmiana tej liczby wymusza ponowną akceptację** przy następnym uruchomieniu.
 * Podnieść przy każdej zmianie treści dokumentów (tekst ekranu: `strings.xml`, klucze `terms_*`).
 */
const val TERMS_VERSION: Int = 2

/**
 * Adresy z tekstu streszczenia na ekranie akceptacji (`terms_body`), które ekran robi klikalnymi: oficjalne źródło
 * (wydania), katalog z dokumentami i zgłoszenia. Treść streszczenia pozostaje bez zmian – adresy są tylko wyróżnione
 * (dopasowanie po postaci wyświetlanej, patrz [findLinkRanges]; `TermsLinksTest` pilnuje, żeby każdy z nich
 * występował w obu wersjach językowych tekstu).
 */
val TERMS_BODY_LINKS: List<String> = listOf(ProjectLinks.RELEASES, ProjectLinks.LEGAL_DIR, ProjectLinks.ISSUES)

/** Czy użytkownik zaakceptował dokładnie obowiązującą wersję ([acceptedVersion] = 0: nigdy). */
fun isTermsAccepted(acceptedVersion: Int, current: Int = TERMS_VERSION): Boolean =
    acceptedVersion != 0 && acceptedVersion == current
