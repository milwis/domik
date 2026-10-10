package app.domik

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.domik.data.KioskModel
import app.domik.platform.AndroidKioskPlatform
import app.domik.platform.applySystemBars
import app.domik.platform.powerConnectedFlow
import app.domik.ui.AppRoot
import app.domik.ui.menu.LocalKioskModel
import app.domik.ui.theme.DomikBackground
import app.domik.ui.theme.DomikTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Jedyna aktywność. `launchMode="singleTask"` (manifest): ekran główny, ikona i ostatnie aplikacje prowadzą do tej
 * samej instancji, więc nie powstaje drugie okno z drugim połączeniem ze sterownikiem. Tryb tabletu ściennego
 * ([KioskModel]): flaga „ekran zawsze włączony” (tylko na pierwszym planie i przy zasilaniu) oraz pełny ekran.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private lateinit var kiosk: KioskModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val environment = (application as DomikApplication).environment
        kiosk = KioskModel(environment.settings, AndroidKioskPlatform(this))
        bindKioskToWindow()
        val model = viewModel.model
        setContent {
            val appearance by model.appearance.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalKioskModel provides kiosk) {
                DomikTheme(mode = appearance.mode, palette = appearance.palette) {
                    DomikBackground {
                        AppRoot(model)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Po powrocie do aplikacji: jeśli połączenie się poddało, spróbuj od razu; odśwież też stan.
        viewModel.model.retryConnection()
    }

    override fun onResume() {
        super.onResume()
        refreshKioskWindow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Ekran główny (HOME) albo ikona przy już działającym Domiku: ta sama instancja wraca na wierzch.
        setIntent(intent)
        refreshKioskWindow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Po powrocie z okna dialogowego, zgody systemowej albo ustawień system mógł pokazać paski.
        if (hasFocus) refreshKioskWindow()
    }

    private fun refreshKioskWindow() {
        applySystemBars(window, kiosk.state.value.settings.fullscreen)
        kiosk.refresh()
    }

    /**
     * Przypina tryb tabletu ściennego do okna. Flaga `FLAG_KEEP_SCREEN_ON` ustawiona tylko wtedy, gdy aktywność jest widoczna, tablet jest podłączony do
     * zasilania i włączono „Ekran zawsze włączony przy zasilaniu”. Odbiornik zasilania działa tylko w cyklu
     * życia aktywności (od onStart do onStop); po zatrzymaniu flaga jest zdejmowana.
     */
    private fun bindKioskToWindow() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val settings = kiosk.state.map { it.settings }.distinctUntilChanged()
                try {
                    combine(settings, powerConnectedFlow(applicationContext)) { current, powered ->
                        current.keepScreenOn(powered = powered, visible = true)
                    }.distinctUntilChanged().collect { keepOn ->
                        if (keepOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                } finally {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // Pełny ekran zmieniony w menu działa od razu (bez czekania na zmianę ostrości okna).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kiosk.state.map { it.settings.fullscreen }.distinctUntilChanged().collect { fullscreen ->
                    applySystemBars(window, fullscreen)
                }
            }
        }
    }
}
