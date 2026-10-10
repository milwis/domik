package app.domik

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.domik.data.AppEnvironment
import app.domik.data.AppModel

/**
 * Trzyma [AppModel] ponad zmianami konfiguracji ekranu (obrót, zmiana motywu systemu), więc połączenie ze
 * sterownikiem nie jest zrywane przy takich zdarzeniach. Zakończenie aktywności zamyka sesję.
 */
class MainViewModel(environment: AppEnvironment) : ViewModel() {
    val model = AppModel(environment, viewModelScope)

    override fun onCleared() {
        model.shutdown()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel((checkNotNull(this[APPLICATION_KEY]) as DomikApplication).environment) }
        }
    }
}
