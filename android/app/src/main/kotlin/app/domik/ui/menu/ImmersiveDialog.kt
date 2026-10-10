package app.domik.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.platform.applySystemBars

/**
 * Wołać wewnątrz zawartości okna dialogowego lub arkusza (`ModalBottomSheet`, `AlertDialog`): okno dialogu
 * ma własne ustawienia pasków systemowych i bez tego pokazałoby paski, gdy aplikacja jest w trybie pełnoekranowym.
 * Poza dialogiem albo bez trybu tabletu ściennego nic nie robi.
 */
@Composable
fun KeepImmersiveInDialog() {
    val model = LocalKioskModel.current ?: return
    val state by model.state.collectAsStateWithLifecycle()
    // Odczyt w kompozycji (nie w SideEffect) – inaczej zmiana ustawienia nie wywołałaby ponownego zastosowania.
    val fullscreen = state.settings.fullscreen
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect { applySystemBars(window, fullscreen) }
}
