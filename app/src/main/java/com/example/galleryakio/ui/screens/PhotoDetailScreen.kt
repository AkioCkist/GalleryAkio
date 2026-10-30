package com.example.galleryakio.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import com.example.galleryakio.data.DetailSection
import com.example.galleryakio.data.VideoLeaveAction
import com.example.galleryakio.ui.components.rememberInPictureInPicture
import com.example.galleryakio.data.canReadMediaLocation
import com.example.galleryakio.data.readMediaDetails
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import com.example.galleryakio.ui.components.BackdropSource
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import com.example.galleryakio.ui.components.GlassDialog
import com.example.galleryakio.ui.components.GlassDialogButton
import com.example.galleryakio.ui.components.GlassMenu
import com.example.galleryakio.ui.components.GlassMenuItem
import com.example.galleryakio.ui.components.GlassSheet
import com.example.galleryakio.ui.components.GlassTextField
import com.example.galleryakio.ui.components.LocalGlassContent
import androidx.compose.foundation.shape.CircleShape
import com.example.galleryakio.ui.components.liquidGlass
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import com.example.galleryakio.ui.components.LocalViewerZoom
import com.example.galleryakio.ui.components.ViewerZoom
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import coil3.SingletonImageLoader
import com.example.galleryakio.ui.components.LocalGridLayout
import com.example.galleryakio.ui.components.TileBounds
import com.example.galleryakio.ui.components.ViewerTransitionLayer
import com.example.galleryakio.ui.components.cachedThumbnail
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.galleryakio.R
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.ui.GalleryViewModel
import com.example.galleryakio.ui.MediaSource
import com.example.galleryakio.ui.components.VideoControlsHost
import com.example.galleryakio.ui.components.VideoPlayer
import com.example.galleryakio.ui.components.ZoomableImage
import com.example.galleryakio.ui.components.formatDuration
import com.example.galleryakio.ui.components.formatSize
import com.example.galleryakio.ui.components.rememberConsentLauncher
import com.example.galleryakio.ui.components.shareItems
import com.example.galleryakio.ui.theme.CqMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhotoDetailScreen(
    viewModel: GalleryViewModel,
    initialId: Long,
    source: MediaSource,
    onBack: () -> Unit,
    /** The navigation transition entering / leaving this screen, which drives the open and close motion. */
    transition: Transition<EnterExitState>? = null,
) {
    val context = LocalContext.current
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val items = remember(library, source, settings.albumSortOrder, settings.favoriteIds) {
        when (source) {
            MediaSource.All -> library.visible
            MediaSource.Favorites -> viewModel.favorites()
            MediaSource.Videos -> library.visible.filter { it.isVideo }
            MediaSource.Custom -> library.all.associateBy { it.id }.let { byId -> viewModel.customList.mapNotNull { byId[it.id] } }
            is MediaSource.Album -> viewModel.albumItems(source.relativePath, settings.albumSortOrder)
        }
    }
    if (items.isEmpty()) {
        if (!library.isLoading) LaunchedEffect(Unit) { onBack() }
        return
    }

    val startIndex = remember { items.indexOfFirst { it.id == initialId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    val current = items.getOrNull(pager.currentPage.coerceIn(0, items.lastIndex)) ?: return

    var overlay by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }

    ImmersiveMode(fullscreen = { !overlay })
    MaxBrightness(enabled = settings.maxBrightnessViewing)

    val trashConsent = rememberConsentLauncher { }

    val density = LocalDensity.current
    var landscape by remember { mutableStateOf(false) }
    // Animated from an effect rather than animateFloatAsState, which would read `overlay`
    // here and recompose the whole viewer (pager included) on every tap.
    val overlayAlpha = remember { Animatable(1f) }
    val barsOn by remember { derivedStateOf { overlay || overlayAlpha.value > 0f } }
    LaunchedEffect(Unit) {
        snapshotFlow { overlay }.collectLatest { shown -> overlayAlpha.animateTo(if (shown) 1f else 0f, tween(if (shown) 150 else 200)) }
    }
    ForcedLandscape(landscape)

    // Open / close: the photo grows out of its grid tile and shrinks back into the tile of
    // whichever photo is showing on close. Only progress is animated; everything that reads it
    // does so while drawing, so the motion never recomposes or re-lays out anything.
    val tileCornerPx = with(density) { LocalGridLayout.current.style.cornerRadius.dp.toPx() }
    val progressState = transition?.animateFloat(
        transitionSpec = { spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TRANSITION_STIFFNESS) },
        label = "viewer_open",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val progress = { progressState?.value ?: 1f }
    val closing = transition?.targetState == EnterExitState.PostExit
    val inPip by rememberInPictureInPicture()
    val opened = transition == null || transition.currentState == EnterExitState.Visible
    // The grid's thumbnail is what moves; without one in memory the viewer just fades.
    val thumb = remember(current.id) { SingletonImageLoader.get(context).memoryCache?.let { cachedThumbnail(it, current.id, 1024) } }
    // The pager (and for a video, its player) is only built once the photo has finished
    // growing: composing it mid-motion would cost frames of the motion.
    var pagerReady by remember { mutableStateOf(transition == null || thumb == null) }
    var handedOff by remember { mutableStateOf(pagerReady) }
    LaunchedEffect(opened) {
        if (!opened) return@LaunchedEffect
        pagerReady = true
        // Keep the moving thumbnail on top until the pager has drawn the same picture under it.
        withFrameNanos {}
        withFrameNanos {}
        handedOff = true
    }

    // Swipe down to close: the photo follows the finger and shrinks while the black fades.
    // Let go far or fast enough and the close motion carries on from wherever it was left.
    // Like progress, the offset is only read while drawing.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var viewerHeight by remember { mutableFloatStateOf(1f) }
    val dragFraction = { (dragOffset.y / (viewerHeight / 2)).coerceIn(0f, 1f) }
    val dragScale = { 1f - DRAG_SHRINK * dragFraction() }
    val dismissDistance = with(density) { DISMISS_DISTANCE.toPx() }
    val dismissVelocity = with(density) { DISMISS_VELOCITY.toPx() }
    val scope = rememberCoroutineScope()
    val zoom = remember { ViewerZoom() }
    val videoControls = remember { VideoControlsHost() }
    val backdrop = rememberLayerBackdrop {
        drawRect(Color.Black)
        drawContent()
    }
    val latestOnBack by rememberUpdatedState(onBack)
    // Remembered once: pointerInput restarts its gesture whenever its lambda changes, and a
    // fresh lambda each recomposition cut every swipe off after its first couple of events.
    // Everything it reads is state, so it never goes stale.
    val dismissGesture = remember {
        Modifier.swipeToDismiss(
            enabled = { handedOff && !zoom.zoomed && transition?.targetState != EnterExitState.PostExit },
            onDrag = { dragOffset = it },
            onRelease = { velocity ->
                if (dragOffset.y > dismissDistance || velocity.y > dismissVelocity) latestOnBack()
                else scope.launch {
                    animate(Offset.VectorConverter, dragOffset, Offset.Zero, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                        dragOffset = v
                    }
                }
            },
        )
    }

    CompositionLocalProvider(LocalViewerZoom provides zoom) {
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewerHeight = it.height.toFloat() }
            .drawBehind { drawRect(Color.Black, alpha = progress() * (1f - DRAG_FADE * dragFraction())) },
    ) {
        // What the glass controls blur, recorded only while they show: a hidden HUD over a
        // playing video then costs nothing per frame.
        if (pagerReady) BackdropSource(backdrop, enabled = { barsOn && !closing && !inPip }, modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = 1,
            userScrollEnabled = !inPip,
            modifier = Modifier
                .fillMaxSize()
                .then(dismissGesture)
                .graphicsLayer {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = dragScale()
                    scaleY = dragScale()
                    alpha = when {
                        thumb == null -> progress()
                        closing -> 0f
                        else -> 1f
                    }
                },
        ) { page ->
            val item = items[page]
            val isCurrent = page == pager.currentPage
            if (item.isVideo) {
                VideoPlayer(
                    item = item,
                    isCurrentPage = isCurrent,
                    closing = closing,
                    rememberPosition = settings.rememberVideoPosition,
                    loop = settings.videoLoop,
                    onLoopChange = viewModel.settingsRepo::setVideoLoop,
                    leaveAction = settings.videoLeaveAction,
                    controlsVisible = overlay,
                    onControlsChange = { overlay = it },
                    controlsHost = videoControls,
                )
            } else {
                ZoomableImage(item, isCurrentPage = isCurrent, onTap = { overlay = !overlay })
            }
        }
        }
        val flying = thumb != null && (!handedOff || closing)
        // The tile the photo flies out of (or back into) stays empty meanwhile.
        SideEffect { TileBounds.hiddenId = if (flying) current.id else TileBounds.NO_ID }
        DisposableEffect(Unit) { onDispose { TileBounds.hiddenId = TileBounds.NO_ID } }
        if (flying) {
            ViewerTransitionLayer(
                bitmap = thumb,
                progress = progress,
                tile = { TileBounds.find(current.id) },
                tileCornerPx = tileCornerPx,
                dragOffset = { dragOffset },
                dragScale = dragScale,
            )
        }
        // The bars get out of the way as soon as a swipe down starts, and at once on closing:
        // fading along with the close, their glass took 40-65 ms of every frame of the motion.
        val barsAlpha = {
            if (closing || inPip) 0f else overlayAlpha.value * progress() * (1f - (dragOffset.y / dismissDistance).coerceIn(0f, 1f))
        }

        // Floating liquid-glass controls, matching the video player's: the photo shows through
        // them (blurred and bent at the edges), so no scrims are needed to keep them readable.
        FadingOverlay({ overlay }, barsAlpha, onReveal = { overlay = true }, modifier = Modifier.align(Alignment.TopCenter)) {
            TopBar(
                backdrop = backdrop,
                item = current,
                landscape = landscape,
                leaveAction = settings.videoLeaveAction,
                onLeaveActionChange = viewModel.settingsRepo::setVideoLeaveAction,
                onBack = onBack,
                onRotate = { landscape = !landscape },
                onDetails = { showInfo = true },
                onRename = { renaming = true },
            )
        }
        FadingOverlay({ overlay }, barsAlpha, onReveal = { overlay = true }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The current video's controls and filmstrip, glass over the same backdrop as the bars.
                val controls = videoControls.content
                if (current.isVideo) {
                    if (controls != null) controls(backdrop) else Spacer(Modifier.height(VIDEO_CONTROLS_HEIGHT))
                }
                BottomBar(
                    backdrop = backdrop,
                    isFavorite = current.id in settings.favoriteIds,
                    onFavorite = { viewModel.settingsRepo.toggleFavorite(current.id) },
                    onDetails = { showInfo = true },
                    onShare = { shareItems(context, listOf(current)) },
                    onDelete = { confirmTrash = true },
                )
            }
        }
    }
    }

    if (confirmTrash) {
        GlassDialog(
            onDismiss = { confirmTrash = false },
            title = "MOVE TO TRASH?",
            buttons = {
                GlassDialogButton("Cancel", onClick = { confirmTrash = false })
                GlassDialogButton("Trash", color = TrashRed, onClick = {
                    confirmTrash = false
                    trashConsent(viewModel.trashRequest(listOf(current)))
                })
            },
        ) {
            Text(current.displayName, color = LocalGlassContent.current.copy(alpha = 0.75f), fontSize = 14.sp)
        }
    }
    if (showInfo) InfoSheet(current, onDismiss = { showInfo = false })
    if (renaming) RenameDialog(viewModel, current, onDismiss = { renaming = false })
}

