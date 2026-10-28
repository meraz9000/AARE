package net.prototux.aare.ui.car

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens

/** YouTube is a phone media app, not a useful car taskbar shortcut. */
private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

/** The AARE Caster app that can sit beside Maps in the taskbar. */
private const val AARE_CASTER_PACKAGE = "dev.lab.ytcar"

/**
 * Root of the car screen: a rail on the driver's side and the content area.
 *
 * [appContent] renders an app full screen (templates or the media browser); [navMap] renders a
 * navigation app's map surface, both on the dashboard card and behind its templates.
 */
@Composable
fun CarShell(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    /** Drawn over everything but notices: the voice assistant's bar. */
    overlay: @Composable (Modifier) -> Unit = {},
) {
    if (state.carPlayStyle) {
        CarPlayShell(state, actions, appContent, navMap, overlay)
        return
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxHeight > maxWidth
            if (portrait || state.railBottom) {
                Column(Modifier.fillMaxSize()) {
                    ContentArea(state, actions, appContent, navMap, Modifier.weight(1f))
                    Rail(state, actions, horizontal = true)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (!state.railOnRight) Rail(state, actions, horizontal = false)
                    ContentArea(state, actions, appContent, navMap, Modifier.weight(1f))
                    if (state.railOnRight) Rail(state, actions, horizontal = false)
                }
            }
            overlay(Modifier.align(Alignment.BottomCenter))
            NoticeOverlay(state, actions, Modifier.align(Alignment.TopCenter))
            CallOverlay(state, actions, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun ContentArea(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
) {
    val gutter = LocalCarDimens.current.gutter
    AnimatedContent(
        targetState = state.screen,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        modifier = modifier.fillMaxHeight(),
        label = "screen",
    ) { screen ->
        when (screen) {
            CarScreen.Dashboard -> Box(Modifier.fillMaxSize()) {
                Wallpaper(state, Modifier.fillMaxSize())
                Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(gutter))
            }
            CarScreen.Launcher -> Launcher(state, actions, Modifier.fillMaxSize())
            is CarScreen.App -> {
                val app = state.app(screen.appId)
                if (app != null) appContent(app, Modifier.fillMaxSize()) else Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(gutter))
            }
            is CarScreen.Split -> SplitContent(
                state = state,
                screen = screen,
                appContent = appContent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------- split

/**
 * A Coolwalk-style split: the projected app is the primary pane at roughly two thirds of the
 * content width and the other app occupies the remaining third. Expanding the map slides the
 * secondary pane away, then gives the map the full content area. This is an original layout
 * inspired by observed metrics; it does not copy Android Auto's artwork or animation assets.
 */
@Composable
internal fun SplitContent(
    state: CarUiState,
    screen: CarScreen.Split,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val left = state.app(screen.leftId)
    val right = state.app(screen.rightId)
    if (left == null || right == null) {
        val only = left ?: right
        if (only != null) appContent(only, modifier.fillMaxSize())
        return
    }

    // Keep a map (the projected app) as the large pane even after a taskbar swap. For two
    // template apps the left app is primary, exactly as before.
    val primaryIsLeft = left.kind == CarAppKind.PROJECTED || right.kind != CarAppKind.PROJECTED
    val primary = if (primaryIsLeft) left else right
    val secondary = if (primaryIsLeft) right else left

    var expanded by remember(screen) { mutableStateOf(false) }
    var showSecondary by remember(screen) { mutableStateOf(true) }
    val slide = remember(screen) { Animatable(0f) }

    // SurfaceView-based apps (AARE Caster) need to sit above the projected map. Keep the map
    // full-width so its native touch region is not resized; the caster SurfaceView is raised
    // above it with media-overlay z-order instead of a separate Presentation window.
    if (secondary.packageName == "dev.lab.ytcar" &&
        (primary.kind != CarAppKind.PROJECTED ||
            LocalContext.current.display?.displayId == android.view.Display.DEFAULT_DISPLAY)) {
        // Phone preview cannot host a second Presentation window. Keep the simple side-by-side
        // fallback there; the car path below keeps the map full-width and overlays the caster.
        androidx.compose.foundation.layout.Row(modifier.fillMaxSize()) {
            appContent(
                primary,
                Modifier
                    .weight(if (state.splitExpanded) 1f else 0.68f)
                    .fillMaxHeight(),
            )
            if (!state.splitExpanded) {
                appContent(
                    secondary,
                    Modifier
                        .weight(0.32f)
                        .fillMaxHeight(),
                )
            }
        }
        return
    }

    LaunchedEffect(state.splitExpanded) {
        if (state.splitExpanded == expanded) return@LaunchedEffect
        if (state.splitExpanded) {
            // Retract the dashboard first, then hand the freed width to the map, so the
            // SurfaceView is resized once instead of on every animation frame.
            slide.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            expanded = true
            showSecondary = false
            slide.snapTo(0f)
        } else {
            // Restore the dashboard at the far edge, then slide it back in.
            expanded = false
            showSecondary = true
            slide.snapTo(1f)
            slide.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
    }

    // Keep the projected app at full content size and overlay the secondary pane on top.
    // This mirrors how a car head unit keeps the map surface full-width and covers its edge
    // with the dashboard; resizing the map's SurfaceControlViewHost breaks native map gestures.
    Box(modifier.fillMaxSize()) {
        appContent(primary, Modifier.fillMaxSize())
        if (showSecondary) {
            Box(
                Modifier
                    .align(if (primaryIsLeft) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.32f)
                    .graphicsLayer {
                        translationX = (if (primaryIsLeft) 1f else -1f) * slide.value * size.width
                        alpha = 1f - slide.value
                    },
            ) {
                appContent(secondary, Modifier.fillMaxSize())
            }
        }
    }
}

// ---------------------------------------------------------------- rail

/**
 * The rail, laid out like Android Auto's taskbar: the voice button at the driver's end with the
 * menu button (the app grid on the home screen, home anywhere else) next to it, the apps in the
 * middle (navigation, media, phone, settings), the clock and the phone's status at the far end.
 */
@Composable
private fun Rail(state: CarUiState, actions: CarActions, horizontal: Boolean) {
    // The "dark bar" setting: night colors for the rail even when the rest is in day colors.
    if (state.darkBar && !state.night) {
        CarTheme(night = true) { RailContent(state, actions, horizontal) }
    } else {
        RailContent(state, actions, horizontal)
    }
}

@Composable
private fun RailContent(state: CarUiState, actions: CarActions, horizontal: Boolean) {
    val d = LocalCarDimens.current
    val nav = state.app(state.activeNavAppId)
    // YouTube is exposed as a media app, but it is not a useful car taskbar shortcut. Keep it
    // launchable from the grid, just don't pin it to the rail.
    val youtube = state.apps.firstOrNull { it.packageName == YOUTUBE_PACKAGE }
    val media = state.app(state.activeMediaAppId)?.takeUnless { it.packageName == YOUTUBE_PACKAGE }
    // Maps uses the private projected path, not the navigation Car App Library, but it should
    // still have a permanent taskbar shortcut next to navigation.
    val maps = state.apps.firstOrNull { it.kind == CarAppKind.PROJECTED }
    val phone = state.apps.firstOrNull { it.kind == CarAppKind.PHONE }
    val settings = state.apps.firstOrNull { it.kind == CarAppKind.SETTINGS }
    val split = state.screen as? CarScreen.Split
    val splitHasProjected = split != null && listOfNotNull(
        state.app(split.leftId),
        state.app(split.rightId),
    ).any { it.kind == CarAppKind.PROJECTED }
    val mapToggle: @Composable () -> Unit = {
        if (splitHasProjected) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.splitExpanded) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable { actions.toggleSplitExpanded() }
                    .semantics {
                        contentDescription = if (state.splitExpanded) "Show apps" else "Expand map"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Map,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (state.splitExpanded) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    fun on(app: CarAppEntry?) = app != null && when (val screen = state.screen) {
        is CarScreen.App -> screen.appId == app.id
        is CarScreen.Split -> screen.contains(app.id)
        else -> false
    }
    val mapsShortcut: @Composable () -> Unit = {
        maps?.let { app ->
            RailApp(
                app = app,
                fallback = Icons.Rounded.Map,
                selected = on(app),
                onClick = { actions.taskbarClick(app) },
                onLongClick = { actions.taskbarLongPress(app) },
            )
        }
    }
    // Like Android Auto's taskbar: the apps used last, besides the fixed ones (one on a side rail, for height).
    val fixed = setOfNotNull(nav?.id, maps?.id, media?.id, phone?.id, settings?.id)
    val recent = state.recentAppIds
        .filter { it !in fixed && it != youtube?.id }
        .mapNotNull(state::app)
    // A bottom bar on a portrait screen is short of width (76 dp per button): fewer apps and no
    // weather there, the launcher has them all. Landscape bars keep everything.
    val conf = LocalConfiguration.current
    val tightBar = horizontal && conf.screenHeightDp > conf.screenWidthDp && conf.screenWidthDp < 800
    val tighterBar = tightBar && conf.screenWidthDp < 640
    val apps: @Composable (Int) -> Unit = { recentSlots ->
        val navIsCaster = nav?.packageName == AARE_CASTER_PACKAGE
        RailApp(
            app = nav,
            fallback = Icons.Rounded.Map,
            selected = on(nav),
            onClick = {
                when {
                    nav == null -> actions.openLauncher()
                    navIsCaster -> actions.taskbarClick(nav)
                    else -> actions.openApp(nav)
                }
            },
            onLongClick = nav?.let { entry -> { actions.taskbarLongPress(entry) } },
        )
        media?.let { app ->
            RailApp(app, Icons.Rounded.MusicNote, on(app), onClick = { actions.openApp(app) })
        }
        recent.take(recentSlots).forEach { app ->
            val isCaster = app.packageName == AARE_CASTER_PACKAGE
            RailApp(
                app = app,
                fallback = Icons.Rounded.Apps,
                selected = on(app),
                onClick = { if (isCaster) actions.taskbarClick(app) else actions.openApp(app) },
                onLongClick = if (isCaster) ({ actions.taskbarLongPress(app) }) else null,
            )
        }
        phone?.let { app -> RailApp(app, Icons.Rounded.Call, on(app), onClick = { actions.openApp(app) }) }
        settings?.takeIf { !tighterBar }?.let { app ->
            RailApp(app, Icons.Rounded.Settings, on(app), onClick = { actions.openApp(app) })
        }
    }
    val menu: @Composable () -> Unit = {
        if (state.screen == CarScreen.Dashboard) {
            RailButton(Icons.Rounded.Apps, "Apps", selected = false, onClick = { actions.openLauncher() })
        } else {
            RailButton(Icons.Rounded.Home, "Home", selected = false, onClick = { actions.goHome() })
        }
    }
    val mic: @Composable () -> Unit = { MicButton(state.listening) { actions.assistant() } }
    val bell = { state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp) }
    val container = MaterialTheme.colorScheme.surfaceContainerLowest
    if (horizontal) {
        Row(
            Modifier.fillMaxWidth().height(d.railWidth).background(container).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val driverEnd: @Composable () -> Unit = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                mapToggle()
                if (state.driverOnRight) { menu(); mic() } else { mic(); menu() }
            } }
            val status: @Composable () -> Unit = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Clock(); if (!tightBar) WeatherChip(state.weather); PhoneStatusIcons(); NotificationBell(state) { bell() }
            } }
            if (state.driverOnRight) status() else driverEnd()
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    apps(if (tightBar) 0 else 2)
                    mapsShortcut()
                }
            }
            if (state.driverOnRight) driverEnd() else status()
        }
    } else {
        Column(
            Modifier.fillMaxHeight().width(d.railWidth).background(container).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock()
            Spacer(Modifier.height(6.dp))
            PhoneStatusIcons()
            Spacer(Modifier.height(4.dp))
            WeatherChip(state.weather)
            NotificationBell(state) { bell() }
            mapToggle()
            Spacer(Modifier.height(4.dp))
            // On short displays the fixed buttons plus the app shortcuts are taller than the
            // rail. Keep the menu/mic pinned and let the app list scroll instead of clipping them.
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // A side rail has room for a recent app only on tall screens.
                apps(if (LocalConfiguration.current.screenHeightDp >= 800) 1 else 0)
            }
            mapsShortcut()
            Spacer(Modifier.height(4.dp))
            menu()
            Spacer(Modifier.height(8.dp))
            mic()
        }
    }
}

