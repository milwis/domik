package app.domik.ui.dashboard

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.domik.R
import app.domik.data.TileId
import app.domik.data.TileSize
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kafelek w siatce pulpitu: identyfikator i rozmiar (zawartość rysuje wywołujący). [editIconRes] to ikona
 * pokazywana na przycisku „Ikona” w trybie edycji (bieżąca ikona kafelka-przełącznika; uchwyt do przeciągania
 * zasłania ją na kafelku); `null` = ogólny rysunek przycisku.
 */
@Immutable
data class GridTile(val id: TileId, val size: TileSize, @DrawableRes val editIconRes: Int? = null)

/**
 * Działania trybu edycji wywoływane z siatki (przeciągnięcie, róg „rozmiar”, „ukryj”, akcje dostępności).
 * [cycleSize] – dotknięcie rogu albo akcja dostępności (kolejny rozmiar z listy), [resize] – puszczenie rogu po
 * przeciągnięciu (rozmiar już przyciągnięty do dozwolonego). [chooseIcon] (opcjonalne) otwiera wybór ikony – przycisk
 * „Ikona” mają tylko kafelki-przełączniki. [configure] (opcjonalne) – ustawienia kafelka: dotknięcie kafelka w trybie
 * edycji (poza przyciskami) albo przycisk ⚙; dotyczy tylko rodzajów z `TileKind.configurable` (np. pogoda).
 */
class GridEditActions(
    val move: (id: TileId, toIndex: Int) -> Unit,
    val cycleSize: (id: TileId) -> Unit,
    val resize: (id: TileId, size: TileSize) -> Unit,
    val hide: (id: TileId) -> Unit,
    val chooseIcon: ((id: TileId) -> Unit)? = null,
    val configure: ((id: TileId) -> Unit)? = null,
)

/** Stan trwającego przeciągania kafelka (jeden naraz). Pozycje w pikselach. */
@Stable
internal class GridDragState {
    var id by mutableStateOf<TileId?>(null)

    /** Lewy-górny róg przeciąganego kafelka w układzie widocznego obszaru (bez przewinięcia). */
    var viewportPos by mutableStateOf(Offset.Zero)

    /** Wysokość palca w układzie widocznego obszaru – do automatycznego przewijania na brzegach. */
    var pointerY by mutableFloatStateOf(0f)
    var width = 0f
    var height = 0f
    var lastMoveMillis = 0L

    fun start(id: TileId, canvasPos: IntOffset, scrollY: Int, width: Float, height: Float, pointerInTile: Offset) {
        viewportPos = Offset(canvasPos.x.toFloat(), (canvasPos.y - scrollY).toFloat())
        pointerY = viewportPos.y + pointerInTile.y
        this.width = width
        this.height = height
        this.id = id
    }

    fun dragBy(amount: Offset, pointerInTile: Offset) {
        viewportPos += amount
        pointerY = viewportPos.y + pointerInTile.y
    }

    fun canvasOffset(scrollY: Int) = IntOffset(viewportPos.x.roundToInt(), (viewportPos.y + scrollY).roundToInt())

    fun centerOnCanvas(scrollY: Int) = Offset(viewportPos.x + width / 2f, viewportPos.y + scrollY + height / 2f)
}

/**
 * Stan trwającej zmiany rozmiaru (róg prawy-dolny; jeden kafelek naraz). Pozycje w pikselach; [target] to rozmiar
 * przyciągnięty do dozwolonego – podgląd rysuje siatka, zapis następuje po puszczeniu.
 */
@Stable
internal class GridResizeState {
    var id by mutableStateOf<TileId?>(null)
    var target by mutableStateOf<TileSize?>(null)

    /** Góra kafelka na płótnie i jego rozmiar na początku przeciągania. */
    var tileTop = 0f
    var startWidth = 0f
    var startHeight = 0f