private val dayFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private val VIDEO_CONTROLS_HEIGHT = 120.dp
private val TrashRed = Color(0xFFFF5A5A)
/** Glass buttons and pills: the video player's control size. */
private val GLASS_SIZE = 36.dp
private val GLASS_BAR_HEIGHT = 48.dp
/** Open/close spring: critically damped, about a third of a second. */
private const val TRANSITION_STIFFNESS = 420f
/** How far down (or how fast) a swipe must go to close the viewer instead of springing back. */
private val DISMISS_DISTANCE = 120.dp
private val DISMISS_VELOCITY = 1200.dp
/** At half the screen's height of swipe: the photo is this much smaller and the black this much lighter. */
private const val DRAG_SHRINK = 0.35f
private const val DRAG_FADE = 0.9f

/**
 * One-finger swipe down. Watched on the Initial pass, ahead of the pager and the photo: once
 * the finger has moved past touch slop mostly downwards, the drag is claimed and consumed so
 * neither reacts. Sideways moves are left to the pager; [enabled] is false while zoomed (the
 * photo pans instead) and a second finger hands the gesture to pinch.
 */
private fun Modifier.swipeToDismiss(
    enabled: () -> Boolean,
    onDrag: (Offset) -> Unit,
    onRelease: (Velocity) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled()) return@awaitEachGesture
        var total = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed || event.changes.count { it.pressed } > 1) return@awaitEachGesture
            total += change.positionChange()
            if (total.getDistance() > viewConfiguration.touchSlop) {
                if (total.y > 0f && total.y > abs(total.x)) break else return@awaitEachGesture
            }
        }
        val tracker = VelocityTracker()
        var offset = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPointerInputChange(change)
            // Read the move before consuming: a consumed change reports no movement.
            val delta = change.positionChange()
            event.changes.forEach { it.consume() }
            if (!change.pressed) break
            offset += delta
            onDrag(offset)
        }
        onRelease(tracker.calculateVelocity())
    }
}