@Composable
internal fun Clock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000 + 5)
        }
    }
    // Hours and minutes only: "AM/PM" doesn't fit the rail, and the car shows the period anyway.
    val text = android.text.format.DateFormat.format(
        if (android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)) "H:mm" else "h:mm", now,
    ).toString()
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val d = LocalCarDimens.current
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val interaction = if (onLongClick == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClickLabel = "Split screen",
            onLongClick = onLongClick,
        )
    }
    Box(
        Modifier.size(d.touchTarget).clip(RoundedCornerShape(24.dp)).background(bg).then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(d.iconSize))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailApp(
    app: CarAppEntry?,
    fallback: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val d = LocalCarDimens.current
    if (app?.icon == null) {
        RailButton(fallback, app?.label ?: "", selected, onClick, onLongClick)
        return
    }
    val ring = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val interaction = if (onLongClick == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClickLabel = "Split screen",
            onLongClick = onLongClick,
        )
    }
    Box(
        Modifier.size(d.touchTarget).clip(RoundedCornerShape(24.dp)).background(ring).then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Image(app.icon.asImageBitmap(), app.label, Modifier.size(48.dp).clip(CircleShape))
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val bg = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(d.touchTarget).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = "Assistant", tint = fg, modifier = Modifier.size(d.iconSize))
    }
}

