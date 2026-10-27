package net.prototux.aare.video

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.Executor

/**
 * A virtual display created through Android's companion VirtualDevice API.
 *
 * Unlike DisplayManager.createVirtualDisplay(), a VirtualDevice display can own a
 * VirtualTouchscreen. Touch events sent to that touchscreen go through InputDispatcher, so
 * windows on the display (including a SurfaceControlViewHost child such as Maps) receive
 * real input events instead of a synthetic View.dispatchTouchEvent().
 *
 * All of this API is hidden/system API, so it is reached through reflection. If the process
 * lacks CREATE_VIRTUAL_DEVICE or anything else fails, callers fall back to a normal
 * DisplayManager virtual display.
 */
internal class VirtualDeviceDisplay private constructor(
    val display: VirtualDisplay,
    private val device: Any,
    val deviceContext: android.content.Context?,
    private val touchscreen: Any?,
    private val touchscreenSend: java.lang.reflect.Method?,
    private val touchscreenClose: java.lang.reflect.Method?,
    private val deviceClose: java.lang.reflect.Method?,
    private val width: Int,
    private val height: Int,
) {
    val hasTouchscreen: Boolean
        get() = touchscreen != null && touchscreenSend != null

    fun sendTouch(event: MotionEvent): Boolean {
        val send = touchscreenSend ?: return false
        val ts = touchscreen ?: return false
        val b = touchBuilder ?: return false
        return runCatching {
            for (i in 0 until event.pointerCount) {
                val action = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> ACTION_DOWN
                    MotionEvent.ACTION_UP -> ACTION_UP
                    MotionEvent.ACTION_CANCEL -> ACTION_CANCEL
                    MotionEvent.ACTION_POINTER_DOWN ->
                        if (i == event.actionIndex) ACTION_DOWN else ACTION_MOVE
                    MotionEvent.ACTION_POINTER_UP ->
                        if (i == event.actionIndex) ACTION_UP else ACTION_MOVE
                    else -> ACTION_MOVE
                }
                // VirtualTouchEvent.Builder.build() rejects ACTION_CANCEL unless the tool is
                // TOOL_TYPE_PALM. A rejected cancel used to leave the slot stuck down, which
                // broke every later touch on the screen.
                val tool = if (action == ACTION_CANCEL) TOOL_TYPE_PALM else TOOL_TYPE_FINGER
                // The touchscreen's axes run 0..size-1; the car's edge taps can land on size.
                val x = event.getX(i).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
                val y = event.getY(i).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
                val e = b.newInstance()
                b.setAction.invoke(e, action)
                b.setX.invoke(e, x)
                b.setY.invoke(e, y)
                b.setPointer.invoke(e, event.getPointerId(i))
                b.setTime.invoke(e, event.eventTime * 1_000_000L)
                b.setPressure.invoke(e, event.getPressure(i).coerceIn(0f, 1f))
                b.setTool.invoke(e, tool)
                b.setMajor.invoke(e, 1f)
                val ok = send.invoke(ts, b.build.invoke(e))
                if (ok == false) Log.w(TAG, "sendTouchEvent rejected action=$action x=$x y=$y")
            }
            true
        }.getOrElse {
            Log.w(TAG, "VirtualTouchscreen.sendTouchEvent failed", it)
            false
        }
    }

    /** VirtualTouchEvent.Builder reflection, resolved once instead of on every event. */
    private class TouchBuilder(cls: Class<*>) {
        private val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
        private fun m(name: String, vararg types: Class<*>) =
            cls.getDeclaredMethod(name, *types).apply { isAccessible = true }
        val build = m("build")
        val setAction = m("setAction", Integer.TYPE)
        val setX = m("setX", java.lang.Float.TYPE)
        val setY = m("setY", java.lang.Float.TYPE)
        val setPointer = m("setPointerId", Integer.TYPE)
        val setTime = m("setEventTimeNanos", java.lang.Long.TYPE)
        val setPressure = m("setPressure", java.lang.Float.TYPE)
        val setTool = m("setToolType", Integer.TYPE)
        val setMajor = m("setMajorAxisSize", java.lang.Float.TYPE)
        fun newInstance(): Any = ctor.newInstance()
    }

    private val touchBuilder: TouchBuilder? = runCatching {
        TouchBuilder(Class.forName("android.hardware.input.VirtualTouchEvent\$Builder"))
    }.onFailure { Log.w(TAG, "VirtualTouchEvent.Builder unavailable", it) }.getOrNull()

    fun release() {
        runCatching { touchscreenClose?.invoke(touchscreen) }
        runCatching { deviceClose?.invoke(device) }
        runCatching { display.release() }
    }

    companion object {
        private const val TAG = "AareVirtualDevice"
        // android.hardware.input.VirtualTouchEvent constants.
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
        private const val ACTION_MOVE = 2
        private const val ACTION_CANCEL = 3
        private const val TOOL_TYPE_FINGER = 1
        private const val TOOL_TYPE_PALM = 5
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1
        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1

        fun create(
            context: Context,
            name: String,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface,
        ): VirtualDeviceDisplay? {
            if (Build.VERSION.SDK_INT < 34) return null
            val hasCreatePermission =
                context.checkSelfPermission("android.permission.CREATE_VIRTUAL_DEVICE") ==
                    PackageManager.PERMISSION_GRANTED
            if (!hasCreatePermission) {
                Log.i(TAG, "CREATE_VIRTUAL_DEVICE not granted; trying an owner-created device")
            }

            return runCatching {
                val manager = context.getSystemService("virtualdevice")
                    ?: error("VirtualDeviceManager unavailable")
                val device = createDevice(context, manager)
                val display = createDisplay(device, name, width, height, densityDpi, surface)
                val displayId = display.display.displayId
                val touchscreen = createTouchscreen(device, displayId, width, height)
                val send = touchscreen?.javaClass?.methods
                    ?.firstOrNull { it.name == "sendTouchEvent" && it.parameterTypes.size == 1 }
                val tsClose = touchscreen?.javaClass?.methods
                    ?.firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
                val devClose = device.javaClass.methods
                    .firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
                Log.i(TAG, "created display=$displayId touchscreen=${touchscreen != null}")
                val deviceContext = runCatching {
                    device.javaClass.methods.firstOrNull {
                        it.name == "createContext" && it.parameterTypes.isEmpty()
                    }?.invoke(device) as? android.content.Context
                }.getOrNull()
                VirtualDeviceDisplay(
                    display, device, deviceContext, touchscreen, send, tsClose, devClose, width, height,
                )
            }.getOrElse {
                Log.w(TAG, "VirtualDevice display unavailable; using DisplayManager fallback", it)
                null
            }
        }

        private fun createDevice(context: Context, manager: Any): Any {
            // This is the CompanionDeviceManager self-managed association created by
            // tools/aare-virtualdevice-setup.sh for the app-streaming profile. It is
            // the only association that VirtualDeviceManager allows AARE to operate.
            val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setLockState", Int::class.javaPrimitiveType)
                .invoke(builder, 0)
            builderClass.getMethod("setUsersWithMatchingAccounts", Set::class.java)
                .invoke(builder, setOf(Process.myUserHandle()))
            if (Build.VERSION.SDK_INT >= 35) {
                runCatching {
                    builderClass.getMethod(
                        "setDevicePolicy",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).invoke(builder, 2, 1)
                }
            }
            val allowed = setOf(
                ComponentName(context, Class.forName("net.prototux.aare.ui.MainActivity")),
                ComponentName(context, Class.forName("net.prototux.aare.ui.CarPreviewActivity")),
            )
            builderClass.getMethod("setAllowedActivities", Set::class.java).invoke(builder, allowed)
            val params = builderClass.getMethod("build").invoke(builder)
            val method = manager.javaClass.methods.first {
                it.name == "createVirtualDevice" && it.parameterTypes.size == 2
            }
            // Association IDs are local to CompanionDeviceManager and can change after a
            // reinstall. Try likely IDs; skip ID 1 (gearhead's association on the test phone).
            for (id in intArrayOf(3, 0, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)) {
                val device = runCatching { method.invoke(manager, id, params) }.getOrNull()
                if (device != null) {
                    Log.i(TAG, "created virtual device association=$id")
                    return device
                }
            }
            error("createVirtualDevice failed for all candidate associations")
        }

        private fun createDisplay(
            device: Any,
            name: String,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface,
        ): VirtualDisplay {
            val config = VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setFlags(
                    VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                        VIRTUAL_DISPLAY_FLAG_TRUSTED,
                )
                .setSurface(surface)
                .build()
            val executor = Executor { it.run() }
            val candidates = device.javaClass.methods.filter { it.name == "createVirtualDisplay" }
            val method = candidates.first { it.parameterTypes.size == 3 }
            return method.invoke(device, config, executor, null) as? VirtualDisplay
                ?: error("createVirtualDisplay returned null")
        }

        private fun createTouchscreen(
            device: Any,
            displayId: Int,
            width: Int,
            height: Int,
        ): Any? {
            val builderClass = Class.forName("android.hardware.input.VirtualTouchscreenConfig\$Builder")
            val builder = builderClass
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(width, height)
            builderClass.getMethod("setVendorId", Int::class.javaPrimitiveType)
                .invoke(builder, 0x1AE0)
            builderClass.getMethod("setProductId", Int::class.javaPrimitiveType)
                .invoke(builder, 0)
            builderClass.getMethod("setInputDeviceName", String::class.java)
                .invoke(builder, "AARE-TOUCHSCREEN:$displayId")
            builderClass.getMethod("setAssociatedDisplayId", Int::class.javaPrimitiveType)
                .invoke(builder, displayId)
            val config = builderClass.getMethod("build").invoke(builder)
            val method = device.javaClass.methods.first {
                it.name == "createVirtualTouchscreen" && it.parameterTypes.size == 1
            }
            return method.invoke(device, config)
        }
    }
}
