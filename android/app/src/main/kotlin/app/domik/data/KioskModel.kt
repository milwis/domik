package app.domik.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * To, czego tryb tabletu ściennego potrzebuje od Androida (implementacja: `AndroidKioskPlatform`). Interfejs
 * zamiast `PackageManager` / `ActivityManager`, żeby logikę [KioskModel] dało się testować w JVM.
 */
interface KioskPlatform {
    /** Czy włączony jest alias aktywności z filtrem HOME (Domik może zostać ekranem głównym). */
    var homeAliasEnabled: Boolean

    /** Czy system uruchamia teraz Domika po stuknięciu „Ekran główny” (Domik wybrany jako aplikacja ekranu głównego). */
    val isDefaultHome: Boolean

    /** Otwiera ustawienia aplikacji ekranu głównego (a gdy ich nie ma – ustawienia systemu). false = nie udało się. */
    fun openHomeSettings(): Boolean

    /** Czy Domik jest przypięty (tryb przypinania ekranu / lock task). */
    val isPinned: Boolean

    /** Czy przypinanie jest włączone w ustawieniach systemu; null = nie da się tego sprawdzić. */
    val pinningEnabledInSystem: Boolean?

    /** `Activity.startLockTask()`; system sam pokaże prośbę o potwierdzenie. false = wyjątek / odmowa. */
    fun startPinning(): Boolean

    /** `Activity.stopLockTask()`; false = system odmówił (np. przypięto z ostatnich aplikacji). */
    fun stopPinning(): Boolean
}

/** Komunikat pod pozycjami menu „Tablet ścienny” (błędy i podpowiedzi, gdy coś nie wyszło). */
enum class KioskNotice {
    /** Przypinanie jest wyłączone w systemie – gdzie je włączyć. */
    PINNING_DISABLED,

    /** Aplikacja nie zdołała sama odpiąć – jak odpiąć gestem. */
    UNPIN_FAILED,

    /** Nie udało się otworzyć ustawień ekranu głównego. */
    HOME_SETTINGS_UNAVAILABLE,
}

/**
 * Stan pozycji menu „Tablet ścienny”.
 * @property homeAliasEnabled przełącznik „Domik jako ekran główny” (alias HOME włączony)
 * @property isDefaultHome system faktycznie używa Domika jako ekranu głównego
 * @property pinned Domik jest przypięty (pokazujemy „Odepnij”)
 */
data class KioskState(
    val settings: KioskSettings,
    val homeAliasEnabled: Boolean,
    val isDefaultHome: Boolean,
    val pinned: Boolean,
    val notice: KioskNotice? = null,
)

/**
 * Logika trybu tabletu ściennego ponad Androidem: ustawienia „Ekran zawsze włączony przy zasilaniu” i „Pełny
 * ekran” (zapisywane w [AppSettings]), „Domik jako ekran główny” (alias HOME + otwarcie ustawień systemu) oraz
 * przypinanie. Stan platformy (ekran główny, przypięcie) zmienia się poza aplikacją, więc [refresh] trzeba
 * wołać po powrocie na pierwszy plan.
 *
 * Samo zastosowanie ustawień do okna (flaga ekranu, paski systemowe) robi aktywność, obserwując [state].
 */
class KioskModel(private val settings: AppSettings, private val platform: KioskPlatform) {
    private val _state = MutableStateFlow(snapshot(notice = null))
    val state: StateFlow<KioskState> = _state.asStateFlow()

    fun setKeepScreenOnWhenPowered(value: Boolean) = changeSettings { it.copy(keepScreenOnWhenPowered = value) }

    fun setFullscreen(value: Boolean) = changeSettings { it.copy(fullscreen = value) }

    /**
     * Przełącznik „Domik jako ekran główny”. Włączenie: alias HOME włączony i otwarte ustawienia, w których
     * użytkownik wybiera Domika. Wyłączenie: alias wyłączony i – jeśli Domik był ekranem głównym – otwarte
     * ustawienia, żeby użytkownik wybrał zwykły ekran główny (np. One UI Home).
     */
    fun setHomeLauncher(enabled: Boolean) {
        val wasDefault = platform.isDefaultHome
        platform.homeAliasEnabled = enabled
        val opened = if (enabled || wasDefault) platform.openHomeSettings() else true
        _state.value = snapshot(notice = if (opened) null else KioskNotice.HOME_SETTINGS_UNAVAILABLE)
    }

    /** „Otwórz ustawienia”, gdy alias jest włączony, ale system używa jeszcze innego ekranu głównego. */
    fun openHomeSettings() {
        val opened = platform.openHomeSettings()
        _state.value = snapshot(notice = if (opened) null else KioskNotice.HOME_SETTINGS_UNAVAILABLE)
    }

    /** „Przypnij Domika”: gdy przypinanie jest wyłączone w systemie – tylko komunikat, gdzie je włączyć. */
    fun pin() {
        if (platform.pinningEnabledInSystem == false || !platform.startPinning()) {
            _state.value = snapshot(notice = KioskNotice.PINNING_DISABLED)
            return
        }
        _state.value = snapshot(notice = null)
    }

    /** „Odepnij Domika”. */
    fun unpin() {
        val stopped = platform.stopPinning()
        _state.value = snapshot(notice = if (stopped) null else KioskNotice.UNPIN_FAILED)
    }

    /** Odczytuje stan platformy na nowo (powrót z ustawień systemu, potwierdzenie przypięcia, zmiana ekranu głównego). */
    fun refresh() {
        _state.update { snapshot(it.notice) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun changeSettings(change: (KioskSettings) -> KioskSettings) {
        settings.kiosk = change(settings.kiosk)
        _state.update { snapshot(it.notice) }
    }

    private fun snapshot(notice: KioskNotice?) = KioskState(
        settings = settings.kiosk,
        homeAliasEnabled = platform.homeAliasEnabled,
        isDefaultHome = platform.isDefaultHome,
        pinned = platform.isPinned,
        notice = notice,
    )
}