/**
 * Bars that stay composed and only fade. Composing them on every tap (as AnimatedVisibility
 * does) cost a long frame each time, a stutter in a playing video. While hidden, a tap on
 * the bar's area reveals the bars instead of pressing an invisible button.
 */
@Composable
private fun FadingOverlay(
    visible: () -> Boolean,
    alpha: () -> Float,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.graphicsLayer {
            this.alpha = alpha()
            // Fade each draw instead of the group through an offscreen layer: allocating
            // those (one per glass control) made the first frame of every fade a long one.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        content()
        // Read here, not by the caller: a toggle then recomposes only this one Box.
        if (!visible()) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onReveal() } })
    }
}

/**
 * Floating glass controls: a back button, the date in a pill (photos), and rotate (videos)
 * and the overflow menu on the right. Same 36dp glass as the video player's controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(
    backdrop: Backdrop,
    item: MediaItem,
    landscape: Boolean,
    leaveAction: VideoLeaveAction,
    onLeaveActionChange: (VideoLeaveAction) -> Unit,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            // Padded for the status bar even while it is hidden: following it as it slides in
            // and out re-laid out (and re-blurred) the bars on every frame of the HUD fade.
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassButton(backdrop, painterResource(R.drawable.back2), "Back", onClick = onBack)
        if (!item.isVideo) {
            val date = Date(item.dateTaken)
            Row(
                Modifier
                    .height(GLASS_SIZE)
                    .liquidGlass(backdrop, darkMode = true, lensHeight = 6.dp, lensAmount = 12.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dayFormat.format(date), color = Color.White, fontSize = 13.sp, maxLines = 1)
                Text("  ·  " + timeFormat.format(date), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        if (item.isVideo) {
            GlassButton(
                backdrop,
                rememberVectorPainter(Icons.Rounded.ScreenRotation),
                if (landscape) "Portrait" else "Rotate to landscape",
                onClick = onRotate,
            )
        }
        Box {
            GlassButton(backdrop, painterResource(R.drawable.more2), "More") { menu = true }
            GlassMenu(expanded = menu, onDismiss = { menu = false }) {
                MoreItem(R.drawable.info, "DETAILS") { menu = false; onDetails() }
                if (item.isVideo) {
                    // Stays open on a tap: it steps through the choices and shows the one picked.
                    GlassMenuItem(rememberVectorPainter(Icons.AutoMirrored.Rounded.ExitToApp), "WHEN LEAVING", leaveAction.label) {
                        val all = VideoLeaveAction.entries
                        onLeaveActionChange(all[(leaveAction.ordinal + 1) % all.size])
                    }
                }
                if (!item.isVideo) {
                    MoreItem(R.drawable.wallpaper, "SET WALLPAPER") {
                        menu = false
                        context.startChooserSafely(attachIntent(item), "Set as:")
                    }
                    MoreItem(R.drawable.contact, "CONTACT PHOTO") {
                        menu = false
                        context.startChooserSafely(attachIntent(item), "Set as:")
                    }
                    MoreItem(R.drawable.lens, "GOOGLE LENS") {
                        menu = false
                        openGoogleLens(context, item)
                    }
                }
                MoreItem(R.drawable.rename, "RENAME") { menu = false; onRename() }
            }
        }
    }
}

/** Round glass button, the size of the video player's controls. */
@Composable
private fun GlassButton(backdrop: Backdrop, painter: Painter, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(GLASS_SIZE)
            .liquidGlass(backdrop, darkMode = true, lensHeight = 6.dp, lensAmount = 12.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, description, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** The actions in one compact glass capsule, centred above the navigation bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBar(
    backdrop: Backdrop,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(top = 8.dp, bottom = 12.dp)) {
        Row(
            Modifier
                .height(GLASS_BAR_HEIGHT)
                .liquidGlass(backdrop, darkMode = true)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(
                rememberVectorPainter(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder),
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) Color(0xFFFF4D4D) else Color.White,
                onClick = onFavorite,
            )
            BarButton(painterResource(R.drawable.info), "Details", onClick = onDetails)
            BarButton(painterResource(R.drawable.share), "Share", onClick = onShare)
            BarButton(painterResource(R.drawable.delete), "Delete", onClick = onDelete)
        }
    }
}

