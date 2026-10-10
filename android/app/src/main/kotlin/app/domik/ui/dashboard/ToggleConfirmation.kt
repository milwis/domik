package app.domik.ui.dashboard

import androidx.compose.runtime.Immutable
import app.domik.data.TileId

/**
 * Prośba o potwierdzenie przełączenia wyjścia alarmowego: [name] i kierunek ([turnOn]) w chwili dotknięcia kafelka.
 * Nic nie jest wysyłane do sterownika, dopóki użytkownik nie potwierdzi.
 */
@Immutable
data class ConfirmRequest(val id: TileId.Switch, val name: String, val turnOn: Boolean) {
    /**
     * Czy po potwierdzeniu trzeba jeszcze przełączyć wyjście: nie, gdy w międzyczasie ([currentlyOn]) samo trafiło
     * w żądany stan (inaczej przełączenie wysłałoby polecenie odwrotne).
     */
    fun stillNeeded(currentlyOn: Boolean): Boolean = currentlyOn != turnOn
}

/**
 * Co zrobić po dotknięciu kafelka: prośba o potwierdzenie dla wyjścia alarmowego (zawsze, i przy włączaniu, i przy
 * wyłączaniu) albo `null` – przełączyć od razu (zwykłe wyjście) lub nic (trwa już przełączanie).
 */
fun SwitchTileModel.confirmRequest(): ConfirmRequest? =
    if (confirm && !pending) ConfirmRequest(id, name, turnOn = !on) else null
