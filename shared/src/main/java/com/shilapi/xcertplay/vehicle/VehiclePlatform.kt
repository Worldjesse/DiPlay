package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * One vehicle platform: the head-unit family DiPlay adapts to.
 *
 * The CarPlay receive path itself is vendor-neutral. iAP2 pairing, the AirPlay session, RTSP
 * streaming, HID input and audio all speak to the iPhone, not to the car. What differs between
 * head units is everything around that path: whether the car exposes navigation output to a
 * windshield HUD or an instrument cluster, and whether vehicle data (speed, gear, battery) can be
 * read for the iPhone's dead reckoning and range warning.
 *
 * A platform therefore answers two questions, and the core path never asks them.
 */
interface VehiclePlatform {
    /** Stable id used in preferences and diagnostics, e.g. `byd`, `gwm-lemon`. */
    val id: String

    /** Whether this platform drives the current head unit. Checked in order, first match wins. */
    fun matches(context: Context): Boolean

    /**
     * Whether the head unit can show CarPlay navigation arrows outside the CarPlay window, that
     * is on a windshield HUD or an instrument cluster. False means DiPlay keeps navigation inside
     * the projected window and hides the vendor navigation settings.
     */
    fun navigationOutputAvailable(context: Context): Boolean

    /**
     * Whether vehicle data may be read at all. This says nothing about whether a given field is
     * readable right now: that depends on ADB being available, which [navigationOutputAvailable]
     * and the per-field probe decide.
     */
    fun vehicleDataAvailable(context: Context): Boolean

    /**
     * A short label for the diagnostics screen. Must not assume a brand the driver cannot verify.
     */
    fun describe(): String
}

/** The recognised platform, resolved once per process and safe to cache. */
object VehiclePlatforms {
    private val ordered: List<VehiclePlatform> = listOf(
        BydPlatform,
        GwmLemonPlatform,
    )

    /**
     * An unrecognised head unit. CarPlay still works over USB; only the vendor extras are absent.
     * This is the honest default, because claiming a platform that was not detected is how a
     * driver ends up with a vendor settings screen that silently does nothing.
     */
    object Generic : VehiclePlatform {
        override val id = "generic"
        override fun matches(context: Context) = true
        override fun navigationOutputAvailable(context: Context) = false
        override fun vehicleDataAvailable(context: Context) = false
        override fun describe() = "Unrecognised head unit (CarPlay over USB only)"
    }

    @Volatile private var cached: VehiclePlatform? = null