@Composable
private fun BarButton(painter: Painter, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 52.dp, height = GLASS_BAR_HEIGHT)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Locks the screen to landscape while [enabled]; back to the user's rotation setting otherwise. */
@Composable
private fun ForcedLandscape(enabled: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(enabled) {
        activity.requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

@Composable
private fun MoreItem(icon: Int, label: String, onClick: () -> Unit) {
    GlassMenuItem(painterResource(icon), label, onClick = onClick)
}

private fun attachIntent(item: MediaItem) = Intent(Intent.ACTION_ATTACH_DATA).apply {
    setDataAndType(item.uri, item.mimeType)
    putExtra("mimeType", item.mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Same fallback as the original openGoogleLens: open Lens, else its Play Store page. */
private fun openGoogleLens(context: Context, item: MediaItem) {
    val lens = Intent(Intent.ACTION_SEND).apply {
        setPackage("com.google.ar.lens")
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(lens)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Google Lens not installed. Opening Play Store...", Toast.LENGTH_SHORT).show()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.ar.lens")))
        }
    }
}

private fun Context.startChooserSafely(intent: Intent, title: String) {
    try {
        startActivity(Intent.createChooser(intent, title))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app can handle this", Toast.LENGTH_SHORT).show()
    }
}

/** Asked once per run, the first time details open: Android hides a file's GPS data without it. */
private var askedMediaLocation = false