// ---------------------------------------------------------------- dashboard

@Composable
internal fun Dashboard(
    state: CarUiState,
    actions: CarActions,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
) {
    val gutter = LocalCarDimens.current.gutter
    BoxWithConstraints(modifier) {
        val narrow = maxWidth < 560.dp
        if (maxHeight > maxWidth * 1.2f && maxHeight >= 700.dp) {
            // portrait screens (720x1280, 1080x1920…): the cards stacked, the map on top
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1.6f).fillMaxWidth())
                MediaCard(state, actions, compact = false, modifier = Modifier.weight(1f).fillMaxWidth())
                val event = state.nextEvent
                val latest = state.history.firstOrNull()
                when {
                    event != null -> Card(Modifier.fillMaxWidth().height(180.dp)) { CalendarCard(event, actions::navigateTo, Modifier.fillMaxSize()) }
                    latest != null -> Card(Modifier.fillMaxWidth().height(180.dp), onClick = {
                        state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp)
                    }) { LatestNotification(latest, state.drivingRestricted) }
                }
            }
        } else if (narrow) {
            // small screens: one big card, media as a strip under it
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1f).fillMaxWidth())
                MediaCard(state, actions, compact = true, modifier = Modifier.fillMaxWidth().height(112.dp))
            }
        } else if (maxWidth > maxHeight * 2.1f) {
            // very wide screens (1920x720, 2560x720…): three cards side by side, like Android Auto's
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1.6f).fillMaxHeight())
                MediaCard(state, actions, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                val event = state.nextEvent
                val latest = state.history.firstOrNull()
                when {
                    event != null -> Card(Modifier.weight(0.9f).fillMaxHeight()) { CalendarCard(event, actions::navigateTo, Modifier.fillMaxSize()) }
                    latest != null -> Card(Modifier.weight(0.9f).fillMaxHeight(), onClick = {
                        state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp)
                    }) { LatestNotification(latest, state.drivingRestricted) }
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1.65f).fillMaxHeight())
                val event = state.nextEvent
                if (event == null) {
                    MediaCard(state, actions, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                } else {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                        MediaCard(state, actions, compact = false, modifier = Modifier.weight(1.3f).fillMaxWidth())
                        Card(Modifier.weight(1f).fillMaxWidth()) { CalendarCard(event, actions::navigateTo, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val r = LocalCarDimens.current.cardRadius
    Box(
        modifier
            .clip(RoundedCornerShape(r))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) { content() }
}

@Composable
private fun NavCard(state: CarUiState, actions: CarActions, navMap: @Composable (CarAppEntry, Modifier) -> Unit, modifier: Modifier) {
    val nav = state.app(state.activeNavAppId)
    val guidance = state.phoneGuidance
    Card(modifier) {
        if (guidance != null) {
            GuidanceCard(guidance)
        } else if (nav != null) {
            navMap(nav, Modifier.fillMaxSize())
        } else {
            val navApps = state.apps.filter { it.kind == CarAppKind.NAVIGATION }
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Map, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(if (navApps.isEmpty()) "No navigation app" else "Where to?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (navApps.isEmpty()) "Install a navigation app that supports Android Auto on the phone" else "Choose your maps app",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    navApps.take(4).forEach { app -> AppChip(app) { actions.openApp(app) } }
                }
            }
        }
    }
}

/** Directions from a navigation app running on the phone (Google Maps, Waze…), from its notification. */
@Composable
private fun GuidanceCard(g: PhoneGuidance) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            g.arrow?.let { arrow ->
                Image(arrow.asImageBitmap(), null, Modifier.size(112.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface, BlendMode.SrcIn))
                Spacer(Modifier.width(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(g.primary, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (g.secondary.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(g.secondary, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (g.detail.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(g.detail, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text("${g.appLabel}, on the phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppChip(app: CarAppEntry, onClick: () -> Unit) {
    Row(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick).padding(start = 10.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, 44)
        Spacer(Modifier.width(12.dp))
        Text(app.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun AppIcon(app: CarAppEntry, sizeDp: Int) {
    if (app.icon != null) {
        Image(app.icon.asImageBitmap(), app.label, Modifier.size(sizeDp.dp).clip(CircleShape))
    } else {
        Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun MediaCard(state: CarUiState, actions: CarActions, compact: Boolean, modifier: Modifier) {
    val np = state.nowPlaying
    val media = state.app(np?.appId ?: state.activeMediaAppId)
    Card(modifier, onClick = { media?.let(actions::openApp) ?: actions.openLauncher() }) {
        if (np?.art != null && !compact) {
            Image(np.art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer))))
        }
        val content: @Composable () -> Unit = {
            if (np != null) {
                Text(np.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(media?.label ?: "Music", style = MaterialTheme.typography.titleMedium)
                Text("Nothing playing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (compact) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(np, 80)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { content() }
                MediaButtons(np, actions)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Art(np, 72)
                    Spacer(Modifier.width(16.dp))
                    if (media != null) AppIcon(media, 28)
                }
                Spacer(Modifier.weight(1f))
                content()
                Spacer(Modifier.height(12.dp))
                if (np != null && np.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { MediaButtons(np, actions) }
            }
        }
    }
}

@Composable
private fun Art(np: NowPlaying?, sizeDp: Int) {
    val shape = RoundedCornerShape(16.dp)
    if (np?.art != null) {
        Image(np.art.asImageBitmap(), null, Modifier.size(sizeDp.dp).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size((sizeDp / 2).dp))
        }
    }
}

@Composable
private fun MediaButtons(np: NowPlaying?, actions: CarActions) {
    RoundIcon(Icons.Rounded.SkipPrevious, "Previous", filled = false) { actions.mediaPrevious() }
    RoundIcon(if (np?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", filled = true) { actions.mediaPlayPause() }
    RoundIcon(Icons.Rounded.SkipNext, "Next", filled = false) { actions.mediaNext() }
}

@Composable
fun RoundIcon(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val bg = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(d.touchTarget).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(d.iconSize))
    }
}

// ---------------------------------------------------------------- launcher

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Launcher(state: CarUiState, actions: CarActions, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Already in the user's order (CarRuntime.applyApps).
        items(state.apps, key = { it.id }) { app ->
            Column(
                Modifier.clip(RoundedCornerShape(24.dp))
                    .combinedClickable(
                        onClick = { actions.openApp(app) },
                        onLongClickLabel = "Split screen",
                        onLongClick = { actions.openInSplit(app) },
                    )
                    .semantics { contentDescription = app.label }.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(app, 80)
                Spacer(Modifier.height(10.dp))
                Text(app.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- notices

@Composable
internal fun NoticeOverlay(state: CarUiState, actions: CarActions, modifier: Modifier) {
    val notice = state.notices.firstOrNull() ?: return
    LaunchedEffect(notice.key) {
        delay(8_000)
        actions.dismissNotice(notice)
    }
    Row(
        modifier.padding(16.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (notice.icon != null) {
            Image(notice.icon.asImageBitmap(), null, Modifier.size(56.dp).aspectRatio(1f).clip(CircleShape))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(notice.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // While driving a message shows who wrote, not what: it can be read aloud instead.
            Text(if (state.drivingRestricted && notice.isMessage) "New message" else notice.text,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice.actions.forEach { (label, run) ->
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { run(); actions.dismissNotice(notice) }.padding(16.dp))
        }
        RoundIcon(Icons.Rounded.Close, "Dismiss", filled = false) { actions.dismissNotice(notice) }
    }
}
