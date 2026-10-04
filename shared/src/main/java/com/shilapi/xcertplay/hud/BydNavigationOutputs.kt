package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.vehicle.VehiclePlatforms

/**
 * Stands in on a platform with no vehicle-data source. The iPhone then keeps using its own GPS for
 * dead reckoning and treats the car as one that reports nothing, which is exactly what it does on a
 * head unit that never answered.
 */
private object NoVehicleSpeed : VehicleSpeedSource {
    override fun start() = Unit
    override fun stop() = Unit
    override fun drain(): VehicleSpeedReading? = null
}

/** Nonblocking boundary between phone control messages and vendor services. */
object BydNavigationOutputs {
    private const val TAG = "DiPlay-NavOutput"
    /** Recover a journaled interrupted output when the app opens, even before a phone reconnects. */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        val platform = VehiclePlatforms.current(app)
        if (!platform.vehicleDataAvailable(app) && !platform.navigationOutputAvailable(app)) {
            Log.i(TAG, "no vendor output on ${platform.id}; CarPlay still runs")
            return
        }
        if (BydStandaloneHudOutput.available(context)) start(context)
        // Read the battery early, so a reading is ready when CarPlay identifies (see batteryStatus).
        if (BydOutputSettings.batteryToIphoneActive(context)) BydBatteryStatus.start(context)
    }
    fun setDiagnosticHold(hold: Boolean) { BydStandaloneHudOutput.syntheticHold = hold }
    @Volatile private var useStandalone = false
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
    )
    private val standalone = NavigationOutputWorker("diplay-standalone-output", BydStandaloneNavigationBridge::clear)
    private val hud = NavigationOutputWorker("diplay-hud-output", BydHudBridge::clear)
    private val cluster = NavigationOutputWorker("diplay-cluster-output", BydClusterBridge::clear)

    /** The host reports whether its CarPlay map window is on the cluster (see [BydClusterMapPause]). */
    fun setClusterMapShown(shown: Boolean) { BydClusterMapPause.clusterMapShown = shown }

    /** The running CarPlay session: told every second whether the cluster currently shows the map. */
    fun setClusterStreamControl(control: (Boolean) -> Unit) { BydClusterMapPause.streamControl = control }

    fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (BydClusterMapPause.streamControl == control) BydClusterMapPause.streamControl = null
    }

    /**
     * The car's battery for the iPhone's vehicle status; starts reading it over adb. The electric
     * vehicle is declared only once a reading is there (see withVehicleStatusFrom).
     *
     * A platform that cannot read vehicle data gets a provider that reports nothing, so the iPhone
     * treats the car as undeclared and falls back to its own range estimate rather than being told
     * an electric car with an unknown state.
     */
    fun batteryStatus(context: Context): VehicleStatusProvider {
        if (!VehiclePlatforms.current(context).vehicleDataAvailable(context)) {
            return VehicleStatusProvider { null }
        }
        return BydBatteryStatus.also { it.start(context) }
    }

    /** The car's wheel speed and gear for the iPhone's dead reckoning; read over adb while asked for. */
    fun wheelSpeed(context: Context): VehicleSpeedSource {
        if (!VehiclePlatforms.current(context).vehicleDataAvailable(context)) return NoVehicleSpeed
        return BydWheelSpeedSource.attach(context)
    }

    /** Whether the car is in P (read over adb), or null when it cannot tell. Blocking. */
    fun parked(context: Context): Boolean? {
        if (!VehiclePlatforms.current(context).vehicleDataAvailable(context)) return null
        return BydParkedState.parked(context.applicationContext)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        // A head unit with no navigation receiver has nowhere to send arrows. Every BYD path
        // below talks to a DiLink service, so on other platforms the whole output stays off
        // instead of binding a service that does not exist on every reconnect.
        if (!BydOutputSettings.navigationAvailable(app)) {
            Log.i(TAG, "navigation output inactive: ${VehiclePlatforms.current(app).id}")
            return
        }
        useStandalone = BydStandaloneHudOutput.available(app)
        if (useStandalone) standalone.start { BydStandaloneNavigationBridge.initialize(app) }
        else {
            hud.start { BydHudBridge.initialize(app) }
            cluster.start { BydClusterBridge.initialize(app) }
        }
        BydClusterMapPause.initialize(app)
        BydClusterSong.attach(app)
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            BydClusterSong.onFrame(frame)
            return
        }
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        updateOverlay(owned)
        if (useStandalone) standalone.submit { BydStandaloneNavigationBridge.onFrame(owned) }
        else {
            hud.submit { BydHudBridge.onFrame(owned) }
            cluster.submit { BydClusterBridge.onFrame(owned) }
        }
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = currentOverlay()
        synchronized(overlayLock) { publishedOverlay = next }
        listener?.invoke(next)
    }

    private fun updateOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val current = currentOverlay()
            if (current == publishedOverlay) return
            publishedOverlay = current
            current
        }
        overlayListener?.invoke(next)
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        overlayRoute.currentApple()?.let { ClusterTurnGuidance.from(BydClusterFrame.from(it)) }
    }

    /** The dashboard song setting changed; applies at once. */
    fun clusterSongChanged(enabled: Boolean) = BydClusterSong.settingChanged(enabled)

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow() {
        standalone.clear(); hud.clear(); cluster.clear(); BydClusterSong.end()
        synchronized(overlayLock) { overlayRoute.clear() }
        refreshTurnOverlay()
    }
}
