package net.prototux.aare.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.window.InputTransferToken

/**
 * Host for one legacy GMS projected-activity service (the protocol behind Google Maps' car UI).
 *
 * This is the production port of tools/gearhead-projection-probe: bind to the app's exported
 * CarActivityService, call onSetup (ICarProjection tx 1), then onProjectionStart (tx 26) with a
 * DrawingSpec whose surface is the SurfaceView on the car screen, and finally onProjectionResume
 * (tx 4). The app creates a Presentation on that surface and draws its real UI into it.
 *
 * The service checks CAPTURE_VIDEO_OUTPUT inside the app; a normal install is refused. See
 * docs/research/gearhead-projected-activity.md and tools/aare-hooks/README.md for the
 * root + Vector/LSPosed module (or the Frida development harness).
 */
class ProjectedHost(
    context: Context,
    private val component: ComponentName,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onError(message: String)

        /** Maps' SurfacePackage is embedded: input now goes natively to the child surface. */
        fun onChildAttached() = Unit
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: IBinder? = null
    private var connection: ServiceConnection? = null
    private var surface: Surface? = null
    private var surfaceView: SurfaceView? = null
    @Volatile private var inputTransferToken: InputTransferToken? = null
    private var childPackage: SurfaceControlViewHost.SurfacePackage? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0
    private var displayId = 0
    private var touchDownTime = 0L
    @Volatile private var generation = 0

    /** Keep the embedded Maps surface focused when another split pane is present. */
    fun requestFocus() {
        surfaceView?.let { view ->
            if (!view.hasFocus()) view.requestFocus()
        }
    }

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            logInbound("callback", code, data, flags)
            when (code) {
                23 -> {
                    val state = runCatching { data.readInt() }.getOrNull()
                    Log.i(TAG, "callback: connection state $state")
                }
                35 -> {
                    // Maps sends a SurfaceControlViewHost.SurfacePackage inside a Bundle.
                    // The parcel begins with a 12-byte Binder header, then the callback
                    // interface token, then the Parcelable presence int and Bundle.
                    data.setDataPosition(12)
                    runCatching { data.readString() }
                    val present = runCatching { data.readInt() }.getOrDefault(0)
                    val extras = if (present != 0) {
                        runCatching { data.readBundle(javaClass.classLoader) }.getOrNull()
                    } else null
                    val pkg = extras?.getParcelable(
                        "surface_package",
                        SurfaceControlViewHost.SurfacePackage::class.java,
                    )
                    Log.i(TAG, "callback: surface package ${if (pkg != null) "yes" else "missing"}")
                    if (pkg != null) {
                        main.post {
                            if (surfaceView != null) {
                                childPackage = pkg
                                surfaceView!!.setChildSurfacePackage(pkg)
                                status("Projecting")
                                listener.onChildAttached()
                            } else {
                                pkg.release()
                            }
                        }
                    }
                }
                else -> Log.i(TAG, "callback: transaction $code")
            }
            reply?.writeNoException()
            return true
        }
    }

    /**
     * Maps asks the ICar binder for an ICarDisplayManager (tx 77) and then for an ICarDisplay
     * (manager tx 3). The display's tx 3 returns the CarUiInfo the legacy UI expects.
     */
    private val display = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            logInbound("ICarDisplay", code, data, flags)
            when (code) {
                3 -> {
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    writeCarUiInfo(reply!!)
                }
                4, 7, 10, 13 -> {
                    runCatching { data.readStrongBinder() }
                    reply?.writeNoException()
                }
                else -> {
                    Log.i(TAG, "ICarDisplay: unhandled transaction $code")
                    reply?.writeNoException()
                }
            }
            return true
        }
    }

    private val displayManager = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            logInbound("ICarDisplayManager", code, data, flags)
            if (code == 3) {
                reply?.writeNoException()
                reply?.writeStrongBinder(display)
            } else {
                Log.i(TAG, "ICarDisplayManager: unhandled transaction $code")
                reply?.writeNoException()
            }
            return true
        }
    }

    /** The app's ICar callback: just enough of the legacy car description to let it draw. */
    private val car = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            logInbound("ICar", code, data, flags)
            when (code) {
                1 -> {
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    writeCarInfo(reply!!)
                }
                2 -> {
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    writeCarUiInfo(reply!!)
                }
                3 -> {
                    reply?.writeNoException()
                    reply?.writeInt(1)
                }
                5, 6 -> {
                    runCatching { data.readStrongBinder() }
                    reply?.writeNoException()
                }
                7, 17 -> {
                    reply?.writeNoException()
                    reply?.writeStrongBinder(null)
                }
                77 -> {
                    reply?.writeNoException()
                    reply?.writeStrongBinder(displayManager)
                }
                19 -> {
                    val name = readSettingName(data)
                    Log.i(TAG, "ICar setting $name queried")
                    reply?.writeNoException()
                    reply?.writeInt(0)
                }
                71 -> {
                    val name = readSettingName(data)
                    val value = when (name) {
                        "client_fullscreen_presentation", "presentation_clear_parent_padding" -> 1
                        else -> 0
                    }
                    Log.i(TAG, "ICar setting $name -> $value")
                    reply?.writeNoException()
                    reply?.writeInt(value)
                }
                55 -> {
                    runCatching { data.readString(); data.createStringArrayList() }
                    reply?.writeNoException()
                    // If we have a SurfaceView input token, advertise the modern
                    // SurfaceControlViewHost path (feature 54): Maps then hosts its UI in our
                    // SurfaceView and Android routes touch/pinch natively. Without a token,
                    // stay on the legacy Presentation path and request local focus.
                    reply?.writeStringList(
                        if (inputTransferToken != null) {
                            listOf("SURFACE_CONTROL_VIEW_HOST_RENDERING", "LOCAL_FOCUS_MODE")
                        } else {
                            listOf("LOCAL_FOCUS_MODE")
                        }
                    )
                }
                68 -> {
                    runCatching { data.readString(); data.readInt() }
                    reply?.writeNoException()
                    reply?.writeInt(0)
                }
                else -> {
                    Log.i(TAG, "ICar: unhandled transaction $code")
                    reply?.writeNoException()
                }
            }
            return true
        }
    }

    /**
     * Starts (or restarts) the projection on [surface]. Safe to call again on a SurfaceView
     * resize; the old binding is torn down first.
     */
    fun start(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        startInternal(surface, width, height, densityDpi, 0, null, null)
    }

    /**
     * Modern projected path: Maps creates a SurfaceControlViewHost inside our SurfaceView and
     * returns its SurfacePackage. The SurfaceView's InputTransferToken is the routing handle
     * that makes Android deliver car touches and pinch gestures to that child surface natively.
     */
    fun start(view: SurfaceView, width: Int, height: Int, densityDpi: Int) {
        val token = runCatching { view.rootSurfaceControl?.inputTransferToken }.getOrNull()
        val viewDisplayId = runCatching { view.display?.displayId }.getOrNull() ?: 0
        Log.i(TAG, "start view ${width}x${height} @${densityDpi}dpi display=$viewDisplayId token=${token != null}")
        startInternal(view.holder.surface, width, height, densityDpi, viewDisplayId, token, view)
    }

    private fun startInternal(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        displayId: Int,
        token: InputTransferToken?,
        view: SurfaceView?,
    ) {
        if (this.surface === surface && this.width == width && this.height == height &&
            this.densityDpi == densityDpi && this.displayId == displayId &&
            this.inputTransferToken == token && connection != null
        ) return
        stop()
        if (width <= 0 || height <= 0) return
        Log.i(TAG, "start surface ${width}x${height} @${densityDpi}dpi nativeInput=${token != null}")
        this.surface = surface
        this.surfaceView = view
        this.inputTransferToken = token
        this.width = width
        this.height = height
        this.densityDpi = densityDpi
        this.displayId = displayId
        touchDownTime = 0L
        generation++
        bind(generation)
    }

    val usesNativeInput: Boolean
        get() = inputTransferToken != null

    private fun bind(bindGeneration: Int) {
        status("Starting ${component.packageName}…")
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (bindGeneration != generation) return
                service = binder
                Log.i(TAG, "bound ${name.flattenToShortString()}")
                Thread({ setup(binder, bindGeneration) }, "projected-setup").start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                Log.i(TAG, "service disconnected")
            }

            override fun onBindingDied(name: ComponentName) {
                service = null
                fail("The projected app stopped")
            }

            override fun onNullBinding(name: ComponentName) {
                fail("The projected app refused the connection")
            }
        }
        connection = conn
        val intent = Intent().setComponent(component)
        val ok = runCatching {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.e(TAG, "bindService failed", it)
            false
        }
        if (!ok) {
            connection = null
            fail("Could not start ${component.packageName}")
        }
    }

    private fun setup(binder: IBinder, setupGeneration: Int) {
        val boundSurface = surface ?: return
        if (setupGeneration != generation) return
        if (!boundSurface.isValid) {
            fail("The car screen surface went away")
            return
        }
        if (!call(binder, "onSetup", 1) { data ->
                data.writeStrongBinder(callback)
                data.writeStrongBinder(car)
            }) return

        Thread.sleep(300)
        if (setupGeneration != generation || surface !== boundSurface || service == null) return

        Log.i(TAG, "onProjectionStart surface ${width}x${height} @${densityDpi}dpi display=$displayId")
        if (!call(binder, "onProjectionStart", 26) { data ->
                data.writeInt(displayId) // CarDisplayId: display hosting the SurfaceView
                data.writeInt(0) // CarRegionId
                data.writeInt(1) // DrawingSpec presence
                // DrawingSpec.e is a system-window insets Rect, not the content bounds.
                // Passing (0,0,width,height) made Maps think the entire content was inset.
                writeDrawingSpec(data, width, height, densityDpi, boundSurface, Rect(0, 0, 0, 0), displayId, inputTransferToken)
                val appIntent = Intent(Intent.ACTION_MAIN).setPackage(component.packageName)
                val projectionConfig = Bundle().apply {
                    putBoolean("CANCEL_PENDING_LIFECYCLE_EVENTS_FOR_FINISH_ENABLED", false)
                }
                appIntent.putExtra("PROJECTION_CONFIGURATION", projectionConfig)
                data.writeInt(1)
                appIntent.writeToParcel(data, 0)
                val configuration = Configuration(context.resources.configuration)
                data.writeInt(1)
                configuration.writeToParcel(data, 0)
                data.writeInt(1)
                Bundle().apply { putBoolean("AARE_HOST", true) }.writeToParcel(data, 0)
            }) return

        Thread.sleep(300)
        if (setupGeneration != generation || surface !== boundSurface || service == null) return
        call(binder, "onProjectionResume", 4) { data -> data.writeInt(displayId) }
        // The app's CarActivityHost ignores touch while it believes the display has no
        // input focus. Real gearhead sends this after the resume/window handshake.
        sendInputFocus(focused = true, touchMode = true)
        status("Projecting")
    }

    /** InputFocusChangedEvent(focused, inTouchMode, direction, focusedRect), tx 20. */
    private fun sendInputFocus(focused: Boolean, touchMode: Boolean) {
        oneWay(20) { data ->
            data.writeInt(1) // event presence
            val start = data.dataPosition()
            data.writeInt(20293 or 0xFFFF0000.toInt())
            data.writeInt(0)
            data.writeInt(1 or 0x00040000)
            data.writeInt(if (focused) 1 else 0)
            data.writeInt(2 or 0x00040000)
            data.writeInt(if (touchMode) 1 else 0)
            data.writeInt(3 or 0x00040000)
            data.writeInt(-1)
            data.writeInt(4 or 0xFFFF0000.toInt())
            data.writeInt(0) // focusedRect = null
            val end = data.dataPosition()
            data.setDataPosition(start + 4)
            data.writeInt(end - (start + 8))
            data.setDataPosition(end)
        }
    }

    private fun call(binder: IBinder, label: String, code: Int, write: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            Log.i(TAG, "$label (tx $code)")
            binder.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (t: Throwable) {
            val message = if (t is SecurityException) {
                "Maps refused the projection: ${t.message}. " +
                    "Install the AARE Projection Hooks module (root + Vector/LSPosed), or use the Frida development harness."
            } else {
                "$label failed: ${t.message ?: t.javaClass.simpleName}"
            }
            fail(message)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun pause() = oneWay(5) { it.writeInt(0) }

    fun resumeProjection() = oneWay(4) { it.writeInt(0) }

    /**
     * Sends a complete [MotionEvent] (all pointers, real event times) to the projected app.
     * Maps needs the pointer count to recognize pinch/zoom, and the original timestamps to
     * distinguish a tap from a drag.
     */
    fun sendTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            Log.d(TAG, "touch pointers=${event.pointerCount} action=${event.actionMasked} source=${event.source} x=${event.x} y=${event.y}")
        }
        val copy = MotionEvent.obtain(event)
        copy.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            oneWay(7) { data ->
                data.writeInt(displayId) // CarDisplayId
                data.writeInt(1) // MotionEvent presence
                copy.writeToParcel(data, 0)
            }
        } finally {
            copy.recycle()
        }
    }

    /** Sends a synthetic single-pointer touch (probe/tests only). */
    fun sendTouch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            Log.d(TAG, "touch action=$action x=$x y=$y")
        }
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val downTime = if (touchDownTime == 0L) now else touchDownTime
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            oneWay(7) { data ->
                data.writeInt(displayId) // CarDisplayId
                data.writeInt(1) // MotionEvent presence
                event.writeToParcel(data, 0)
            }
        } finally {
            event.recycle()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchDownTime = 0L
        }
    }

    fun sendKey(code: Int, down: Boolean) {
        val event = KeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
        try {
            oneWay(8) { data ->
                data.writeInt(displayId) // CarDisplayId
                data.writeInt(1) // KeyEvent presence
                event.writeToParcel(data, 0)
            }
        } finally {
            // KeyEvent has no recycle().
        }
    }

    private fun oneWay(code: Int, write: (Parcel) -> Unit) {
        val binder = service ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            // FLAG_ONEWAY: these are fire-and-forget. A synchronous call from the main thread
            // (touch overlay) blocked AARE until Maps answered, and Maps can wait on AARE.
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Log.w(TAG, "transaction $code failed", t)
        } finally {
            data.recycle()
        }
    }

    fun stop() {
        generation++
        service?.let { binder ->
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(0)
                binder.transact(9, data, null, 0) // onProjectionStop
            } catch (_: Throwable) {
            } finally {
                data.recycle()
            }
        }
        connection?.let { conn ->
            runCatching { context.unbindService(conn) }
        }
        connection = null
        service = null
        surface = null
        surfaceView = null
        inputTransferToken = null
        childPackage?.release()
        childPackage = null
        touchDownTime = 0L
    }

    private fun status(text: String) {
        Log.i(TAG, text)
        main.post { listener.onStatus(text) }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        main.post { listener.onError(message) }
    }

    private fun writeDrawingSpec(
        parcel: Parcel,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        insets: Rect,
        displayId: Int,
        token: InputTransferToken?,
    ) {
        val start = parcel.dataPosition()
        parcel.writeInt(20293 or 0xFFFF0000.toInt())
        parcel.writeInt(0)
        parcel.writeInt(1 or 0x00040000)
        parcel.writeInt(width)
        parcel.writeInt(2 or 0x00040000)
        parcel.writeInt(height)
        parcel.writeInt(3 or 0x00040000)
        parcel.writeInt(dpi)
        writeParcelableField(parcel, 4, surface)
        writeParcelableField(parcel, 5, insets)
        parcel.writeInt(6 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(7 or 0x00040000)
        parcel.writeInt(displayId)
        if (token != null) {
            val extras = Bundle().apply { putParcelable("input_transfer_token", token) }
            val fieldStart = parcel.dataPosition()
            parcel.writeInt(8 or 0xFFFF0000.toInt())
            parcel.writeInt(0)
            extras.writeToParcel(parcel, 0)
            val fieldEnd = parcel.dataPosition()
            parcel.setDataPosition(fieldStart + 4)
            parcel.writeInt(fieldEnd - (fieldStart + 8))
            parcel.setDataPosition(fieldEnd)
        }
        val end = parcel.dataPosition()
        parcel.setDataPosition(start + 4)
        parcel.writeInt(end - (start + 8))
        parcel.setDataPosition(end)
    }

    private fun writeParcelableField(parcel: Parcel, id: Int, value: Parcelable) {
        val start = parcel.dataPosition()
        parcel.writeInt(id or 0xFFFF0000.toInt())
        parcel.writeInt(0)
        value.writeToParcel(parcel, 0)
        val end = parcel.dataPosition()
        parcel.setDataPosition(start + 4)
        parcel.writeInt(end - (start + 8))
        parcel.setDataPosition(end)
    }

    private fun writeCarInfo(parcel: Parcel) {
        val start = parcel.dataPosition()
        parcel.writeInt(20293 or 0xFFFF0000.toInt())
        parcel.writeInt(0)
        // Match the stock Desktop Head Unit's CarInfo exactly: Maps uses the head-unit
        // protocol version (1.7 here) when deciding which input/UI features are available.
        writeStringField(parcel, 1, "Google")
        writeStringField(parcel, 2, "Desktop Head Unit")
        writeStringField(parcel, 3, "2015")
        writeStringField(parcel, 4, "5abcfda96937f794")
        parcel.writeInt(5 or 0x00040000)
        parcel.writeInt(1)
        parcel.writeInt(6 or 0x00040000)
        parcel.writeInt(7)
        parcel.writeInt(7 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(8 or 0x00040000)
        parcel.writeInt(0)
        writeStringField(parcel, 9, "Google")
        writeStringField(parcel, 10, "Desktop Head Unit")
        writeStringField(parcel, 11, "2022-03-30-438482292")
        writeStringField(parcel, 12, "2.0-mac-arm64")
        parcel.writeInt(13 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(14 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(15 or 0x00040000)
        parcel.writeInt(0)
        writeStringField(parcel, 16, "")
        writeStringField(parcel, 17, "Google")
        parcel.writeInt(18 or 0x00040000)
        parcel.writeInt(1)
        val end = parcel.dataPosition()
        parcel.setDataPosition(start + 4)
        parcel.writeInt(end - (start + 8))
        parcel.setDataPosition(end)
    }

    private fun writeStringField(parcel: Parcel, id: Int, value: String) {
        val start = parcel.dataPosition()
        parcel.writeInt(id or 0xFFFF0000.toInt())
        parcel.writeInt(0)
        parcel.writeString(value)
        val end = parcel.dataPosition()
        parcel.setDataPosition(start + 4)
        parcel.writeInt(end - (start + 8))
        parcel.setDataPosition(end)
    }

    /**
     * Reads the setting name from an ICar setting query. Binder's onTransact Parcel starts
     * with the interface token, so this matches the same header/token layout used by logInbound.
     */
    private fun readSettingName(data: Parcel): String? {
        val pos = data.dataPosition()
        return try {
            data.setDataPosition(0)
            data.readInt() // strict mode policy
            data.readInt() // work source uid
            if (data.dataAvail() >= 4) data.readInt() // header marker
            data.readString() // interface token
            data.readString() // setting name
        } catch (_: Throwable) {
            null
        } finally {
            data.setDataPosition(pos)
        }
    }

    /** Debug: every call Maps makes into AARE, with its interface token and a hex peek of the args. */
    private fun logInbound(iface: String, code: Int, data: Parcel, flags: Int) {
        val pos = data.dataPosition()
        try {
            data.setDataPosition(0)
            data.readInt() // strict mode policy
            data.readInt() // work source uid
            if (data.dataAvail() >= 4) data.readInt() // header marker (Q+)
            val token = runCatching { data.readString() }.getOrNull()
            val argsAt = data.dataPosition()
            val str = runCatching { data.readString() }.getOrNull()
            val rest = buildList {
                while (data.dataAvail() >= 4 && size < 6) add(data.readInt())
            }
            data.setDataPosition(argsAt)
            Log.i(TRACE, "$iface tx $code size=${data.dataSize()} oneway=${flags and IBinder.FLAG_ONEWAY != 0} token=$token str=$str ints=$rest")
        } catch (t: Throwable) {
            Log.i(TRACE, "$iface tx $code size=${data.dataSize()} (unparsed: ${t.javaClass.simpleName})")
        } finally {
            data.setDataPosition(pos)
        }
    }

    private fun writeCarUiInfo(parcel: Parcel) {
        val start = parcel.dataPosition()
        parcel.writeInt(20293 or 0xFFFF0000.toInt())
        parcel.writeInt(0)
        for (id in 1..10) {
            if (id == 5) continue
            parcel.writeInt(id or 0x00040000)
            // 1 rotary, 2 touchscreen, 3 search button, 4 touchpad for UI navigation,
            // 6 D-pad, 7 touchscreen type, 8 absolute touchpad, 9/10 touchpad thresholds.
            parcel.writeInt(
                when (id) {
                    2, 3 -> 1  // has a touchscreen and a search button
                    7 -> 3     // stock gearhead reports 3, not 1
                    9 -> 48    // stock gearhead touchpad/finger thresholds
                    10 -> 64
                    else -> 0
                },
            )
        }
        val end = parcel.dataPosition()
        parcel.setDataPosition(start + 4)
        parcel.writeInt(end - (start + 8))
        parcel.setDataPosition(end)
    }

    companion object {
        private const val TAG = "ProjectedHost"
        private const val TRACE = "AareICarTrace"
        private const val DESCRIPTOR = "com.google.android.gms.car.ICarProjection"
    }
}
