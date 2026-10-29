package net.prototux.aare.host

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.location.Location
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.navigation.model.Trip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.car.Calls
import net.prototux.aare.car.DashboardInfo
import net.prototux.aare.media.MediaHub
import net.prototux.aare.mirror.MirrorScreen
import net.prototux.aare.notify.CarNotificationListener
import net.prototux.aare.notify.MessagingScreen
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.car.CarActions
import net.prototux.aare.ui.car.CarAppEntry
import net.prototux.aare.ui.car.CarAppKind
import net.prototux.aare.ui.car.CarCall
import net.prototux.aare.ui.car.CarNotice
import net.prototux.aare.ui.car.CarScreen
import net.prototux.aare.ui.car.CarSettingsScreen
import net.prototux.aare.ui.car.CarShell
import net.prototux.aare.ui.car.CarUiState
import net.prototux.aare.ui.car.NotificationsScreen
import net.prototux.aare.ui.car.NowPlaying
import net.prototux.aare.ui.car.PhoneScreen
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Everything on the car screen that doesn't depend on how it reaches the car: the app list,
 * hosted car apps, media, notices and the root composable. A projection session (real head unit)
 * and the on-phone preview both drive one of these.
 *
 * Main thread only, except the properties documented as thread-safe for [HostEnvironment].
 */
