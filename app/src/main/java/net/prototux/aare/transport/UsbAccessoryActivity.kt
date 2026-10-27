package net.prototux.aare.transport

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.IntentCompat
import net.prototux.aare.service.ProjectionService

/**
 * Receives USB_ACCESSORY_ATTACHED when a head unit switches the phone to accessory mode, hands
 * the accessory to the projection service and goes away. (An activity, because Android only
 * delivers accessory attachment, with permission granted, to activities.)
 */
class UsbAccessoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forward(intent)
        finish()
    }

    private fun forward(intent: Intent?) {
        val accessory = intent?.let { IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_ACCESSORY, android.hardware.usb.UsbAccessory::class.java) }
        if (accessory == null) {
            Log.w("AareUsb", "attach without accessory: ${intent?.action} extras=${intent?.extras?.keySet()}")
            return
        }
        Log.i("AareUsb", "attach: ${accessory.manufacturer}/${accessory.model} v${accessory.version}")
        ProjectionService.startUsb(this, accessory)
    }
}
