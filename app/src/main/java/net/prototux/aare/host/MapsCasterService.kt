package net.prototux.aare.host

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.AppManager
import androidx.car.app.validation.HostValidator
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import java.util.concurrent.Executors

/**
 * AARE Maps: a Car App Library wrapper around Google Maps' private projected activity.
 *
 * The host side of AARE sees this like any navigation app, renders its map surface and sends
 * ordinary Car App Library map gestures back. Inside, [ProjectedHost] binds
 * `GmmCarProjectionService` and forwards those gestures to Maps over ICarProjection.
 */
class MapsCasterService : CarAppService() {
    private var session: MapsCasterSession? = null

    override fun onCreateSession(): Session = MapsCasterSession(this).also { session = it }

    override fun onDestroy() {
        session?.shutdown()
        session = null
        super.onDestroy()
    }

    // AARE is the host and calls this service as the same UID; a permissive validator keeps the
    // bridge independent of the host certificate used by the private Maps path.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
}

private class MapsCasterSession(private val service: MapsCasterService) : Session() {
    private var host: ProjectedHost? = null
    private val touch = Executors.newSingleThreadExecutor { r -> Thread(r, "AareMapsCasterTouch") }
    private val main = Handler(Looper.getMainLooper())
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var touching = false
    private var touchX = 0f
    private var touchY = 0f
    private val endTouch = Runnable {
        if (touching) {
            touching = false
            forward(MotionEvent.ACTION_UP, touchX, touchY, 0)
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val screen = object : Screen(carContext) {
            override fun onGetTemplate(): Template {
                return NavigationTemplate.Builder()
                    .setActionStrip(
                        ActionStrip.Builder()
                            .addAction(
                                Action.Builder()
                                    .setTitle("Recenter")
                                    .setIcon(CarIcon.APP_ICON)
                                    .setOnClickListener { tapCenter() }
                                    .build(),
                            )
                            .build(),
                    )
                    .build()
            }
        }
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                Log.i(TAG, "surface available ${container.width}x${container.height}@${container.dpi}")
                surfaceWidth = container.width
                surfaceHeight = container.height
                val surface = container.surface ?: return
                if (host == null) {
                    host = ProjectedHost(
                        service,
                        android.content.ComponentName(MAPS_PACKAGE, MAPS_SERVICE),
                        object : ProjectedHost.Listener {
                            override fun onStatus(text: String) {
                                Log.i(TAG, "Maps: $text")
                                Handler(Looper.getMainLooper()).post { screen.invalidate() }
                            }

                            override fun onError(message: String) {
                                Log.w(TAG, "Maps error: $message")
                                Handler(Looper.getMainLooper()).post { screen.invalidate() }
                            }
                        },
                    )
                    host?.start(surface, container.width, container.height, container.dpi)
                }
            }

            override fun onSurfaceDestroyed(container: SurfaceContainer) {
                Log.i(TAG, "surface destroyed")
                main.removeCallbacks(endTouch)
                touching = false
                host?.stop()
                host = null
            }

            override fun onClick(x: Float, y: Float) {
                main.removeCallbacks(endTouch)
                touchX = x
                touchY = y
                forward(MotionEvent.ACTION_DOWN, x, y, 0)
                forward(MotionEvent.ACTION_UP, x, y, 60)
            }

            override fun onScroll(dx: Float, dy: Float) {
                main.removeCallbacks(endTouch)
                if (!touching) {
                    touching = true
                    touchX = surfaceWidth / 2f
                    touchY = surfaceHeight / 2f
                    forward(MotionEvent.ACTION_DOWN, touchX, touchY, 0)
                }
                // Car App onScroll uses previous-current; the finger moves by -dx/-dy.
                touchX = (touchX - dx).coerceIn(0f, surfaceWidth.toFloat())
                touchY = (touchY - dy).coerceIn(0f, surfaceHeight.toFloat())
                forward(MotionEvent.ACTION_MOVE, touchX, touchY, 30)
                main.postDelayed(endTouch, 90)
            }

            override fun onFling(velocityX: Float, velocityY: Float) {
                main.removeCallbacks(endTouch)
                if (touching) {
                    touching = false
                    forward(MotionEvent.ACTION_UP, touchX, touchY, 0)
                }
            }

            override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
                // Until two-pointer forwarding is wired, at least keep the touch centered so a
                // pinch does not act like a stray tap elsewhere.
                touchX = focusX
                touchY = focusY
            }
        })
        return screen
    }

    private fun tapCenter() {
        val x = surfaceWidth / 2f
        val y = surfaceHeight / 2f
        forward(MotionEvent.ACTION_DOWN, x, y, 0)
        forward(MotionEvent.ACTION_UP, x, y, 60)
    }

    private fun forward(action: Int, x: Float, y: Float, delayMs: Long) {
        val h = host ?: return
        touch.execute {
            if (delayMs > 0) Thread.sleep(delayMs)
            h.sendTouch(action, x, y)
        }
    }

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
    }

    fun shutdown() {
        host?.stop()
        host = null
        touch.shutdown()
    }

    companion object {
        private const val TAG = "AareMapsCaster"
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val MAPS_SERVICE = "com.google.android.apps.gmm.car.GmmCarProjectionService"
    }
}
