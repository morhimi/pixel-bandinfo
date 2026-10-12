package com.ray.pixelbandinfo

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.ray.pixelbandinfo.services.KeepAliveService
import com.ray.pixelbandinfo.shizuku.ShizukuTelephonyManager
import com.ray.pixelbandinfo.ui.AppSettingsState
import com.ray.pixelbandinfo.ui.HomeScreen
import rikka.shizuku.Shizuku
import org.lsposed.hiddenapibypass.HiddenApiBypass

class HomeActivity : ComponentActivity() {

    private lateinit var telephonyManager: ShizukuTelephonyManager
    private val isInPipState = mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (::telephonyManager.isInitialized) {
            telephonyManager.registerTelephonyListeners()
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyListeners()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            HiddenApiBypass.addHiddenApiExemptions("L", "I")
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        try {
            val serviceIntent = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        telephonyManager = ShizukuTelephonyManager(this)
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}

        checkAndRequestPhonePermissions()
        updatePipParams()

        val prefs = getSharedPreferences("pixel_bandinfo_settings", MODE_PRIVATE)

        setContent {
            val simSlotStates by telephonyManager.simSlotStates.collectAsState()
            val isShizukuGranted by telephonyManager.isShizukuGranted.collectAsState()
            val isInPip by isInPipState

            var settings by remember {
                mutableStateOf(
                    AppSettingsState(
                        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
                        showQciInfo = prefs.getBoolean("show_qci_info", true),
                        onlyShowActiveSimInPip = prefs.getBoolean("only_show_active_sim_pip", true),
                        hideDetailsInPip = prefs.getBoolean("hide_details_pip", false)
                    )
                )
            }

            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            HomeScreen(
                simSlotStates = simSlotStates,
                isShizukuGranted = isShizukuGranted,
                isInPip = isInPip,
                settings = settings,
                onSettingsChanged = { newSettings ->
                    settings = newSettings
                    prefs.edit()
                        .putBoolean("keep_screen_on", newSettings.keepScreenOn)
                        .putBoolean("show_qci_info", newSettings.showQciInfo)
                        .putBoolean("only_show_active_sim_pip", newSettings.onlyShowActiveSimInPip)
                        .putBoolean("hide_details_pip", newSettings.hideDetailsInPip)
                        .apply()
                },
                onRequestShizukuPermission = { requestShizukuPermission() },
                onEnterPip = { enterPipMode() },
                onRefresh = {
                    checkAndRequestPhonePermissions()
                    telephonyManager.checkShizukuPermission()
                }
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState.value = isInPictureInPictureMode
    }

    private fun checkAndRequestPhonePermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(android.Manifest.permission.READ_PHONE_STATE)
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(1001)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(4, 3))
                .build()
            setPictureInPictureParams(params)
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(4, 3))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
        telephonyManager.unregister()
    }
}