class CarRuntime(
    val context: Context,
    private val scope: CoroutineScope,
    val settings: Settings,
    val media: MediaHub,
    /** Release [media] in [stop]: true when this runtime created it (the preview), false when shared. */
    private val ownsMedia: Boolean = true,
    /** The car session's runtime (false for the on-phone preview): it takes [active] over a preview. */
    private val primary: Boolean = true,
    /** Offer the "Phone screen" entry at all (off for sessions from the developer TCP server unless opted in). */
    private val phoneScreenAllowed: Boolean = true,
) : HostEnvironment {

    val ui = CarUiState()
    val icons = IconLoader(context, this)
    private val main = Handler(Looper.getMainLooper())

    /** Hosted car app connections by entry id. */
    private val connections = mutableStateMapOf<String, CarAppConnection>()
    private val carApps = HashMap<String, CarAppInfo>()
    private val projectedApps = HashMap<String, ProjectedAppInfo>()
    private val visibleCount = HashMap<CarAppConnection, Int>()

    /** The private projected-activity host on screen, if any (Maps and other legacy GMS apps). */
    internal var activeProjectedHost: ProjectedHost? = null

    /** Hooks the projection session sets: navigation status to the cluster, microphone, etc. */
    var onTrip: ((CarAppConnection, Trip?) -> Unit)? = null
    var microphone: ((sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> MicHandle?)? = null
    var onAssistant: (() -> Unit)? = null
    /** The screen-capture consent (car audio), which also lets the phone screen be mirrored. */
    var projection: () -> MediaProjection? = { null }
    /** Guidance from a navigation app on the phone (Google Maps, Waze…) changed, or ended (null). */
    var onPhoneGuidance: ((PhoneGuidance?) -> Unit)? = null

    // ------------------------------------------------------------------ HostEnvironment

    override val driving: Boolean get() = ui.drivingRestricted
    override val night: Boolean get() = ui.night
    override var screenWidthDp by mutableIntStateOf(1280)
    override var screenHeightDp by mutableIntStateOf(720)
    override var densityDpi by mutableIntStateOf(160)

    /**
     * True when touches on this UI arrive as real input through InputDispatcher, so they can
     * reach an embedded SurfaceControlViewHost child (projected Maps). That is the case on the
     * phone preview and on a car display backed by a VirtualTouchscreen. When false, car touches
     * are synthetic View dispatches and projected apps must use the legacy touch transaction.
     */
    var nativeTouchInput = true
    @Volatile override var carLocation: Location? = null
    @Volatile override var carHeading: Float? = null

    override fun contentLimit(type: Int): Int = when (type) {
        // Android Auto's usual limits: generous when parked, shorter lists while driving.
        ConstraintManager.CONTENT_LIMIT_TYPE_LIST -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_GRID -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST -> 6
        ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST -> 3
        ConstraintManager.CONTENT_LIMIT_TYPE_PANE -> 4
        else -> 6
    }

    override fun toast(app: CarAppConnection, text: CharSequence, long: Boolean) {
        notice(app.info.label, text.toString(), app.info.icon, if (long) 6_000 else 3_500)
    }

    override fun startCarApp(from: CarAppConnection, intent: Intent) {
        val action = intent.action
        // CarContext.startCarApp's documented phone call. Like Android Auto, the host places it for any car app
        // (they use ACTION_DIAL precisely because they don't hold CALL_PHONE). Vanity letters (1-800-FLOWERS) become
        // digits, then only ASCII digits and '+' are kept, so spacing doesn't count toward the length cap. A '*' or
        // '#' makes it a USSD/MMI code ("**21*...#" call forwarding), not a number: refused, as stripping them would
        // dial a different number.
        if ((action == Intent.ACTION_DIAL || action == Intent.ACTION_CALL) && intent.data?.scheme == "tel") {
            val raw = PhoneNumberUtils.convertKeypadLettersToDigits(
                intent.data?.schemeSpecificPart.orEmpty().substringBefore(',').substringBefore(';'),
            )
            // An unescaped '#' starts the URI's fragment, so it isn't in the scheme-specific part: check that too.
            val code = raw.any { it == '*' || it == '#' } || intent.data?.encodedFragment != null
            val number = if (code) "" else raw.filter { it in '0'..'9' || it == '+' }.take(20)
            val id = idOf(from)
            // Car apps stay connected in the background, and AARE hosts any car app (not only Play-approved
            // ones): only the app on screen, or in the assistant bar, calls straight away; any other is asked.
            val screen = ui.screen
            val onScreen = id != null && (
                (screen is CarScreen.App && screen.appId == id) ||
                    (screen is CarScreen.Split && screen.contains(id)) ||
                    ui.assistantAppId == id
                )
            when {
                code -> {
                    notice("Not calling", "${from.info.label} asked to dial a code with * or #, not a phone number")
                    if (onScreen) ui.screen = CarScreen.App(PHONE_ID)
                }
                number.isBlank() -> if (onScreen) ui.screen = CarScreen.App(PHONE_ID)
                onScreen -> {
                    Calls.place(context, number)
                    notice("Calling $number", "Asked by ${from.info.label}")
                    ui.screen = CarScreen.App(PHONE_ID)
                }
                else -> notice("Call $number?", "Asked by ${from.info.label}", durationMs = 10_000, actions = listOf("Call" to {
                    Calls.place(context, number)
                    ui.screen = CarScreen.App(PHONE_ID)
                }))
            }
            return
        }
        val isNavigate = action == CarContext.ACTION_NAVIGATE || intent.data?.scheme == "geo" || intent.data?.scheme == "google.navigation"
        val target = when {
            intent.component != null -> connections.values.firstOrNull { it.info.service == intent.component }
                ?: carApps.entries.firstOrNull { it.value.service == intent.component }?.let { connection(it.key) }
            isNavigate -> navConnection()
            else -> null
        }
        if (target == null) {
            notice(from.info.label, if (isNavigate) "No navigation app on the phone" else "Can't open that from the car")
            return
        }
        val navIntent = if (isNavigate && intent.action != CarContext.ACTION_NAVIGATE) Intent(CarContext.ACTION_NAVIGATE, intent.data) else intent
        target.connect(navIntent)
        show(target)
    }

    override fun finish(app: CarAppConnection) {
        val id = idOf(app) ?: return
        when (val screen = ui.screen) {
            is CarScreen.App -> if (screen.appId == id) ui.screen = CarScreen.Dashboard
            is CarScreen.Split -> if (screen.contains(id)) {
                ui.screen = CarScreen.App(if (screen.leftId == id) screen.rightId else screen.leftId)
            }
            else -> Unit
        }
        if (ui.assistantAppId == id) ui.assistantAppId = null
        // The dashboard's map card would start it again at once. Don't start another one in its
        // place either: two apps that both finish on start would take turns forever.
        if (ui.activeNavAppId == id) ui.activeNavAppId = null
        if (navigatingApp === app) navigationEnded(app)
        visibleCount.remove(app)
        app.release()
        connections.remove(id)
    }

    /** The app whose trip the dashboard and the cluster show. */
    private var navigatingApp: CarAppConnection? = null

    override fun navigationStarted(app: CarAppConnection) {
        if (app.cluster) return
        // One navigation at a time, as in Android Auto: the other app is told to stop.
        connections.values.filter { it !== app && it.navigating }.forEach { it.stopNavigation() }
        navigatingApp = app
        idOf(app)?.let { ui.activeNavAppId = it; settings.lastNavApp = app.info.packageName }
    }

    override fun navigationEnded(app: CarAppConnection) {
        // An app we stopped may end after the new one started: keep the new one's trip.
        if (app.cluster || navigatingApp !== app) return
        navigatingApp = null
        ui.trip = null
        ui.tripPackage = null
        onTrip?.invoke(app, null)
    }

    override fun tripUpdated(app: CarAppConnection, trip: Trip) {
        if (app.cluster) return // the main session reports the same trip
        if (navigatingApp != null && navigatingApp !== app) return
        ui.trip = trip
        ui.tripPackage = app.info.packageName
        onTrip?.invoke(app, trip)
    }

    /** The instrument cluster's session of [nav], if the app has one (FEATURE_CLUSTER). */
    private val clusterConnections = HashMap<String, CarAppConnection>()

    fun clusterConnection(nav: CarAppEntry): CarAppConnection? {
        clusterConnections[nav.id]?.let { return it }
        val info = carApps[nav.id]?.takeIf { CarAppInfo.CATEGORY_CLUSTER in it.categories } ?: return null
        return CarAppConnection(context, info, this, cluster = true).also { clusterConnections[nav.id] = it }
    }

    override fun openMicrophone(app: CarAppConnection, sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit): MicHandle? {
        // The rail's mic and the assistant bar show when an app listens.
        val handle = microphone?.invoke(sink) { main.post { ui.listening = false }; onStopped() } ?: return null
        main.post { ui.listening = true }
        return MicHandle { handle.close(); main.post { ui.listening = false } }
    }

    // ------------------------------------------------------------------ car screen settings

    /** The car's own day/night signal, if it sent one. */
    private var carNight: Boolean? = null
    /** The head unit's driver position. */
    var rightHandDrive = false
        set(v) { field = v; applyScreenSettings() }

    /** The car's driving signal (parked or not), before the passenger-mode setting. */
    private var carDriving = false

    /** The car says it's moving (true) or parked. */
    fun carDriving(driving: Boolean) {
        carDriving = driving
        applyScreenSettings()
    }

    /** The car's day/night signal changed. */
    fun carNightMode(night: Boolean) {
        carNight = night
        applyScreenSettings()
    }

    /** Applies the theme and bar position settings (also when they change during a drive). */
    private fun applyScreenSettings() {
        val night = when (settings.carTheme) {
            Settings.THEME_DARK -> true
            Settings.THEME_LIGHT -> false
            else -> carNight ?: true
        }
        if (ui.night != night) {
            ui.night = night
            configurationChanged()
        }
        val position = settings.railPosition
        // Passenger mode lifts the driving restrictions (keyboard, list lengths, phone screen).
        ui.drivingRestricted = carDriving && !settings.ignoreDriving
        ui.carPlayStyle = settings.carStyle == Settings.STYLE_CARPLAY
        ui.darkBar = settings.darkBar
        val wallpaper = settings.wallpaper
        if (wallpaper != ui.wallpaper || (wallpaper == Settings.WALLPAPER_CUSTOM && ui.wallpaperImage == null)) {
            ui.wallpaper = wallpaper
            ui.wallpaperImage = null
            if (wallpaper == Settings.WALLPAPER_CUSTOM) scope.launch {
                ui.wallpaperImage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(context.filesDir, Settings.WALLPAPER_FILE).path) }.getOrNull()
                }
            }
        }
        ui.railBottom = position == Settings.RAIL_BOTTOM
        ui.driverOnRight = rightHandDrive
        ui.railOnRight = when (position) {
            Settings.RAIL_LEFT -> false
            Settings.RAIL_RIGHT -> true
            else -> rightHandDrive
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "car_theme", "rail_position", "car_style", "dark_bar", "ignore_driving" -> main.post { applyScreenSettings() }
            "wallpaper" -> main.post { ui.wallpaper = ""; applyScreenSettings() }
            // Apps shown or hidden from the phone: update the car's app list now.
            "hidden_apps", "phone_screen", "assistant_pkg", "app_order" -> main.post { refreshApps() }
        }
    }

    // ------------------------------------------------------------------ apps

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    fun start() {
        running += this
        // Messages, directions and now playing all need the listener bound: make sure it is.
        CarNotificationListener.rebind(context)
        applyScreenSettings()
        settings.registerListener(settingsListener)
        refreshApps()
        context.registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        scope.launch { media.apps.collectLatest { refreshApps() } }
        scope.launch { watchCalls() }
        scope.launch { watchDashboardInfo() }
        scope.launch {
            CarNotificationListener.guidance.collect { g ->
                // A navigation app hosted on the car screen shows its own guidance.
                val shown = g?.takeUnless { p -> ui.apps.any { it.kind == CarAppKind.NAVIGATION && it.packageName == p.packageName } }
                ui.phoneGuidance = shown
                onPhoneGuidance?.invoke(shown)
            }
        }
        scope.launch {
            media.playback.collectLatest { p ->
                ui.nowPlaying = p?.let {
                    NowPlaying(mediaId(it.packageName), it.title, listOf(it.artist, it.album).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        it.art, it.playing, it.positionMs, it.durationMs, it.sampledAtMs)
                }
                if (p != null && ui.activeMediaAppId == null) ui.activeMediaAppId = mediaId(p.packageName)
            }
        }
    }

    fun stop() {
        running.remove(this)
        activeProjectedHost?.stop()
        activeProjectedHost = null
        runCatching { context.unregisterReceiver(packageReceiver) }
        settings.unregisterListener(settingsListener)
        connections.values.forEach { it.release() }
        connections.clear()
        clusterConnections.values.forEach { it.release() }
        clusterConnections.clear()
        if (ownsMedia) media.release()
    }

    /** Kill a frozen hosted app and bring its CarAppService back up from scratch. */
    fun restartApp(conn: CarAppConnection) {
        val pkg = conn.info.packageName
        notice("Restarting ${conn.info.label}", "Killing and reopening the app")
        conn.release()
        scope.launch {
            withContext(Dispatchers.IO) {
                val forceStopped = runCatching {
                    val process = ProcessBuilder("su", "-c", "am force-stop $pkg")
                        .redirectErrorStream(true)
                        .start()
                    process.waitFor(4, TimeUnit.SECONDS) && process.exitValue() == 0
                }.getOrDefault(false)
                if (!forceStopped) {
                    context.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(pkg)
                }
            }
            delay(700)
            conn.connect()
        }
    }

    /** Rescans car apps (package manager work and icons on a background thread). */
    fun refreshApps() {
        scope.launch {
            val (found, messaging, projected) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Triple(
                    CarAppCatalog.query(context),
                    AndroidAutoApps.messagingApps(context),
                    ProjectedAppCatalog.query(context),
                )
            }
            applyApps(found, messaging, projected)
        }
    }

    private fun applyApps(found: List<CarAppInfo>, messaging: List<PhoneApp>, projected: List<ProjectedAppInfo> = emptyList()) {
        val hidden = settings.hiddenApps
        val assistantPkg = assistantPackage()
        carApps.clear()
        projectedApps.clear()
        val entries = ArrayList<CarAppEntry>()
        for (app in found) {
            val id = carId(app)
            carApps[id] = app
            if (app.packageName in hidden) continue
            val kind = when {
                app.isNavigation -> CarAppKind.NAVIGATION
                app.packageName == assistantPkg -> CarAppKind.ASSISTANT
                else -> CarAppKind.TEMPLATED
            }
            entries += CarAppEntry(id, app.packageName, app.label, app.icon, kind)
        }
        for (app in projected) {
            val id = projectedId(app.packageName)
            projectedApps[id] = app
            if (app.packageName in hidden) continue
            // Waze and other Car App Library apps are already in [found]; don't list them twice.
            if (entries.any { it.packageName == app.packageName }) continue
            entries += CarAppEntry(id, app.packageName, app.label, app.icon, CarAppKind.PROJECTED)
        }
        for (m in media.apps.value) {
            // An app that has a car UI of its own is shown once, as that.
            if (m.packageName in hidden || entries.any { it.packageName == m.packageName }) continue
            entries += CarAppEntry(mediaId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MEDIA)
        }
        // Built in, like Android Auto's: calls and the car-side settings.
        entries += CarAppEntry(PHONE_ID, context.packageName, "Phone", builtinIcon(R.drawable.ic_car_phone), CarAppKind.PHONE)
        entries += CarAppEntry(SETTINGS_ID, context.packageName, "Settings", builtinIcon(R.drawable.ic_car_settings), CarAppKind.SETTINGS)
        entries += CarAppEntry(NOTIFICATIONS_ID, context.packageName, "Notifications", builtinIcon(R.drawable.ic_car_notifications), CarAppKind.NOTIFICATIONS)
        if (settings.phoneScreen && phoneScreenAllowed) {
            entries += CarAppEntry(PHONE_SCREEN_ID, context.packageName, "Phone screen", builtinIcon(R.drawable.ic_phone_screen), CarAppKind.PHONE_SCREEN)
        }
        for (m in messaging) {
            if (m.packageName in hidden || entries.any { it.packageName == m.packageName }) continue
            entries += CarAppEntry(messagingId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MESSAGING)
        }
        // The order chosen on the phone first, then alphabetical.
        val order = settings.appOrder.withIndex().associate { (i, k) -> k to i }
        entries.sortWith(compareBy<CarAppEntry>({ order[orderKey(it)] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
        ui.apps.clear()
        ui.apps.addAll(entries)
        // A debug/head-unit launch can ask for an app before the car session has loaded the catalog.
        pendingLaunchPackage?.let { pkg ->
            entries.firstOrNull { it.packageName == pkg }?.let { app ->
                pendingLaunchPackage = null
                actions.openApp(app)
            }
        }
        pendingSplitPackage?.let { pkg ->
            entries.firstOrNull { it.packageName == pkg }?.let { app ->
                pendingSplitPackage = null
                // The launch package is opened above. Let its connection settle, then split.
                main.postDelayed({
                    if (ui.screen is CarScreen.App) actions.openInSplit(app)
                }, 1500)
            }
        }
        // Restore the last navigation and media apps, so the dashboard isn't empty.
        if (ui.activeNavAppId == null) {
            val nav = entries.filter { it.kind == CarAppKind.NAVIGATION }
            ui.activeNavAppId = (nav.firstOrNull { it.packageName == settings.lastNavApp } ?: nav.singleOrNull())?.id
        }
        if (ui.activeMediaAppId == null) {
            ui.activeMediaAppId = entries.firstOrNull { it.kind == CarAppKind.MEDIA && it.packageName == settings.lastMediaApp }?.id
        }
    }

    /** The weather (every 30 minutes) and the next calendar event (every 5), for the rail and the dashboard. */
    private suspend fun watchDashboardInfo() {
        var lastWeather = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (!settings.showWeather) ui.weather = null
            else if (now - lastWeather > 30 * 60_000) {
                val where = carLocation ?: DashboardInfo.phoneLocation(context)
                if (where != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.weather(where) }?.let { ui.weather = it; lastWeather = now }
                }
            }
            ui.nextEvent = if (settings.showCalendar)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.nextEvent(context) } else null
            kotlinx.coroutines.delay(5 * 60_000)
        }
    }

    /** Navigates to [place] in the car's navigation app (geo: query), or false without one. */
    fun navigateTo(place: String): Boolean {
        val nav = ui.app(ui.activeNavAppId) ?: ui.apps.firstOrNull { it.kind == CarAppKind.NAVIGATION } ?: return false
        val conn = connection(nav.id) ?: return false
        conn.connect(Intent(CarContext.ACTION_NAVIGATE).setData(android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place))))
        ui.activeNavAppId = nav.id
        ui.screen = CarScreen.App(nav.id)
        return true
    }

    /** Phone calls for the car's call card: polled, as no telephony listener needs fewer permissions. */
    private suspend fun watchCalls() {
        var since = 0L
        var last = TelephonyManager.CALL_STATE_IDLE
        while (true) {
            val state = Calls.state(context)
            if (state != last) { since = System.currentTimeMillis(); last = state }
            val name = CarNotificationListener.caller.value
            val call = when (state) {
                TelephonyManager.CALL_STATE_RINGING -> CarCall(ringing = true, name = name ?: "Incoming call", sinceMs = since)
                TelephonyManager.CALL_STATE_OFFHOOK -> CarCall(ringing = false, name = name ?: "Call", sinceMs = since)
                else -> null
            }
            if (call != ui.call) ui.call = call
            kotlinx.coroutines.delay(if (state == TelephonyManager.CALL_STATE_IDLE) 1_500 else 500)
        }
    }

    /** Starts the last media app playing again (the "resume music" setting). */
    fun resumeMedia() {
        scope.launch {
            // The app list and the media apps come in asynchronously: give them a moment.
            kotlinx.coroutines.delay(1_500)
            val app = media.apps.value.firstOrNull { it.packageName == settings.lastMediaApp } ?: return@launch
            media.select(app)
            kotlinx.coroutines.delay(1_500)
            if (media.playback.value?.playing != true) media.playPause()
        }
    }

    /** How [Settings.appOrder] names an entry: its package, or its id for AARE's own apps. */
    private fun orderKey(e: CarAppEntry) = if (e.packageName == context.packageName) e.id else e.packageName

    private fun builtinIcon(res: Int) = ContextCompat.getDrawable(context, res)?.toBitmap(128, 128)

    /** Packages of the navigation apps hosted on the car screen. */
    fun navigationPackages(): Set<String> = carApps.values.filter { it.isNavigation }.mapTo(HashSet()) { it.packageName }

    /** The private projected-activity app for a launcher entry, if it is one. */
    fun projectedApp(id: String): ProjectedAppInfo? = projectedApps[id]

    /** True when a projected app is on screen and should receive this hardware key. */
    /** Re-focus the projected surface when input arrives in a split. */
    fun focusProjected() {
        activeProjectedHost?.requestFocus()
    }

    fun projectedKey(code: Int, down: Boolean): Boolean {
        val id = when (val screen = ui.screen) {
            is CarScreen.App -> screen.appId
            is CarScreen.Split -> when {
                ui.app(screen.leftId)?.kind == CarAppKind.PROJECTED -> screen.leftId
                ui.app(screen.rightId)?.kind == CarAppKind.PROJECTED -> screen.rightId
                else -> null
            }
            else -> null
        } ?: return false
        if (ui.app(id)?.kind != CarAppKind.PROJECTED) return false
        val host = activeProjectedHost ?: return false
        host.sendKey(code, down)
        return true
    }

    /** The connection for a car app entry, created on first use. */
    fun connection(id: String): CarAppConnection? {
        connections[id]?.let { return it }
        val info = carApps[id] ?: return null
        return CarAppConnection(context, info, this).also { connections[id] = it }
    }

    private fun navConnection(): CarAppConnection? {
        val id = ui.activeNavAppId ?: ui.apps.firstOrNull { it.kind == CarAppKind.NAVIGATION }?.id ?: return null
        ui.activeNavAppId = id
        return connection(id)
    }

    private fun idOf(conn: CarAppConnection): String? = connections.entries.firstOrNull { it.value === conn }?.key

    private fun show(conn: CarAppConnection) {
        idOf(conn)?.let { ui.screen = CarScreen.App(it) }
    }

    fun acquireVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 0) + 1
        visibleCount[conn] = n
        if (n == 1) conn.setVisible(true)
    }

    fun releaseVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 1) - 1
        if (n <= 0) {
            visibleCount.remove(conn)
            // Let the other copy (dashboard card ↔ full screen) take over before stopping the app.
            main.postDelayed({ if ((visibleCount[conn] ?: 0) == 0) conn.setVisible(false) }, 500)
        } else visibleCount[conn] = n
    }

    /** Pushes car GPS/compass to subscribed apps. Any thread. */
    fun carSensorsChanged() = main.post { connections.values.forEach { it.carSensorsChanged() } }

    /** Night mode or screen size changed: tell running apps. */
    fun configurationChanged() {
        connections.values.forEach { it.configurationChanged() }
        clusterConnections.values.forEach { it.configurationChanged() }
    }

    // ------------------------------------------------------------------ notices

    /** Notice keys (LazyColumn keys): two notices in the same millisecond must not collide. */
    private var noticeSeq = 0L

    /** Keeps a notification (a message) for the notification centre. */
    fun remember(title: String, text: String, icon: Bitmap?, actions: List<Pair<String, () -> Unit>>) {
        ui.history.add(0, CarNotice("h${++noticeSeq}", title, text, icon, actions))
        while (ui.history.size > 30) ui.history.removeAt(ui.history.lastIndex)
        if ((ui.screen as? CarScreen.App)?.appId != NOTIFICATIONS_ID) ui.unseen++
    }

    fun notice(title: String, text: String, icon: Bitmap? = null, durationMs: Long = 4_000, actions: List<Pair<String, () -> Unit>> = emptyList(), message: Boolean = false) {
        val n = CarNotice("n${++noticeSeq}", title, text, icon, actions, message = message)
        ui.notices.add(n)
        main.postDelayed({ ui.notices.remove(n) }, durationMs)
    }

    // ------------------------------------------------------------------ actions from the car UI

    /** Makes an app the active navigation/media app and starts its car service. */
    private fun activate(app: CarAppEntry) {
        when (app.kind) {
            CarAppKind.MEDIA -> {
                ui.activeMediaAppId = app.id
                settings.lastMediaApp = app.packageName
                media.apps.value.firstOrNull { it.packageName == app.packageName }?.let { media.select(it) }
            }
            CarAppKind.NAVIGATION -> {
                ui.activeNavAppId = app.id
                settings.lastNavApp = app.packageName
                connection(app.id)?.connect()
            }
            CarAppKind.PROJECTED -> Unit // ProjectedAppScreen binds when its SurfaceView is ready.
            else -> connection(app.id)?.connect()
        }
    }

    private fun rememberRecent(appId: String) {
        ui.recentAppIds.remove(appId)
        ui.recentAppIds.add(0, appId)
        while (ui.recentAppIds.size > 6) ui.recentAppIds.removeAt(ui.recentAppIds.lastIndex)
    }

    /** Waze is the preferred navigation pane in split; Maps remains the full-screen fallback. */
    private fun preferredSplitNav(excludeId: String? = null): CarAppEntry? =
        ui.apps.firstOrNull { it.packageName == CarAppInfo.AARE_NAV_PACKAGE && it.id != excludeId }
            ?: ui.apps.firstOrNull { it.packageName == "com.waze" && it.id != excludeId }
            ?: ui.apps.firstOrNull { it.kind == CarAppKind.PROJECTED && it.id != excludeId }

    private fun openSplitPair(other: CarAppEntry, app: CarAppEntry) {
        if (other.id == app.id) return
        if (other.kind == CarAppKind.PROJECTED && app.kind == CarAppKind.PROJECTED) {
            notice("Split screen", "Only one projected app can be shown at a time")
            activate(app)
            ui.splitExpanded = false
            ui.screen = CarScreen.App(app.id)
            rememberRecent(app.id)
            return
        }
        activate(other)
        activate(app)
        ui.splitExpanded = false
        ui.screen = CarScreen.Split(other.id, app.id)
        ui.recentAppIds.remove(other.id)
        ui.recentAppIds.remove(app.id)
        ui.recentAppIds.add(0, app.id)
        ui.recentAppIds.add(1, other.id)
        while (ui.recentAppIds.size > 6) ui.recentAppIds.removeAt(ui.recentAppIds.lastIndex)
    }

    val actions = object : CarActions {
        override fun openApp(app: CarAppEntry) {
            activate(app)
            ui.splitExpanded = false
            ui.screen = CarScreen.App(app.id)
            rememberRecent(app.id)
        }

        override fun openInSplit(app: CarAppEntry) {
            // The other pane is whatever is on screen now, or the last app if we are in the
            // launcher. This makes "open an app, go to the grid, long-press another" work.
            val other = when (val screen = ui.screen) {
                is CarScreen.App -> ui.app(screen.appId)
                is CarScreen.Split -> ui.app(screen.leftId)
                CarScreen.Dashboard, CarScreen.Launcher ->
                    ui.recentAppIds.firstNotNullOfOrNull { ui.app(it)?.takeIf { entry -> entry.id != app.id } }
            }
            if (other == null || other.id == app.id) {
                notice("Split screen", "Open another app first, then long-press an app to put it beside")
                openApp(app)
                return
            }
            if (other.kind == CarAppKind.PROJECTED && app.kind == CarAppKind.PROJECTED) {
                notice("Split screen", "Only one projected app can be shown at a time")
                openApp(app)
                return
            }
            // Prefer Waze over Maps whenever the other pane is a projected map.
            val preferredOther = if (other.kind == CarAppKind.PROJECTED) {
                preferredSplitNav(excludeId = app.id) ?: other
            } else other
            openSplitPair(preferredOther, app)
        }

        override fun taskbarClick(app: CarAppEntry) {
            val split = ui.screen as? CarScreen.Split
            if (split != null && split.contains(app.id)) {
                // Tapping either app in a split swaps the two panes.
                ui.splitExpanded = false
                ui.screen = CarScreen.Split(split.rightId, split.leftId)
            } else {
                openApp(app)
            }
        }

        override fun taskbarLongPress(app: CarAppEntry) {
            val split = ui.screen as? CarScreen.Split
            if (split != null && split.contains(app.id)) {
                // The second long-press leaves split screen and makes the pressed app full size.
                activate(app)
                ui.splitExpanded = false
                ui.screen = CarScreen.App(app.id)
                rememberRecent(app.id)
            } else {
                // Waze and AARE Caster are the preferred split pair. Long-pressing either starts
                // that pair regardless of what is currently open; Maps stays full-screen.
                val navApp = ui.apps.firstOrNull { it.packageName == CarAppInfo.AARE_NAV_PACKAGE }
                    ?: ui.apps.firstOrNull { it.packageName == "com.waze" }
                val caster = ui.apps.firstOrNull { it.packageName == CarAppInfo.AARE_CASTER_PACKAGE }
                when {
                    app.packageName == CarAppInfo.AARE_CASTER_PACKAGE && navApp != null ->
                        openSplitPair(navApp, app)
                    app.packageName == CarAppInfo.AARE_NAV_PACKAGE && caster != null ->
                        openSplitPair(app, caster)
                    app.packageName == "com.waze" && caster != null ->
                        openSplitPair(app, caster)
                    else -> openInSplit(app)
                }
            }
        }

        override fun toggleSplitExpanded() {
            if (ui.screen is CarScreen.Split) ui.splitExpanded = !ui.splitExpanded
        }

        override fun goHome() { ui.screen = CarScreen.Dashboard }
        override fun openLauncher() { ui.screen = CarScreen.Launcher }
        override fun assistant() { this@CarRuntime.assistant() }
        override fun mediaPlayPause() = media.playPause()
        override fun mediaNext() = media.next()
        override fun mediaPrevious() = media.previous()
        override fun dismissNotice(notice: CarNotice) { ui.notices.remove(notice) }
        override fun answerCall() = Calls.answer(context)
        override fun endCall() = Calls.end(context)
        override fun navigateTo(place: String) {
            if (!this@CarRuntime.navigateTo(place)) notice("Navigation", "No navigation app in the car")
        }
    }

    /** The chosen assistant's package, or the phone's default assistant. */
    private fun assistantPackage(): String? = settings.assistantPackage ?: Assistants.phoneDefault(context)

    /**
     * Steering-wheel voice button or the rail's mic. An assistant with a car app (Lapin, for example)
     * opens on the car screen and is asked to listen with ACTION_VOICE_COMMAND; any other assistant
     * opens on the phone.
     */
    fun assistant() {
        onAssistant?.invoke()
        val pkg = assistantPackage()
        if (pkg == null) {
            notice("Assistant", "Choose a voice assistant in AARE's settings on the phone")
            return
        }
        val entry = ui.apps.firstOrNull { it.packageName == pkg && it.kind != CarAppKind.MEDIA && it.kind != CarAppKind.NAVIGATION }
        val conn = entry?.let { connection(it.id) }
        if (entry != null && conn != null) {
            // Like Android Auto's assistant: a bar over whatever is on screen, not the app full screen.
            conn.connect(Intent(Intent.ACTION_VOICE_COMMAND).setComponent(conn.info.service))
            if ((ui.screen as? CarScreen.App)?.appId != entry.id) ui.assistantAppId = entry.id
            return
        }
        val label = Assistants.label(context, pkg) ?: pkg
        if (Assistants.startOnPhone(context, pkg)) {
            notice("Assistant", "$label has no car screen: it opened on the phone")
        } else {
            notice("Assistant", "$label can't be opened. Choose another assistant in AARE's settings")
        }
    }

    /** Set by a screen with its own back stack (a conversation in a messaging app): true if it handled back. */
    var screenBack: (() -> Boolean)? = null

    /** Back key from the car. */
    fun back() {
        if (ui.assistantAppId != null) { ui.assistantAppId = null; return }
        if (screenBack?.invoke() == true) return
        when (val s = ui.screen) {
            is CarScreen.App -> {
                val conn = connections[s.appId]
                if (conn != null) conn.back() else ui.screen = CarScreen.Dashboard
            }
            is CarScreen.Split -> ui.screen = CarScreen.App(s.leftId)
            CarScreen.Launcher -> ui.screen = CarScreen.Dashboard
            CarScreen.Dashboard -> Unit
        }
    }

    // ------------------------------------------------------------------ UI

    /** The whole car screen. */
    @Composable
    fun Root(modifier: Modifier = Modifier) {
        val mediaApps by media.apps.collectAsState()
        CarTheme(night = ui.night) {
            CompositionLocalProvider(LocalCarDimens provides LocalCarDimens.current) {
                CarShell(
                    state = ui,
                    actions = actions,
                    appContent = { app, mod ->
                        when (app.kind) {
                            CarAppKind.PROJECTED -> ProjectedAppScreen(this, app, mod)
                            CarAppKind.MEDIA -> {
                                val m = mediaApps.firstOrNull { it.packageName == app.packageName }
                                if (m != null) media.Screen(m, ui.drivingRestricted, mod)
                            }
                            CarAppKind.MESSAGING -> MessagingScreen(app, ui.drivingRestricted, this, mod)
                            CarAppKind.PHONE_SCREEN -> MirrorScreen(this, ui.drivingRestricted, mod)
                            CarAppKind.PHONE -> PhoneScreen(this, mod)
                            CarAppKind.SETTINGS -> CarSettingsScreen(settings, mod)
                            CarAppKind.NOTIFICATIONS -> NotificationsScreen(ui, mod)
                            else -> connection(app.id)?.let { CarAppScreen(it, this, mod) }
                        }
                    },
                    navMap = { app, mod -> connection(app.id)?.let { CarAppScreen(it, this, mod, compact = true) } },
                    modifier = modifier,
                    overlay = { mod ->
                        val entry = ui.app(ui.assistantAppId)
                        val conn = entry?.let { connection(it.id) }
                        if (entry != null && conn != null) {
                            AssistantBar(entry, conn, this, mod,
                                onClose = { ui.assistantAppId = null },
                                onExpand = { ui.assistantAppId = null; ui.screen = CarScreen.App(entry.id) })
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val TAG = "AareRuntime"

        /** Set by MainActivity/head-unit debug extras; consumed by the first runtime that sees the package. */
        @Volatile
        var pendingLaunchPackage: String? = null

        /** Debug-only: open this package beside [pendingLaunchPackage] after the catalog loads. */
        @Volatile
        var pendingSplitPackage: String? = null

        fun carId(app: CarAppInfo) = "car:${app.service.flattenToShortString()}"
        fun mediaId(pkg: String) = "media:$pkg"
        fun messagingId(pkg: String) = "messages:$pkg"
        fun projectedId(pkg: String) = "projected:$pkg"
        const val PHONE_SCREEN_ID = "phone-screen"
        const val PHONE_ID = "phone"
        const val SETTINGS_ID = "car-settings"
        const val NOTIFICATIONS_ID = "notifications"

        /** Started runtimes, oldest first. Main thread. */
        private val running = ArrayList<CarRuntime>()

        /**
         * The car screen being driven now, for the voice assistant's car tools: the car session's
         * runtime, or the preview's when no car is connected (opening or closing the preview
         * during a drive doesn't take the car away). Main thread.
         */
        val active: CarRuntime? get() = running.lastOrNull { it.primary } ?: running.lastOrNull()
    }
}