    /** Przesunięcie palca od miejsca dotknięcia względem płótna (z przewinięciem w trakcie). */
    var fingerDx = 0f
    var fingerDy = 0f

    val width: Float get() = startWidth + fingerDx
    val height: Float get() = startHeight + fingerDy

    /** Palec (≈ róg kafelka) w układzie widocznego obszaru – do automatycznego przewijania na brzegach. */
    fun pointerY(scrollY: Int): Float = tileTop + height - scrollY

    fun start(id: TileId, tileTop: Float, width: Float, height: Float, size: TileSize) {
        this.tileTop = tileTop
        startWidth = width
        startHeight = height
        fingerDx = 0f
        fingerDy = 0f
        target = size
        this.id = id
    }

    fun end() {
        id = null
        target = null
    }
}

private const val MOVE_COOLDOWN_MS = 180L
private const val EDIT_SCALE = 0.95f
private const val DRAG_SCALE = 1.04f
/** Uchwyt zmiany rozmiaru: pole dotyku w prawym-dolnym rogu, łuk wzdłuż zaokrąglenia kafelka i jego ramiona. */
private val GRIP_TOUCH = 48.dp
private val GRIP_STROKE = 5.dp
private val GRIP_ARM = 12.dp
private val TILE_CORNER = 24.dp
private val CHROME_INSET = 14.dp
private val CHROME_BUTTON = 40.dp
private val CHROME_INSET_SMALL = 8.dp
private val CHROME_BUTTON_SMALL = 30.dp

/** Pole dotyku przycisku ⚙ (ustawienia kafelka): zawsze co najmniej 48 dp, choć rysowany jest tak jak sąsiednie. */
private val CHROME_TOUCH_MIN = 48.dp

/** Najmniejszy przycisk „Ikona” (na najwęższych kafelkach) i najmniejszy odstęp od sąsiednich przycisków. */
private val CHROME_BUTTON_ICON_MIN = 24.dp
private val CHROME_GAP = 4.dp

/** Kafelki węższe niż to (S w wąskich komórkach) dostają mniejsze przyciski edycji, żeby nie zasłaniały nazwy. */
private val SMALL_TILE_WIDTH = 140.dp
private val AUTOSCROLL_EDGE = 72.dp
private val AUTOSCROLL_MIN_SPEED = 3.dp
private val AUTOSCROLL_MAX_SPEED = 20.dp

/**
 * Pulpit: jedno płótno z kafelkami ułożonymi w siatce. Kafelki są pakowane w kolejności
 * ([GridPacker], „pierwsze wolne miejsce”) w siatce o [columns] kolumnach; płótno przewija się w pionie.
 * Zawartość kafelka rysuje [content] (dostaje rozmiar kafelka w dp).
 *
 * W trybie edycji ([editing]) kafelki są lekko pomniejszone, w przerywanej ramce, z uchwytem do przeciągania,
 * przyciskami „rozmiar” i „ukryj”; sterowanie urządzeniami jest wyłączone. Kafelek można przeciągnąć uchwytem
 * albo po przytrzymaniu (inne kafelki przesuwają się płynnie, przy brzegu płótno przewija się samo). Dla czytników
 * ekranu zamiast przeciągania są akcje „przesuń wcześniej / później”, „zmień rozmiar”, „ukryj”.
 *
 * @param scrollToEnd zwiększenie wartości przewija płótno na koniec (po dodaniu kafelka)
 * @param labels nazwy kafelków dla czytników ekranu w trybie edycji
 * @param emptyHint tekst na pustym płótnie
 */
