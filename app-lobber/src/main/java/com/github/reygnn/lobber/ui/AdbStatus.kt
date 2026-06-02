package com.github.reygnn.lobber.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * ADB debugging status on the phone — both evaluable in parallel:
 *  - [usbDebug]: classic USB debugging via `adb_enabled`
 *  - [wifiDebug]: Wireless Debugging (Android 11+) via `adb_wifi_enabled`
 *
 * Relevant for Lobber as "build host pushes AAB to the test phone": without ADB
 * the push does not go through on the other end.
 */
data class AdbStatus(val usbDebug: Boolean, val wifiDebug: Boolean) {
    val anyEnabled: Boolean get() = usbDebug || wifiDebug
}

private const val SETTING_ADB_ENABLED = "adb_enabled"

/**
 * `Settings.Global.ADB_WIFI_ENABLED` is `@hide` (since Android 11). The
 * key name itself is stable across several AOSP versions; we read it
 * directly as a String. If Google ever renames the key, the Wifi status
 * silently falls back to `false` — no crash risk.
 */
private const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"

private fun readAdbStatus(context: Context): AdbStatus {
    val resolver = context.contentResolver
    val usb = Settings.Global.getInt(resolver, SETTING_ADB_ENABLED, 0) == 1
    val wifi = Settings.Global.getInt(resolver, SETTING_ADB_WIFI_ENABLED, 0) == 1
    return AdbStatus(usbDebug = usb, wifiDebug = wifi)
}

fun adbStatusFlow(context: Context): Flow<AdbStatus> = callbackFlow {
    trySend(readAdbStatus(context))
    val resolver = context.contentResolver
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(readAdbStatus(context))
        }
    }
    resolver.registerContentObserver(
        Settings.Global.getUriFor(SETTING_ADB_ENABLED),
        false,
        observer,
    )
    resolver.registerContentObserver(
        Settings.Global.getUriFor(SETTING_ADB_WIFI_ENABLED),
        false,
        observer,
    )
    awaitClose { resolver.unregisterContentObserver(observer) }
}

@Composable
fun adbStatusState(): State<AdbStatus> {
    val context = LocalContext.current
    val flow = remember(context) { adbStatusFlow(context) }
    return flow.collectAsStateWithLifecycle(initialValue = AdbStatus(usbDebug = false, wifiDebug = false))
}
