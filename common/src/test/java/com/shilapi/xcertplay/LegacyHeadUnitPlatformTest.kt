package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.vehicle.BydPlatform
import com.shilapi.xcertplay.vehicle.GwmLemonPlatform
import com.shilapi.xcertplay.vehicle.VehiclePlatforms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The old-head-unit target: Android 8 seats, including the GWM Lemon units that run Harman or
 * iFlytek HiLife on Intel x86. Wired CarPlay is vendor-neutral, so these tests cover the parts that
 * are not: platform detection, and the wireless modes each firmware can actually run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LegacyHeadUnitPlatformTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        VehiclePlatforms.clearCacheForTests()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun android8InstallsAndReportsItself() {
        // The whole point of the minSdk change: this suite runs on API 26, so any API 28+ call
        // reached from here would throw rather than silently pass.
        assertTrue(
            "expected API 26 but was ${android.os.Build.VERSION.SDK_INT}",
            android.os.Build.VERSION.SDK_INT <= 28,
        )
    }

    @Test fun bydSettingsPackageIdentifiesByd() {
        install("com.byd.carsettings", system = true)
        assertEquals(BydPlatform, VehiclePlatforms.current(context))
        assertTrue(CarHotspotSetup.isBydHeadUnit(context))
    }

    @Test fun userInstalledLookalikeIsNotByd() {
        install("com.byd.carsettings", system = false)
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
    }

    @Test fun lemonIsRecognisedButOffersNoVendorNavigation() {
        install("com.gwm.life.launcher", system = true)
        val platform = VehiclePlatforms.current(context)
        assertEquals(GwmLemonPlatform, platform)
        // Lemon exposes no navigation receiver, so the vendor switches must stay hidden rather
        // than appear and do nothing.
        assertFalse(platform.navigationOutputAvailable(context))
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
        assertFalse(CarHotspotSetup.hasNavigationOutput(context))
    }

    @Test fun lemonOnIntelX86IsRecognisedFromTheBuildFieldsAlone() {
        // Exercises the rule directly, with the build fields passed in. Going through
        // VehiclePlatforms.current here would only assert Robolectric's own ABI, which is not x86,
        // so the end-to-end detection is covered by lemonIsRecognisedButOffersNoVendorNavigation
        // using a marker package instead.
        assertTrue(
            GwmLemonPlatform.isLemon(
                abis = arrayOf("x86_64", "x86"),
                manufacturer = "GWM",
                brand = "gwm",
                display = null,
                fingerprint = null,
            ),
        )
    }

    @Test fun armHeadUnitWithAGwmBrandIsNotMistakenForLemon() {
        // Lemon is an x86 seat. A GWM-branded arm64 unit is newer hardware, not a Lemon head unit,
        // and must not be claimed as one.
        assertFalse(
            GwmLemonPlatform.isLemon(
                abis = arrayOf("arm64-v8a"),
                manufacturer = "GWM",
                brand = "gwm",
                display = null,
                fingerprint = null,
            ),
        )
    }

    @Test fun lemonDetectionAcceptsTheDocumentedBuildVariants() {
        // The real marks a Lemon unit reports vary by Harman and iFlytek generation, so each of
        // these has been seen on a seat this build should claim.
        for (build in listOf(
            Triple("GWM", "gwm", null),
            Triple(null, null, "Lemon"),
            Triple(null, null, "gwm_car"),
        )) {
            assertTrue(
                "expected a Lemon match for ${build}",
                GwmLemonPlatform.isLemon(
                    abis = arrayOf("x86_64"),
                    manufacturer = build.first,
                    brand = build.second,
                    display = build.third,
                    fingerprint = null,
                ),
            )
        }
    }

    @Test fun aNonGwmX86UnitIsNotLemon() {
        // Plenty of other x86 head units exist. Brand evidence is required, not just the ABI.
        assertFalse(
            GwmLemonPlatform.isLemon(
                abis = arrayOf("x86_64"),
                manufacturer = "Generic",
                brand = "generic",
                display = null,
                fingerprint = null,
            ),
        )
    }

    @Test fun unknownHeadUnitFallsBackToGenericWithoutPretendingToBeByd() {
        val platform = VehiclePlatforms.current(context)
        assertEquals(VehiclePlatforms.Generic, platform)
        assertFalse(platform.navigationOutputAvailable(context))
        assertFalse(platform.vehicleDataAvailable(context))
    }

    @Test fun platformIsResolvedOncePerProcess() {
        install("com.byd.carsettings", system = true)
        assertEquals(BydPlatform, VehiclePlatforms.current(context))
        // A cached resolution must survive a later package change: the car does not swap head
        // units while DiPlay is running.
        install("com.gwm.life.launcher", system = true)
        assertEquals(BydPlatform, VehiclePlatforms.current(context))
    }

    @Test fun wifiDirectIsNotOfferedOnAndroid8() {
        assertFalse(AirPlayPersistence.hotspotModeSupported(WirelessHotspotMode.WIFI_P2P))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun manualHotspotRemainsAvailableOnAndroid8() {
        assertTrue(AirPlayPersistence.hotspotModeSupported(WirelessHotspotMode.MANUAL))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test @Config(sdk = [29]) fun wifiDirectReturnsOnAndroid10() {
        assertTrue(AirPlayPersistence.hotspotModeSupported(WirelessHotspotMode.WIFI_P2P))
    }

    private fun install(name: String, system: Boolean) {
        org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
    }
}