    fun current(context: Context): VehiclePlatform {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: resolve(context.applicationContext).also { cached = it }
        }
    }

    /** Test-only: drops the process-wide resolution so the next call probes again. */
    fun clearCacheForTests() {
        synchronized(this) { cached = null }
    }

    private fun resolve(context: Context): VehiclePlatform =
        ordered.firstOrNull { runCatching { it.matches(context) }.getOrDefault(false) } ?: Generic

    /** Whether a package is present and is a preinstalled system app, not a user's own install. */
    internal fun isSystemPackage(context: Context, packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    /** Whether any of [packageNames] is installed, system or not. */
    internal fun anyInstalled(context: Context, vararg packageNames: String): Boolean =
        packageNames.any { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
}

/**
 * BYD DiLink, including the DiLink 5.x firmware that exposes navigation over SOME/IP.
 *
 * Detection leans on the stock settings package and the navigation receiver rather than on
 * `Build.FINGERPRINT`, which differs across every DiLink generation and would need a list that
 * goes stale. [navigationOutputAvailable] keeps the upstream probe, so a BYD unit that passes
 * detection but exposes no receiver still hides the settings instead of showing a dead switch.
 */
object BydPlatform : VehiclePlatform {
    override val id = "byd"

    private val SETTINGS_PACKAGES = arrayOf("com.byd.carsettings")
    private val NAVIGATION_PACKAGES = arrayOf("com.byd.amapservice", "com.ts.car.someip.service")

    override fun matches(context: Context): Boolean =
        VehiclePlatforms.isSystemPackage(context, SETTINGS_PACKAGES[0]) ||
            VehiclePlatforms.anyInstalled(context, *NAVIGATION_PACKAGES)

    /**
     * The upstream receiver probe: the standalone DiLink 5 path, or either stock navigation
     * receiver. This is the raw check, deliberately not routed back through
     * `BydOutputSettings.navigationAvailable`, which asks the platform registry and would recurse.
     */
    override fun navigationOutputAvailable(context: Context): Boolean =
        BydStandaloneHudOutput.available(context) ||
            VehiclePlatforms.anyInstalled(context, *NAVIGATION_PACKAGES)

    override fun vehicleDataAvailable(context: Context): Boolean = navigationOutputAvailable(context)

    override fun describe() = "BYD DiLink"
}

/**
 * Great Wall Motor's Lemon seat, in the Harman and iFlytek HiLife generations.
 *
 * These head units run Android 8.1 on Intel x86, so the CarPlay path is reached over USB, or over
 * a hotspot the driver starts, because Wi-Fi Direct needs Android 10 (see
 * `AirPlayPersistence.supportedHotspotMode`).
 *
 * Vehicle data is reported as unavailable: unlike DiLink, Lemon exposes no autoservice binder that
 * an unprivileged app can read over ADB, and its steering-wheel and cluster integrations are not
 * documented. DiPlay therefore does not pretend to read speed or battery here. The iPhone falls
 * back to its own GPS and range estimate, which is what it does on any car that reports nothing.
 * Adding a real source means implementing [navigationOutputAvailable] and the vehicle-data reader
 * against a Lemon head unit, not guessing at service names.
 */
object GwmLemonPlatform : VehiclePlatform {
    override val id = "gwm-lemon"

    /**
     * Lemon firmware ships under several marks, so both the GWM-prefixed and vendor-branded
     * packages are checked. A system app is required: the user installing a launcher that happens
     * to share a name must not flip DiPlay into a platform it cannot drive.
     */
    private val MARKER_PACKAGES = arrayOf(
        "com.gwm.life.launcher",
        "com.gwm.life",
        "com.gwm.harman.launcher",
        "com.harman.android.infotainment",
        "com.iflytek.vehicle.oshi",
    )

    override fun matches(context: Context): Boolean =
        MARKER_PACKAGES.any { VehiclePlatforms.isSystemPackage(context, it) } ||
            isLemon(abis = Build.SUPPORTED_ABIS, manufacturer = Build.MANUFACTURER,
                brand = Build.BRAND, display = Build.DISPLAY, fingerprint = Build.FINGERPRINT)

    /**
     * A Lemon unit is x86. Matching the ABI catches a head unit whose launcher package was renamed
     * in an OTA, which the package list alone would miss.
     *
     * Both halves are required on purpose. GWM ships arm64 head units on newer Coffee OS hardware,
     * and a GWM-branded arm64 device is not a Lemon seat; matching the brand alone would claim it
     * and then offer navigation output that does not exist.
     *
     * The build fields are parameters rather than read inline so the rule can be tested on every
     * documented firmware mark without a shadowed [Build].
     */
    fun isLemon(
        abis: Array<String>?,
        manufacturer: String?,
        brand: String?,
        display: String?,
        fingerprint: String?,
    ): Boolean {
        val onX86 = abis.orEmpty().any { it == "x86" || it == "x86_64" }
        val gwm = manufacturer.equals("gwm", ignoreCase = true) ||
            brand.equals("gwm", ignoreCase = true) ||
            display?.contains("GWM", ignoreCase = true) == true ||
            display?.contains("Lemon", ignoreCase = true) == true ||
            fingerprint?.contains("gwm", ignoreCase = true) == true
        return onX86 && gwm
    }

    override fun navigationOutputAvailable(context: Context): Boolean = false

    override fun vehicleDataAvailable(context: Context): Boolean = false

    override fun describe() = "GWM Lemon (Harman / iFlytek HiLife)"
}
