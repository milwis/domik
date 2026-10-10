package app.domik.data

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.ConnectionState

/**
 * Komunikat o stanie połączenia dla paska u góry ekranu; null = wszystko w porządku, nic nie pokazujemy.
 *
 * @property problem co jest nie tak (UI wybiera tekst)
 * @property gaveUp aplikacja przestała próbować – pasek proponuje ręczne „Ponów”
 */
data class ConnectionNotice(val problem: ConnectionProblem, val gaveUp: Boolean)

/**
 * Kiedy pokazać pasek: dopiero gdy połączenie zawiodło (czekanie na ponowną próbę, kolejna próba, poddanie się).
 * Pierwsze łączenie trwa ułamek sekundy i nie wymaga paska.
 */
fun ConnectionState.toNotice(): ConnectionNotice? = when (this) {
    ConnectionState.Idle, ConnectionState.Ready, is ConnectionState.Connecting -> null
    is ConnectionState.WaitingToReconnect -> ConnectionNotice(problem, gaveUp = false)
    is ConnectionState.Failed -> ConnectionNotice(problem, gaveUp = true)
}
