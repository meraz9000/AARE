package net.prototux.aare.host

import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
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
            .background(Color.Black)
            .pointerInput(host) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    host.sendTouch(MotionEvent.ACTION_DOWN, down.position.x, down.position.y)
                    var sentUp = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (change.pressed) {
                            host.sendTouch(MotionEvent.ACTION_MOVE, change.position.x, change.position.y)
                        } else {
                            host.sendTouch(MotionEvent.ACTION_UP, change.position.x, change.position.y)
                            sentUp = true
                            break
                        }
                    }
                    if (!sentUp) host.sendTouch(MotionEvent.ACTION_CANCEL, down.position.x, down.position.y)
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (width <= 0 || height <= 0) return
                            lastSurface = holder.surface
                            lastWidth = width
                            lastHeight = height
                            host.start(holder.surface, width, height, dpi)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            lastSurface = null
                            host.stop()
                        }
                    })
                }
            },
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
                        host.start(lastSurface!!, lastWidth, lastHeight, dpi)
                    }) { Text("Retry") }
                }
            }
        }
    }
}
