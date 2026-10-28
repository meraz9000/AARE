package net.prototux.aare.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap

/** A Car App Library app installed on the phone. */
data class CarAppInfo(
    val service: ComponentName,
    val label: String,
    val icon: Bitmap?,
    val categories: Set<String>,
    val minApiLevel: Int,
) {
    val packageName: String get() = service.packageName
    val isNavigation: Boolean get() = CATEGORY_NAVIGATION in categories

    companion object {
        const val SERVICE_ACTION = "androidx.car.app.CarAppService"
        const val CATEGORY_NAVIGATION = "androidx.car.app.category.NAVIGATION"
        const val CATEGORY_POI = "androidx.car.app.category.POI"
        const val CATEGORY_IOT = "androidx.car.app.category.IOT"
        const val CATEGORY_MESSAGING = "androidx.car.app.category.MESSAGING"
        const val CATEGORY_CLUSTER = "androidx.car.app.category.FEATURE_CLUSTER"
        const val MIN_API_KEY = "androidx.car.app.minCarApiLevel"

        /** The caster app is ours in the car UI, even though it is installed as a separate APK. */
        const val AARE_CASTER_PACKAGE = "dev.lab.ytcar"
        const val AARE_CASTER_LABEL = "AARE Caster"
    }
}

/** Finds Car App Library apps: services answering `androidx.car.app.CarAppService`. */
object CarAppCatalog {

    fun query(context: Context, iconSizePx: Int = 128): List<CarAppInfo> {
        val pm = context.packageManager
        val intent = Intent(CarAppInfo.SERVICE_ACTION)
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_RESOLVED_FILTER
        val found = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.queryIntentServices(intent, flags)
        return found.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val categories = buildSet {
                ri.filter?.categoriesIterator()?.forEach { add(it) }
            }
            val appInfo = si.applicationInfo
            val minApi = appInfo.metaData?.getInt(CarAppInfo.MIN_API_KEY, 0)?.takeIf { it > 0 }
                ?: si.metaData?.getInt(CarAppInfo.MIN_API_KEY, 0) ?: 0
            val installedLabel = (si.loadLabel(pm).takeIf { it.isNotBlank() } ?: appInfo.loadLabel(pm)).toString()
            CarAppInfo(
                service = ComponentName(si.packageName, si.name),
                label = if (si.packageName == CarAppInfo.AARE_CASTER_PACKAGE) {
                    CarAppInfo.AARE_CASTER_LABEL
                } else {
                    installedLabel
                },
                icon = runCatching { si.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(),
                categories = categories,
                minApiLevel = minApi,
            )
        }.distinctBy { it.service }
    }
}