@Composable
fun DashboardGrid(
    tiles: List<GridTile>,
    columns: Int,
    editing: Boolean,
    actions: GridEditActions,
    labels: Map<TileId, String>,
    emptyHint: String,
    modifier: Modifier = Modifier,
    scrollToEnd: Int = 0,
    content: @Composable (tile: GridTile, width: Dp, height: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val d = density.density
    BoxWithConstraints(modifier.fillMaxSize()) {
        val gap = GridSpec.gap(maxWidth.value)
        val availableWidth = maxWidth.value - 2 * gap
        val viewportHeight = maxHeight.value - gap
        val geometry = remember(availableWidth, viewportHeight, columns, gap) {
            GridSpec.geometry(availableWidth, viewportHeight, columns, gap)
        }
        val packItems = remember(tiles) { tiles.map { PackItem(it.size.cols, it.size.rows) } }
        val rects = remember(packItems, geometry) { GridPacker.pack(packItems, geometry.columns) }
        val pxRects = remember(rects, geometry, d) {
            rects.map { r ->
                PxRect(
                    left = geometry.x(r.col) * d,
                    top = geometry.y(r.row) * d,
                    right = (geometry.x(r.col) + geometry.width(r.cols)) * d,
                    bottom = (geometry.y(r.row) + geometry.height(r.rows)) * d,
                )
            }
        }
        val scroll = rememberScrollState()
        val drag = remember { GridDragState() }
        val resize = remember { GridResizeState() }
        val resizingIndex = tiles.indexOfFirst { it.id == resize.id }
        val resizeTarget = resize.target
        // Podgląd: miejsce i rozmiar kafelka po puszczeniu rogu (ułożenie z nowym rozmiarem).
        val preview = if (resizingIndex >= 0 && resizeTarget != null) {
            GridResize.preview(packItems, resizingIndex, resizeTarget, geometry.columns)
        } else {
            null
        }

        val rowCount = maxOf(GridPacker.rowCount(rects), preview?.bottom ?: 0)
        val tilesHeight = if (rowCount == 0) 0f else geometry.height(rowCount)
        val contentHeight = tilesHeight + gap + if (editing) geometry.rowHeight + gap else 0f

        val tilesNow = rememberUpdatedState(tiles)
        val pxRectsNow = rememberUpdatedState(pxRects)
        val actionsNow = rememberUpdatedState(actions)
        val geometryNow = rememberUpdatedState(geometry)
        val viewportPx = constraints.maxHeight.toFloat()
        val haptics = LocalHapticFeedback.current

        // Przyciąganie rozmiaru „z palca” do dozwolonego; każda zmiana rozmiaru = krótkie tyknięcie.
        val updateResize = remember(resize, d, haptics) {
            {
                val id = resize.id
                if (id != null) {
                    val snapped = GridResize.snap(id.kind, geometryNow.value, resize.width / d, resize.height / d)
                    if (snapped != resize.target) {
                        resize.target = snapped
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
        }
        LaunchedEffect(editing) { if (!editing) resize.end() }

        // Cel upuszczenia wg środka przeciąganego kafelka; ruch co najwyżej co MOVE_COOLDOWN_MS (czas na animację).
        val evaluateDrop = remember(drag, scroll) {
            {
                val id = drag.id
                if (id != null) {
                    val from = tilesNow.value.indexOfFirst { it.id == id }
                    val center = drag.centerOnCanvas(scroll.value)
                    val target = if (from >= 0) dropTargetIndex(pxRectsNow.value, from, center.x, center.y) else null
                    val now = SystemClock.uptimeMillis()
                    if (target != null && now - drag.lastMoveMillis >= MOVE_COOLDOWN_MS) {
                        drag.lastMoveMillis = now
                        actionsNow.value.move(id, target)
                    }
                }
            }
        }

        // Podczas przeciągania kafelka albo rogu: przewijanie przy brzegu widocznego obszaru i ponawianie oceny celu
        // (np. po upływie czasu). Przy zmianie rozmiaru przewinięcie przesuwa palec względem płótna – kafelek rośnie.
        val resizing = resize.id != null
        LaunchedEffect(drag.id, resizing) {
            if (drag.id == null && !resizing) return@LaunchedEffect
            val edge = AUTOSCROLL_EDGE.value * d
            val minSpeed = AUTOSCROLL_MIN_SPEED.value * d
            val maxSpeed = AUTOSCROLL_MAX_SPEED.value * d
            while (true) {
                val y = if (resizing) resize.pointerY(scroll.value) else drag.pointerY
                val delta = when {
                    y < edge -> -(minSpeed + (maxSpeed - minSpeed) * ((edge - y) / edge).coerceIn(0f, 1f))
                    y > viewportPx - edge -> minSpeed + (maxSpeed - minSpeed) * ((y - (viewportPx - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (delta != 0f) {
                    val consumed = scroll.scrollBy(delta)
                    if (resizing) {
                        // Palec stoi, płótno jedzie: do następnego zdarzenia dotyku przesunięcie liczymy sami.
                        resize.fingerDy += consumed
                        updateResize()
                    }
                }
                if (!resizing) evaluateDrop()
                delay(16)
            }
        }

        LaunchedEffect(scrollToEnd) {
            if (scrollToEnd > 0) {
                delay(120)
                scroll.animateScrollTo(scroll.maxValue)
            }
        }

        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(contentHeight.dp).padding(horizontal = gap.dp)) {
                // Miejsce, w które wpadnie przeciągany kafelek.
                val draggedIndex = tiles.indexOfFirst { it.id == drag.id }
                if (draggedIndex >= 0) {
                    val r = rects[draggedIndex]
                    Box(
                        Modifier
                            .offset(geometry.x(r.col).dp, geometry.y(r.row).dp)
                            .size(geometry.width(r.cols).dp, geometry.height(r.rows).dp)
                            .clip(DomikShapes.tile)
                            .background(DomikTheme.colors.accent.copy(alpha = 0.12f)),
                    )
                }
                tiles.forEachIndexed { index, tile ->
                    androidx.compose.runtime.key(tile.id.code) {
                        TileSlot(
                            tile = tile,
                            index = index,
                            rect = rects[index],
                            geometry = geometry,
                            editing = editing,
                            label = labels[tile.id].orEmpty(),
                            drag = drag,
                            resize = resize,
                            scrollY = { scroll.value },
                            evaluateDrop = evaluateDrop,
                            updateResize = updateResize,
                            actions = actionsNow,
                            content = content,
                        )
                    }
                }
                if (preview != null && resizeTarget != null) ResizePreview(preview, resizeTarget, geometry)
            }
        }

        if (tiles.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodyLarge,
                color = DomikTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
    }
}

@Composable
private fun TileSlot(
    tile: GridTile,
    index: Int,
    rect: GridRect,
    geometry: GridGeometry,
    editing: Boolean,
    label: String,
    drag: GridDragState,
    resize: GridResizeState,
    scrollY: () -> Int,
    evaluateDrop: () -> Unit,
    updateResize: () -> Unit,
    actions: androidx.compose.runtime.State<GridEditActions>,
    content: @Composable (GridTile, Dp, Dp) -> Unit,
) {
    val d = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val width = geometry.width(rect.cols)
    val height = geometry.height(rect.rows)
    val target = IntOffset((geometry.x(rect.col) * d).roundToInt(), (geometry.y(rect.row) * d).roundToInt())
    val offset = remember { Animatable(target, IntOffset.VectorConverter) }
    val dragging = drag.id == tile.id

    // Przesunięcie na nowe miejsce w siatce – płynnie; przeciągany kafelek podąża za palcem.
    LaunchedEffect(target, dragging) {
        if (!dragging) offset.animateTo(target, tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(editing) {
        if (!editing && drag.id == tile.id) drag.id = null
    }
    DisposableEffect(tile.id) {
        onDispose {
            if (drag.id == tile.id) drag.id = null
            if (resize.id == tile.id) resize.end()
        }
    }
    // Palec na rogu „rozmiar”: przytrzymanie nie zaczyna wtedy przenoszenia kafelka.
    val gripPressed = remember { BooleanArray(1) }

    val scale = animateFloatAsState(
        targetValue = (if (editing) EDIT_SCALE else 1f) * (if (dragging) DRAG_SCALE / EDIT_SCALE else 1f),
        animationSpec = tween(160),
        label = "tileScale",
    )
    val lift = animateFloatAsState(if (dragging) 1f else 0f, tween(160), label = "tileLift")

    val sizePx = Offset(width * d, height * d)
    fun beginDrag(pointerInTile: Offset) {
        if (gripPressed[0] || resize.id != null) return
        drag.start(tile.id, offset.value, scrollY(), sizePx.x, sizePx.y, pointerInTile)
    }
    fun beginResize() {
        if (drag.id != null || resize.id != null) return
        resize.start(tile.id, offset.value.y.toFloat(), sizePx.x, sizePx.y, tile.size)
    }

    // Przesunięcie palca liczone w układzie rogu, który jedzie razem z płótnem – zawiera więc przewinięcie.
    fun resizeBy(fingerOffset: Offset) {
        if (resize.id != tile.id) return
        resize.fingerDx = fingerOffset.x
        resize.fingerDy = fingerOffset.y
        updateResize()
    }
    fun endResize(commit: Boolean) {
        if (resize.id != tile.id) return
        val target = resize.target
        resize.end()
        if (commit && target != null && target != tile.size) actions.value.resize(tile.id, target)
    }
    fun endDrag() {
        if (drag.id != tile.id) return
        // Najpierw ustaw animowaną pozycję tam, gdzie kafelek jest teraz, potem dopiero pozwól jej płynnie dojechać do miejsca.
        scope.launch {
            offset.snapTo(drag.canvasOffset(scrollY()))
            drag.id = null
        }
    }

    val earlier = stringResource(R.string.edit_move_earlier)
    val later = stringResource(R.string.edit_move_later)
    val resizeLabel = stringResource(R.string.edit_resize)
    val hide = stringResource(R.string.edit_hide)
    val chooseIcon = stringResource(R.string.edit_icon)
    val configureLabel = stringResource(R.string.edit_configure)
    val sizeState = stringResource(R.string.edit_size_state, tile.size.cols, tile.size.rows)
    val canChooseIcon = tile.id is TileId.Switch && actions.value.chooseIcon != null
    val canConfigure = tile.id.kind.configurable && actions.value.configure != null
    val resizable = tile.id.kind.resizable

    val handleOrigin = Offset(CHROME_INSET.value * d, CHROME_INSET.value * d)
    Box(
        Modifier
            .offset { if (drag.id == tile.id) drag.canvasOffset(scrollY()) else offset.value }
            .size(width.dp, height.dp)
            .zIndex(if (dragging) 10f else 0f)
            .then(
                if (editing) {
                    Modifier
                        .pointerInput(tile.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { beginDrag(it) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag.dragBy(amount, change.position)
                                    evaluateDrop()
                                },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                            )
                        }
                        .clearAndSetSemantics {
                            contentDescription = label
                            stateDescription = sizeState
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(earlier) { actions.value.move(tile.id, index - 1); true },
                                CustomAccessibilityAction(later) { actions.value.move(tile.id, index + 1); true },
                                if (resizable) {
                                    CustomAccessibilityAction(resizeLabel) { actions.value.cycleSize(tile.id); true }
                                } else {
                                    null
                                },
                                if (canChooseIcon) {
                                    CustomAccessibilityAction(chooseIcon) { actions.value.chooseIcon?.invoke(tile.id); true }
                                } else {
                                    null
                                },
                                if (canConfigure) {
                                    CustomAccessibilityAction(configureLabel) { actions.value.configure?.invoke(tile.id); true }
                                } else {
                                    null
                                },
                                CustomAccessibilityAction(hide) { actions.value.hide(tile.id); true },
                            )
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        val accent = DomikTheme.colors.accent
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    shadowElevation = lift.value * 16.dp.toPx()
                    shape = DomikShapes.tile
                    clip = false
                }
                .drawWithContent {
                    drawContent()
                    if (editing) {
                        val stroke = 2.dp.toPx()
                        drawRoundRect(
                            color = accent.copy(alpha = 0.65f),
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(24.dp.toPx()),
                            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()))),
                        )
                    }
                },
        ) {
            content(tile, width.dp, height.dp)
            if (editing) {
                // Przygaszenie zawartości: sterowanie jest wyłączone (skaluje się razem z kafelkiem).
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(DomikShapes.tile)
                        .background(DomikTheme.colors.card.copy(alpha = 0.40f)),
                )
            }
        }
        if (editing) {
            EditChrome(
                small = width.dp < SMALL_TILE_WIDTH,
                label = label,
                tileWidth = width.dp,
                iconRes = tile.editIconRes,
                onHandleStart = { beginDrag(handleOrigin + it) },
                onHandleDrag = { amount, position ->
                    drag.dragBy(amount, handleOrigin + position)
                    evaluateDrop()
                },
                onHandleEnd = { endDrag() },
                grip = if (resizable) {
                    ResizeGripActions(
                        pressed = { gripPressed[0] = it },
                        onTap = { actions.value.cycleSize(tile.id) },
                        onStart = { beginResize() },
                        onDrag = { resizeBy(it) },
                        onEnd = { commit -> endResize(commit) },
                    )
                } else {
                    null
                },
                // Róg pomniejszonego kafelka (EDIT_SCALE) – tam rysujemy łuk uchwytu.
                gripInset = DpOffset((width * (1f - EDIT_SCALE) / 2f).dp, (height * (1f - EDIT_SCALE) / 2f).dp),
                onHide = { actions.value.hide(tile.id) },
                onChooseIcon = if (canChooseIcon) ({ actions.value.chooseIcon?.invoke(tile.id) }) else null,
                onConfigure = if (canConfigure) ({ actions.value.configure?.invoke(tile.id) }) else null,
                configureLabel = configureLabel,
            )
        }
    }
}

/**
 * Róg „rozmiar” kafelka. [pressed] – palec dotknął rogu (`true`) / puścił go (`false`); [onTap] – dotknięcie bez
 * przeciągania (kolejny rozmiar z listy); [onStart] / [onDrag] (przesunięcie palca od miejsca dotknięcia) / [onEnd]
 * (`true` = puszczenie, zapisz; `false` = przerwane).
 */
internal class ResizeGripActions(
    val pressed: (Boolean) -> Unit,
    val onTap: () -> Unit,
    val onStart: () -> Unit,
    val onDrag: (fingerOffset: Offset) -> Unit,
    val onEnd: (commit: Boolean) -> Unit,
)

/**
 * Warstwa edycji na kafelku: blokuje dotyk zawartości (sterowanie urządzeniami wyłączone; dotknięcie kafelka wywołuje
 * [onConfigure], jeśli jest), uchwyt do przeciągania (lewy-górny róg), „ukryj” (prawy-górny), róg „rozmiar”
 * ([grip], prawy-dolny: łuk wzdłuż zaokrąglenia kafelka – przeciąganie zmienia rozmiar, dotknięcie przełącza
 * po liście), dla przełączników ([onChooseIcon]) – „ikona” (górny środek, żeby nie zasłaniać nazwy na dole)
 * i dla kafelków z ustawieniami – ⚙ („ustawienia kafelka”; ten sam górny środek, między uchwytem a „ukryj”,
 * z polem dotyku 48 dp; gdy zajęty przez „ikonę” – lewy-dolny róg).
 * Róg jest cienkim łukiem przy samej krawędzi, więc nie zasłania nazwy ani pomieszczenia w dolnej części kafelka.
 */
@Composable
private fun BoxScope.EditChrome(
    small: Boolean,
    label: String,
    tileWidth: Dp,
    iconRes: Int?,
    onHandleStart: (Offset) -> Unit,
    onHandleDrag: (amount: Offset, position: Offset) -> Unit,
    onHandleEnd: () -> Unit,
    grip: ResizeGripActions?,
    gripInset: DpOffset,
    onHide: () -> Unit,
    onChooseIcon: (() -> Unit)? = null,
    onConfigure: (() -> Unit)? = null,
    configureLabel: String = "",
) {
    val configure = rememberUpdatedState(onConfigure)
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(Unit) {
                // Nic poza krótkim dotknięciem nie konsumuje (przytrzymanie na kafelku dalej zaczyna przeciąganie
                // u rodzica), ale jako najwyższy element z obsługą dotyku zasłania zawartość kafelka: sterowanie
                // urządzeniami w edycji nie działa. Krótkie dotknięcie (puszczone przed przytrzymaniem) = [onConfigure].
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                    val action = configure.value
                    if (up != null && action != null) {
                        up.consume()
                        action()
                    }
                }
            },
    )
    val inset = if (small) CHROME_INSET_SMALL else CHROME_INSET
    val button = if (small) CHROME_BUTTON_SMALL else CHROME_BUTTON
    if (onConfigure != null) {
        // Przed uchwytem i „ukryj”: przy nakładaniu się pól dotyku (wąskie kafelki) wygrywają sąsiedzi (wyższa warstwa).
        val touch = maxOf(CHROME_TOUCH_MIN, button)
        val position = if (onChooseIcon == null) {
            Modifier.align(Alignment.TopCenter).offset(y = inset - (touch - button) / 2)
        } else {
            Modifier.align(Alignment.BottomStart).offset(x = inset - (touch - button) / 2, y = -(inset - (touch - button) / 2))
        }
        ChromeButton(
            iconRes = R.drawable.ic_settings,
            buttonSize = button,
            modifier = position,
            onClick = onConfigure,
            contentDescription = configureLabel,
            touchSize = touch,
        )
    }
    ChromeButton(
        iconRes = R.drawable.ic_drag_handle,
        buttonSize = button,
        modifier = Modifier.align(Alignment.TopStart).padding(inset).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = onHandleStart,
                onDrag = { change, amount ->
                    change.consume()
                    onHandleDrag(amount, change.position)
                },
                onDragEnd = onHandleEnd,
                onDragCancel = onHandleEnd,
            )
        },
    )
    ChromeButton(
        iconRes = R.drawable.ic_close,
        buttonSize = button,
        modifier = Modifier.align(Alignment.TopEnd).padding(inset),
        onClick = onHide,
    )
    if (onChooseIcon != null) {
        // Środek górnej krawędzi między uchwytem a „ukryj”: przycisk tak duży, jak pozwala wolne miejsce.
        val free = tileWidth - (inset + button) * 2 - CHROME_GAP * 2
        ChromeButton(
            iconRes = iconRes ?: R.drawable.ic_icon_pick,
            buttonSize = free.coerceIn(CHROME_BUTTON_ICON_MIN, button),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = inset),
            onClick = onChooseIcon,
        )
    }
    if (grip != null) {
        ResizeGrip(grip, Modifier.align(Alignment.BottomEnd).padding(end = gripInset.x, bottom = gripInset.y))
    }
}

