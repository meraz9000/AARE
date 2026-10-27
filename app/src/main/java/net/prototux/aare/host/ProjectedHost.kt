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

/**
 * Host for one legacy GMS projected-activity service (the protocol behind Google Maps' car UI).
 *
 * This is the production port of tools/gearhead-projection-probe: bind to the app's exported
 * CarActivityService, call onSetup (ICarProjection tx 1), then onProjectionStart (tx 26) with a
 * DrawingSpec whose surface is the SurfaceView on the car screen, and finally onProjectionResume
 * (tx 4). The app creates a Presentation on that surface and draws its real UI into it.
 *
 * The service checks CAPTURE_VIDEO_OUTPUT inside the app; a normal install is refused. See
 * docs/research/gearhead-projected-activity.md for the rooted/Frida development path.
 */
class ProjectedHost(
    context: Context,
    private val component: ComponentName,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onError(message: String)
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: IBinder? = null
    private var connection: ServiceConnection? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0
    private var touchDownTime = 0L
    @Volatile private var generation = 0

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 23) {
                val state = runCatching { data.readInt() }.getOrNull()
                Log.i(TAG, "callback: connection state $state")
            } else {
                Log.i(TAG, "callback: transaction $code")
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
                19, 71 -> {
                    runCatching { data.readString(); data.readInt() }
                    reply?.writeNoException()
                    reply?.writeInt(0)
                }
                55 -> {
                    runCatching { data.readString(); data.createStringArrayList() }
                    reply?.writeNoException()
                    reply?.writeStringList(emptyList())
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
        if (this.surface === surface && this.width == width && this.height == height &&
            this.densityDpi == densityDpi && connection != null
        ) return
        stop()
        if (width <= 0 || height <= 0) return
        this.surface = surface
        this.width = width
        this.height = height
        this.densityDpi = densityDpi
        touchDownTime = 0L
        generation++
        bind(generation)
    }

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

        if (!call(binder, "onProjectionStart", 26) { data ->
                data.writeInt(0) // CarDisplayId: main display
                data.writeInt(0) // CarRegionId
                data.writeInt(1) // DrawingSpec presence
                writeDrawingSpec(data, width, height, densityDpi, boundSurface, Rect(0, 0, width, height), 0)
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
        call(binder, "onProjectionResume", 4) { data -> data.writeInt(0) }
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
                    "This feature needs CAPTURE_VIDEO_OUTPUT (root/Frida development path)."
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

    /** Sends a touch to the projected app, in surface coordinates. */
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
                data.writeInt(0)
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
                data.writeInt(0)
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
            binder.transact(code, data, null, 0)
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

    private fun writeDrawingSpec(parcel: Parcel, width: Int, height: Int, dpi: Int, surface: Surface, insets: Rect, displayId: Int) {
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
        writeStringField(parcel, 1, "AARE")
        writeStringField(parcel, 2, "Projected")
        writeStringField(parcel, 3, "AARE")
        writeStringField(parcel, 4, "AARE")
        parcel.writeInt(5 or 0x00040000)
        parcel.writeInt(2026)
        parcel.writeInt(6 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(7 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(8 or 0x00040000)
        parcel.writeInt(0)
        writeStringField(parcel, 9, "")
        writeStringField(parcel, 10, "")
        writeStringField(parcel, 11, "")
        writeStringField(parcel, 12, "")
        parcel.writeInt(13 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(14 or 0x00040000)
        parcel.writeInt(0)
        parcel.writeInt(15 or 0x00040000)
        parcel.writeInt(0)
        writeStringField(parcel, 16, "")
        writeStringField(parcel, 17, "")
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

    private fun writeCarUiInfo(parcel: Parcel) {
        val start = parcel.dataPosition()
        parcel.writeInt(20293 or 0xFFFF0000.toInt())
        parcel.writeInt(0)
        for (id in 1..10) {
            if (id == 5) continue
            parcel.writeInt(id or 0x00040000)
            parcel.writeInt(0)
        }
        val end = parcel.dataPosition()
        parcel.setDataPosition(start + 4)
        parcel.writeInt(end - (start + 8))
        parcel.setDataPosition(end)
    }

    companion object {
        private const val TAG = "ProjectedHost"
        private const val DESCRIPTOR = "com.google.android.gms.car.ICarProjection"
    }
}
