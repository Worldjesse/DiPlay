package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.vehicle.BydPlatform
import com.shilapi.xcertplay.vehicle.VehiclePlatforms

/** Each grant is requested explicitly from settings; startup never calls this authorization path. */
internal object CarHotspotSetup {
    /**
     * Whether the ADB hotspot controls apply to this head unit.
     *
     * Identity and navigation capability are different questions, and this one is about identity.
     * An older Qualcomm/qti BYD unit has no navigation-output service, yet ADB `appops` can still
     * start its hotspot, so it must keep reaching this setup. [hasNavigationOutput] is the other
     * question and answers differently on that same unit.
     */
    fun isBydHeadUnit(context: Context): Boolean =
        VehiclePlatforms.current(context).id == BydPlatform.id

    /**
     * Whether the head unit has a navigation receiver DiPlay can drive, which is a stricter
     * question than [isBydHeadUnit] and false on both a Qualcomm BYD unit and a Lemon head unit.
     */
    fun hasNavigationOutput(context: Context): Boolean =
        VehiclePlatforms.current(context).navigationOutputAvailable(context)

    enum class Permission(val appOp: String) {
        HOTSPOT("WRITE_SETTINGS"), BOOT_LAUNCH("SYSTEM_ALERT_WINDOW");

        fun granted(context: Context): Boolean = when (this) {
            HOTSPOT -> Settings.System.canWrite(context)
            BOOT_LAUNCH -> Settings.canDrawOverlays(context)
        }
    }

    fun check(context: Context, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access = adb.use {
        it.connect(mayAsk = false)
    }

    fun grant(context: Context, permissions: List<Permission>, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access =
        adb.use {
            val access = it.connect(mayAsk = true)
            Log.i("DiPlay-ADB", "switch connection: $access")
            if (access == LocalAdb.Access.READY) {
                for (permission in permissions) {
                    if (!permission.granted(context)) {
                        Log.i("DiPlay-ADB", "request permission: ${permission.appOp}")
                        it.shell("appops set ${context.packageName} ${permission.appOp} allow")
                    }
                    val granted = permission.granted(context)
                    Log.i("DiPlay-ADB", "permission ${permission.appOp}: granted=$granted")
                    if (!granted) break
                }
            }
            access
        }

    fun shouldStartOnLaunch(context: Context, hasSession: Boolean): Boolean =
        !hasSession && CarHotspotSettings.shouldEnable(context,
            AirPlayPersistence.loadWirelessEnabled(context), AirPlayPersistence.loadWirelessHotspotMode(context)) &&
            ManualHotspotValidation.error(AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context)) == null
}
