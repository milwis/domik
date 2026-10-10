package app.domik.data

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.client.commands.PendingSetpoint
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.ShutterDirection

/** Adres wyjścia: moduł (`id_device`) i przekaźnik (`output_relay`). */
data class OutputKey(val module: Int, val relay: Int)

/** Adres rolety: moduł (`id_device`) i numer rolety (`output_nr`) – nie `output_relay`, którym sterowniki adresują światła. */
data class ShutterKey(val module: Int, val outputNr: Int)

/** Przycisk rolety na kafelku: w górę, stop, w dół. */
enum class ShutterButton { UP, STOP, DOWN }

/** Dlaczego nie udało się wczytać konfiguracji domu (gdy nie ma jej nawet w pamięci podręcznej). */
enum class ConfigFailure {
    /** Brak połączenia lub brak odpowiedzi sterownika. */
    NO_CONNECTION,

    /** Sterownik odesłał bazę, której nie da się odczytać. */
    UNREADABLE,

    OTHER,
}

sealed interface ConfigStatus {
    /** Konfiguracja jeszcze niepobrana (łączenie / pobieranie). */
    data object Loading : ConfigStatus

    data object Ready : ConfigStatus

    data class Failed(val reason: ConfigFailure) : ConfigStatus
}

/**
 * Co aplikacja wie o domu, w jednym niezmiennym obiekcie (źródło dla UI). Nazwy, pomieszczenia i lista urządzeń
 * pochodzą z [config] (baza sterownika), stany z odczytów i zdarzeń sterownika.
 *
 * @property synced po (ponownym) połączeniu odczytano pełny stan sterownika; false = wartości mogą być nieaktualne
 * @property outputsOn wyjścia włączone wg ostatniej wiadomości sterownika
 * @property pendingOutputs polecenie przełączenia w toku: docelowy stan wyjścia (kafelek pokazuje „oczekiwanie”)
 * @property zones ostatni odczyt (0x6B) każdej strefy
 * @property pendingProfiles zmiana profilu w toku: docelowy profil
 * @property pendingSetpoints temperatura zadana wybrana przez użytkownika, jeszcze niepotwierdzona
 * @property shutters ostatni kierunek zgłoszony przez sterownik dla rolety (zdarzenie 0x8B); **brak wpisu = stan
 *   nieznany** – sterownik z firmware 2017 nie pozwala zapytać o stan rolety, więc po starcie aplikacji żadna roleta
 *   go nie ma, dopóki ktoś jej nie ruszy
 * @property pendingShutters polecenie rolety w toku: wciśnięty przycisk (czeka na zdarzenie ze sterownika)
 */
data class HomeState(
    val config: HomeConfig? = null,
    val configStatus: ConfigStatus = ConfigStatus.Loading,
    val synced: Boolean = false,
    val outputsOn: Set<OutputKey> = emptySet(),
    val pendingOutputs: Map<OutputKey, Boolean> = emptyMap(),
    val zones: Map<ZoneKey, HeaterState> = emptyMap(),
    val pendingProfiles: Map<ZoneKey, HeatingProfile> = emptyMap(),
    val pendingSetpoints: Map<ZoneKey, PendingSetpoint> = emptyMap(),
    val shutters: Map<ShutterKey, ShutterDirection> = emptyMap(),
    val pendingShutters: Map<ShutterKey, ShutterButton> = emptyMap(),
)

/** Zdarzenia jednorazowe dla UI (komunikat na chwilę), nie stan. [subject] to nazwa urządzenia z konfiguracji. */
sealed interface HomeEvent {
    data class CommandFailed(val subject: String, val kind: CommandFailureKind) : HomeEvent

    /** Wyjście ma w bazie `can_on = 0`: aplikacja go nie włącza. */
    data class CannotSwitchOn(val subject: String) : HomeEvent
}

/** Powód niepowodzenia polecenia – do wyboru komunikatu dla użytkownika. */
enum class CommandFailureKind {
    /** Brak połączenia ze sterownikiem. */
    NOT_CONNECTED,

    /** Sterownik nie potwierdził polecenia w czasie. */
    NO_RESPONSE,

    /** Sterownik potwierdził, ale stan po poleceniu jest inny niż żądany. */
    NOT_CONFIRMED,

    /** Polecenie odrzucone przed wysłaniem (wartość spoza zakresu, funkcja wyłączona). */
    REJECTED,
}

/** Null, gdy sterownik przyjął polecenie; w przeciwnym razie powód niepowodzenia. */
fun CommandOutcome.failureKind(): CommandFailureKind? = when (this) {
    CommandOutcome.Confirmed, CommandOutcome.AckOnly, is CommandOutcome.SwitchedToComfort -> null
    CommandOutcome.NoAck -> CommandFailureKind.NO_RESPONSE
    is CommandOutcome.Failed ->
        if (problem == ConnectionProblem.NO_RESPONSE) CommandFailureKind.NO_RESPONSE else CommandFailureKind.NOT_CONNECTED
    is CommandOutcome.OutputMismatch, is CommandOutcome.HeaterMismatch -> CommandFailureKind.NOT_CONFIRMED
    is CommandOutcome.Invalid, CommandOutcome.NotEnabled -> CommandFailureKind.REJECTED
}
