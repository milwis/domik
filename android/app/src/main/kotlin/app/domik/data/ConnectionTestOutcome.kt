package app.domik.data

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.ConnectionTestReport
import app.domik.deimic.client.ConnectionTestStep
import app.domik.deimic.client.ConnectionTester
import app.domik.deimic.client.ControllerEndpoint

/** Dlaczego „Testuj połączenie” się nie powiodło – każdy powód ma w UI własny, zrozumiały komunikat. */
enum class ConnectionTestFailure {
    /** Brak trasy do adresu / nieznana nazwa (zły adres IP, inna sieć Wi-Fi). */
    HOST_UNREACHABLE,

    /** Adres odpowiada, ale nic nie nasłuchuje na tym porcie. */
    CONNECTION_REFUSED,

    /** Połączenie trwało zbyt długo. */
    TIMEOUT,

    /** Urządzenie przedstawiło inny certyfikat – to chyba nie jest sterownik Deimic 1. generacji. */
    CERTIFICATE_MISMATCH,

    /** Nieudany handshake TLS z innego powodu. */
    TLS_FAILED,

    /** Połączono, ale sterownik nie odpowiada na polecenia – najpewniej zły numer seryjny. */
    NO_RESPONSE,

    /** Połączenie zerwane w trakcie testu. */
    CONNECTION_LOST,

    /** Inny, nieoczekiwany błąd. */
    OTHER,
}

/** Wynik testu połączenia pokazywany na ekranie ustawień. */
sealed interface ConnectionTestOutcome {
    /** [firmware] i [configChecksum] to tekst techniczny pokazywany jako potwierdzenie (np. „Dec 13 2017”, „4d62”). */
    data class Success(val firmware: String?, val configChecksum: String?) : ConnectionTestOutcome

    data class Failure(val reason: ConnectionTestFailure) : ConnectionTestOutcome
}

/** Zamienia raport techniczny [ConnectionTester] na wynik dla użytkownika. */
fun ConnectionTestReport.toOutcome(): ConnectionTestOutcome {
    val failed = failure ?: return ConnectionTestOutcome.Success(firmwareVersion, configChecksum?.hexString)
    val reason = when (failed.problem) {
        ConnectionProblem.HOST_UNREACHABLE -> ConnectionTestFailure.HOST_UNREACHABLE
        ConnectionProblem.CONNECTION_REFUSED -> ConnectionTestFailure.CONNECTION_REFUSED
        ConnectionProblem.TIMEOUT -> ConnectionTestFailure.TIMEOUT
        ConnectionProblem.CERTIFICATE_MISMATCH -> ConnectionTestFailure.CERTIFICATE_MISMATCH
        ConnectionProblem.TLS_FAILED -> ConnectionTestFailure.TLS_FAILED
        ConnectionProblem.NO_RESPONSE ->
            // Przed zestawieniem TLS „brak odpowiedzi” to zwykły timeout; po nim – brak reakcji na polecenia.
            if (failed.step == ConnectionTestStep.TLS) ConnectionTestFailure.TIMEOUT else ConnectionTestFailure.NO_RESPONSE
        ConnectionProblem.CONNECTION_LOST, ConnectionProblem.DECODER_ERROR -> ConnectionTestFailure.CONNECTION_LOST
        ConnectionProblem.OTHER, null -> ConnectionTestFailure.OTHER
    }
    return ConnectionTestOutcome.Failure(reason)
}

/** Wykonuje test połączenia (za interfejsem, żeby ViewModel dało się testować bez sieci). */
fun interface ConnectionTestRunner {
    suspend fun run(input: ConnectionInput): ConnectionTestReport

    companion object {
        /** Prawdziwy test: TLS → ping → wersja oprogramowania → suma kontrolna konfiguracji (same odczyty). */
        val Default: ConnectionTestRunner = ConnectionTestRunner { input ->
            ConnectionTester().run(ControllerEndpoint(input.host, input.port), input.serial)
        }
    }
}
