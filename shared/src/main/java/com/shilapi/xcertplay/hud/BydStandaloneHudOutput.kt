package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import java.security.MessageDigest

/** Ordinary-app IPC to the real stock receiver. No shell, local socket or permission grant. */
internal class BydStandaloneHudOutput private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("byd_standalone_hud", Context.MODE_PRIVATE)
    private val session = BydStandaloneSession(
        send = { packet ->
            app.sendBroadcast(Intent("byd.hud.NAVIGATION").setComponent(TARGET)
                .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            Log.d(TAG, "dispatch uid=${Process.myUid()} bytes=${packet.split(',').size}")
        },
        rememberPendingClear = { pending ->
            check(prefs.edit().putBoolean("pending_clear", pending).commit()) { "Cannot persist HUD cleanup" }
        },
        needsRecovery = prefs.getBoolean("pending_clear", false),
    )

    init {
        Log.i(TAG, "Standalone navigation ready uid=${Process.myUid()} helper=none")
        // Retain the journal if dispatch fails; the next scheduled tick retries.
        runCatching { session.clear() }.onFailure { Log.w(TAG, "Startup clear will retry", it) }
    }

    fun update(icon: Int, exit: Int, distanceMeters: Int, road: String) =
        session.update(icon, exit, distanceMeters, road)
    fun clear() = session.clear()

    companion object {
        private const val TAG = "BYD-Standalone-Live"
        private val TARGET = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")

        /** The DiLink 5.1 firmware this output was validated on. */
        private const val EXPECTED_FINGERPRINT =
            "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys"
        private const val EXPECTED_VERSION_CODE = 10601004L
        private const val EXPECTED_SIGNER_SHA256 =
            "efe3ca8ada0d10c655c3df9910ad2ebc121a47d9a6358434eb24074309933efc"
        private val ALLOWED_PACKAGES = setOf(
            "com.andrerinas.headunitrevived", "com.shihab.diplay",
            "com.andrerinas.headunitrevived.bydhudtest", "com.shihab.diplay.hudtest",
        )

        @Volatile var syntheticHold = false

        fun create(context: Context): BydStandaloneHudOutput? =
            if (available(context)) BydStandaloneHudOutput(context) else null

        /** Enable production and diagnostic packages only on the physically tested firmware. */
        fun available(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            if (context.packageName !in ALLOWED_PACKAGES) return false
            if (Build.FINGERPRINT != EXPECTED_FINGERPRINT) return false
            return runCatching { matchesTestedReceiver(context) }.getOrDefault(false)
        }

        /**
         * The signature, version and receiver checks that need API 28.
         *
         * Split out from [available] so the API level requirement is declared once, rather than
         * relying on the caller's early return to satisfy the platform check. Everything here
         * inspects signing metadata that only exists from Android 9 onwards, which matches the
         * DiLink 5 firmware this path was validated on.
         */
        @RequiresApi(Build.VERSION_CODES.P)
        private fun matchesTestedReceiver(context: Context): Boolean {
            val manager = context.packageManager
            val info = manager.getPackageInfo(TARGET.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val receiver = manager.getReceiverInfo(TARGET, 0)
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            return info.versionCodeCompat() == EXPECTED_VERSION_CODE &&
                info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                receiver.enabled && receiver.exported && receiver.permission.isNullOrEmpty() &&
                signers.size == 1 && MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) } == EXPECTED_SIGNER_SHA256
        }

        /**
         * The receiver's version as a Long on every supported API level.
         *
         * `getLongVersionCode` only arrived in API 28 and this app now supports API 26, so the
         * deprecated Int field is read below that. The receiver's version is a plain integer, so
         * widening it loses nothing here.
         */
        private fun PackageInfo.versionCodeCompat(): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
            else @Suppress("DEPRECATION") versionCode.toLong()
    }
}