@Composable
private fun InfoSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var canLocate by remember { mutableStateOf(canReadMediaLocation(context)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canLocate = it }
    LaunchedEffect(Unit) {
        if (!canLocate && !askedMediaLocation && Build.VERSION.SDK_INT >= 29) {
            askedMediaLocation = true
            askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    val details by produceState(emptyList<DetailSection>(), item.id, canLocate) {
        value = withContext(Dispatchers.IO) { readMediaDetails(context, item) }
    }
    val dateFmt = remember { SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()) }
    GlassSheet(onDismiss = onDismiss) {
        Column {
            Text("DETAILS", fontFamily = CqMono, fontSize = 18.sp, color = LocalGlassContent.current)
            Spacer(Modifier.height(12.dp))
            InfoRow("Name", item.displayName)
            InfoRow("Date", dateFmt.format(Date(item.dateTaken)))
            InfoRow("Size", formatSize(item.size))
            if (item.width > 0 && item.height > 0) {
                InfoRow("Resolution", "${item.width} × ${item.height} (%.1f MP)".format(item.width * item.height / 1_000_000f))
            }
            item.duration?.let { InfoRow("Duration", formatDuration(it)) }
            InfoRow("Type", item.mimeType)
            InfoRow("Path", item.relativePath.ifEmpty { item.bucketName })
            details.forEach { section ->
                Spacer(Modifier.height(14.dp))
                Text(section.title.uppercase(), fontFamily = CqMono, fontSize = 14.sp, color = LocalGlassContent.current)
                Spacer(Modifier.height(4.dp))
                section.rows.forEach { (label, value) -> InfoRow(label, value) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), fontFamily = CqMono, fontSize = 12.sp, color = LocalGlassContent.current.copy(alpha = 0.55f), modifier = Modifier.width(100.dp))
        Text(value, fontSize = 13.sp, color = LocalGlassContent.current)
    }
}

private val InvalidNameChars = Regex("[\\\\/:*?\"<>|]")

@Composable
private fun RenameDialog(viewModel: GalleryViewModel, item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ext = item.displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var name by remember { mutableStateOf(item.displayName.removeSuffix(ext)) }
    val error = when {
        name.isBlank() -> "File name cannot be empty"
        name.length > 200 -> "File name too long"
        InvalidNameChars.containsMatchIn(name) -> "Invalid characters in file name"
        else -> null
    }

    fun doRename() = scope.launch {
        val ok = viewModel.rename(item, name.trim() + ext)
        Toast.makeText(context, if (ok) "Renamed to: ${name.trim()}$ext" else "Rename failed", Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    val consent = rememberConsentLauncher { approved -> if (approved) doRename() else onDismiss() }

    val submit = { if (error == null) consent(viewModel.writeRequest(listOf(item))) }
    GlassDialog(
        onDismiss = onDismiss,
        title = "RENAME",
        buttons = {
            GlassDialogButton("Cancel", onClick = onDismiss)
            GlassDialogButton("Rename", enabled = error == null, onClick = submit)
        },
    ) {
        val content = LocalGlassContent.current
        GlassTextField(name, { name = it }, suffix = ext, isError = error != null, onDone = submit)
        Spacer(Modifier.height(6.dp))
        Text(error ?: "The extension ($ext) is kept", fontSize = 11.sp, color = if (error != null) TrashRed else content.copy(alpha = 0.55f))
    }
}

/** Hides the system bars while [fullscreen]; read in an effect, so a toggle recomposes nothing. */
@Composable
private fun ImmersiveMode(fullscreen: () -> Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    val controller = remember(activity) { WindowCompat.getInsetsController(activity.window, activity.window.decorView) }
    LaunchedEffect(controller) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        snapshotFlow(fullscreen).collect {
            if (it) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(controller) { onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) } }
}

@Composable
private fun MaxBrightness(enabled: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(enabled) {
        val window = activity.window
        val original = window.attributes.screenBrightness
        if (enabled) {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = original }
        }
    }
}
