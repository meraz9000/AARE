package net.prototux.aare.host

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap

/**
 * A phone app that renders through the legacy GMS projected-activity protocol rather than the
 * public Car App Library. These apps have no CarAppService: their exported CarActivityService
 * speaks `com.google.android.gms.car.ICarProjection` and draws into a host-provided Surface.
 *
 * Known apps are listed explicitly: there is no public discovery action, and the services are
 * usually protected by a check inside the app (not by the manifest), so pretending that every
 * exported service speaking the protocol is safe would only produce confusing launcher entries.
 */
data class ProjectedAppInfo(
    val service: ComponentName,
    val label: String,
    val icon: Bitmap?,
) {
    val packageName: String get() = service.packageName
}

object ProjectedAppCatalog {
    private val KNOWN = listOf(
        ComponentName(
            "com.google.android.apps.maps",
            "com.google.android.apps.gmm.car.GmmCarProjectionService",
        ),
    )

    fun query(context: Context, iconSizePx: Int = 128): List<ProjectedAppInfo> {
        val pm = context.packageManager
        return KNOWN.mapNotNull { component ->
            val si = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getServiceInfo(component, 0)
                }
            }.getOrNull() ?: return@mapNotNull null
            if (!si.exported) return@mapNotNull null
            val app = si.applicationInfo
            ProjectedAppInfo(
                service = component,
                label = app.loadLabel(pm).toString(),
                icon = runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(),
            )
        }
    }

    fun find(context: Context, packageName: String): ProjectedAppInfo? =
        query(context).firstOrNull { it.packageName == packageName }
}
