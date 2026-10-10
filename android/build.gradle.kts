// Wtyczki deklarowane w katalogu głównym (apply false), żeby wszystkie moduły używały tych samych wersji.
// Wtyczka kotlin.jvm wnosi KGP w wersji z libs.versions.toml – nadpisuje starszą wersję, od której zależy AGP
// (AGP 9 ma wbudowaną obsługę Kotlina, więc :app nie stosuje org.jetbrains.kotlin.android).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