/**
 * Róg „rozmiar”: pole dotyku [GRIP_TOUCH] w prawym-dolnym rogu kafelka, a w nim gruby łuk w kolorze akcentu wzdłuż
 * zaokrąglenia kafelka (z krótkimi ramionami wzdłuż krawędzi; pod spodem obwódka w kolorze karty dla kontrastu).
 * Przeciągnięcie (po przekroczeniu progu ruchu) zmienia rozmiar, krótkie dotknięcie – kolejny rozmiar z listy.
 */
@Composable
private fun ResizeGrip(actions: ResizeGripActions, modifier: Modifier) {
    val colors = DomikTheme.colors
    val current = rememberUpdatedState(actions)
    Box(
        modifier
            .size(GRIP_TOUCH)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val grip = current.value
                    grip.pressed(true)
                    var dragging = false
                    var finished = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                finished = true
                                if (dragging) grip.onEnd(true) else grip.onTap()
                                break
                            }
                            val offset = change.position - down.position
                            if (!dragging && offset.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                grip.onStart()
                            }
                            if (dragging) {
                                change.consume()
                                grip.onDrag(offset)
                            }
                        }
                    } finally {
                        grip.pressed(false)
                        if (dragging && !finished) grip.onEnd(false)
                    }
                }
            }
            .drawBehind {
                val stroke = GRIP_STROKE.toPx()
                val radius = TILE_CORNER.toPx() * EDIT_SCALE
                val arm = GRIP_ARM.toPx()
                val inset = stroke / 2f + 1.dp.toPx()
                val center = Offset(size.width - radius, size.height - radius)
                val r = radius - inset
                val path = Path().apply {
                    moveTo(center.x - arm, center.y + r)
                    lineTo(center.x, center.y + r)
                    arcTo(Rect(center, r), startAngleDegrees = 90f, sweepAngleDegrees = -90f, forceMoveTo = false)
                    lineTo(center.x + r, center.y - arm)
                }
                drawPath(path, colors.card.copy(alpha = 0.9f), style = Stroke(stroke + 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, colors.accent, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            },
    )
}

