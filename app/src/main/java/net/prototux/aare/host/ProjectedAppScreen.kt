package net.prototux.aare.host

import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import net.prototux.aare.ui.car.CarAppEntry

/**
 * A legacy GMS projected app (Google Maps) on the car screen. Its real UI is drawn into a
 * SurfaceView; this screen forwards touch and hardware keys back to the app's CarActivityService.
 */
@Composable
fun ProjectedAppScreen(runtime: CarRuntime, app: CarAppEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = runtime.projectedApp(app.id) ?: return
    var status by remember { mutableStateOf<String?>("Connecting to ${info.label}…") }
    var error by remember { mutableStateOf<String?>(null) }
    var lastSurface by remember { mutableStateOf<Surface?>(null) }
    var lastSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var lastWidth by remember { mutableStateOf(0) }
    var lastHeight by remember { mutableStateOf(0) }
    val host = remember(app.id) {
        ProjectedHost(
            context = context,
            component = info.service,
            listener = object : ProjectedHost.Listener {
                override fun onStatus(text: String) {
                    status = text
                    error = null
                }

                override fun onError(message: String) {
                    error = message
                    status = null
                }
            },
        )
    }
    val dpi = runtime.densityDpi

    DisposableEffect(host) {
        runtime.activeProjectedHost = host
        onDispose {
            if (runtime.activeProjectedHost === host) runtime.activeProjectedHost = null
            host.stop()
        }
    }

    LaunchedEffect(status, error) {
        if (status != null && error == null) {
            delay(2_500)
            status = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    val surfaceView = this
                    lastSurfaceView = surfaceView
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (width <= 0 || height <= 0) return
                            lastSurface = holder.surface
                            lastWidth = width
                            lastHeight = height
                            host.start(surfaceView, width, height, dpi)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            lastSurface = null
                            host.stop()
                        }
                    })
                }
            },
        )
        // AARE receives car touch synthetically (not through InputDispatcher), so the
        // SurfaceControlViewHost child cannot receive native input. Forward raw events over
        // tx7; the Maps hooks dispatch them into the SCVH window.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> TouchForwardView(ctx, host) },
        )
        (error ?: status)?.let { text ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(20.dp),
            ) {
                Text(text = text, color = Color.White, textAlign = TextAlign.Center)
                if (error != null && lastSurface != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        error = null
                        status = "Retrying ${info.label}…"
                        val view = lastSurfaceView
                        if (view != null) host.start(view, lastWidth, lastHeight, dpi)
                        else host.start(lastSurface!!, lastWidth, lastHeight, dpi)
                    }) { Text("Retry") }
                }
            }
        }
    }
}


/** Transparent touch surface that forwards raw Android events to the projected app. */
private class TouchForwardView(context: Context, private val host: ProjectedHost) : View(context) {
    init {
        isClickable = true
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        host.sendTouch(event)
        return true
    }
}
