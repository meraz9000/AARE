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
    private val touchscreen: Any?,
    private val touchscreenSend: java.lang.reflect.Method?,
    private val touchscreenClose: java.lang.reflect.Method?,
    private val deviceClose: java.lang.reflect.Method?,
) {
    fun sendTouch(event: MotionEvent): Boolean {
        val send = touchscreenSend ?: return false
        val ts = touchscreen ?: return false
        return runCatching {
            send.invoke(ts, event)
            true
        }.getOrElse {
            Log.w(TAG, "VirtualTouchscreen.sendTouchEvent failed", it)
            false
        }
    }

    fun release() {
        runCatching { touchscreenClose?.invoke(touchscreen) }
        runCatching { deviceClose?.invoke(device) }
        runCatching { display.release() }
    }

    companion object {
        private const val TAG = "AareVirtualDevice"
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
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
            if (context.checkSelfPermission("android.permission.CREATE_VIRTUAL_DEVICE") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "CREATE_VIRTUAL_DEVICE not granted; using DisplayManager fallback")
                return null
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
                VirtualDeviceDisplay(display, device, touchscreen, send, tsClose, devClose)
            }.getOrElse {
                Log.w(TAG, "VirtualDevice display unavailable; using DisplayManager fallback", it)
                null
            }
        }

        private fun createDevice(context: Context, manager: Any): Any {
            val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setLockState", Int::class.javaPrimitiveType)
                .invoke(builder, 1)
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
            return method.invoke(manager, 1, params)
                ?: error("createVirtualDevice returned null")
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
                        VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH,
                )
                .setSurface(surface)
                .build()
            val executor = Executor { it.run() }
            val method = device.javaClass.methods.first {
                it.name == "createVirtualDisplay" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == VirtualDisplayConfig::class.java
            }
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