/**
 * Podgląd w czasie przeciągania rogu: obrys w kolorze akcentu tam, gdzie kafelek wyląduje po puszczeniu ([rect],
 * już przyciągnięty do siatki), z podpisem rozmiaru „4 × 3”. Nie przyjmuje dotyku.
 */
@Composable
private fun ResizePreview(rect: GridRect, size: TileSize, geometry: GridGeometry) {
    val colors = DomikTheme.colors
    Box(
        Modifier
            .offset(geometry.x(rect.col).dp, geometry.y(rect.row).dp)
            .size(geometry.width(rect.cols).dp, geometry.height(rect.rows).dp)
            .zIndex(20f)
            .clip(DomikShapes.tile)
            .background(colors.accent.copy(alpha = 0.14f))
            .border(3.dp, colors.accent, DomikShapes.tile),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.edit_size_value, size.cols, size.rows),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onAccent,
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.accent)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * Okrągły przycisk warstwy edycji ([buttonSize]). [touchSize] > [buttonSize] – pole dotyku większe niż rysunek
 * (przycisk jest rysowany pośrodku, wokół niego niewidoczny margines klikalny, z okrągłym efektem dotyku).
 * [contentDescription] – opis dla czytników ekranu (warstwa edycji jako całość ma jednak własne akcje dostępności).
 */
@Composable
private fun ChromeButton(
    iconRes: Int,
    buttonSize: Dp,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    touchSize: Dp = buttonSize,
) {
    val colors = DomikTheme.colors
    val icon = @Composable {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(colors.textPrimary),
            modifier = Modifier.size(if (buttonSize < CHROME_BUTTON) 16.dp else 20.dp),
        )
    }
    if (touchSize > buttonSize) {
        val fill = colors.card.copy(alpha = 0.94f)
        val rim = colors.textSecondary.copy(alpha = 0.30f)
        Box(
            modifier
                .size(touchSize)
                .clip(CircleShape)
                .drawBehind {
                    val radius = buttonSize.toPx() / 2f
                    drawCircle(fill, radius)
                    drawCircle(rim, radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                }
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) { icon() }
        return
    }
    Box(
        modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(colors.card.copy(alpha = 0.94f))
            .border(1.dp, colors.textSecondary.copy(alpha = 0.30f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
