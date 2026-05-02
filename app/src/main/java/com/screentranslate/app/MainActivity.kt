package com.screentranslate.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.screentranslate.feature.translator.service.ScreenCaptureService

class MainActivity : Activity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private var serviceMessage = "Service is stopped."
    private var serviceRunning = false
    private var capturedFrameCount = 0
    private var overlayAvailableInService = false
    private var statusReceiverRegistered = false

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_STATUS) return
            serviceMessage = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS_MESSAGE)
                ?: serviceMessage
            serviceRunning = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_RUNNING, false)
            capturedFrameCount = intent.getIntExtra(ScreenCaptureService.EXTRA_CAPTURED_FRAME_COUNT, 0)
            overlayAvailableInService = intent.getBooleanExtra(
                ScreenCaptureService.EXTRA_OVERLAY_AVAILABLE,
                false,
            )
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        setContentView(createContentView())
        requestRuntimePermissionsIfNeeded()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        registerServiceStatusReceiver()
        startService(ScreenCaptureService.createQueryStatusIntent(this))
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(serviceStatusReceiver)
            statusReceiverRegistered = false
        }
        super.onStop()
    }

    @Deprecated("Required for the platform MediaProjection permission contract on this min SDK.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            startForegroundServiceCompat(ScreenCaptureService.createStartIntent(this, resultCode, data))
            serviceMessage = "Starting video subtitle translation."
            serviceRunning = false
            updateStatus()
        } else {
            serviceMessage = "Screen capture permission was not granted."
            serviceRunning = false
            updateStatus()
        }
    }

    private fun createContentView(): LinearLayout {
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }

        val overlayButton = Button(this).apply {
            text = "Open overlay settings"
            setOnClickListener { openOverlaySettings() }
        }

        val startButton = Button(this).apply {
            text = "Start video subtitles"
            setOnClickListener { startCapturePermissionFlow() }
        }

        val stopButton = Button(this).apply {
            text = "Stop video subtitles"
            setOnClickListener {
                startService(ScreenCaptureService.createStopIntent(this@MainActivity))
                serviceMessage = "Stopping video subtitle translation."
                serviceRunning = false
                updateStatus()
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
            addView(TextView(this@MainActivity).apply {
                text = "ScreenTranslate"
                textSize = 28f
            })
            addView(statusText)
            addView(overlayButton)
            addView(startButton)
            addView(stopButton)
        }
    }

    private fun startCapturePermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            serviceMessage = "Overlay permission is required before video subtitles can start."
            serviceRunning = false
            updateStatus()
            openOverlaySettings()
            return
        }

        @Suppress("DEPRECATION")
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE,
        )
    }

    private fun openOverlaySettings() {
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
        val opened = intents.any { intent ->
            runCatching {
                startActivity(intent)
            }.isSuccess
        }
        if (!opened) {
            Toast.makeText(
                this,
                "Unable to open overlay settings on this device.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.RECORD_AUDIO
        }

        val missingPermissions = permissions
            .filter { permission -> checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
        }
    }

    private fun updateStatus() {
        val overlayStatus = if (Settings.canDrawOverlays(this)) {
            "granted"
        } else {
            "missing"
        }
        val serviceStatus = if (serviceRunning) "running" else "stopped"
        val subtitleOverlayStatus = if (overlayAvailableInService) "shown" else "not shown"
        statusText.text = buildString {
            appendLine("Overlay permission: $overlayStatus")
            appendLine("Service: $serviceStatus")
            appendLine("Frames captured: $capturedFrameCount")
            appendLine("Subtitle overlay: $subtitleOverlayStatus")
            append(serviceMessage)
        }
    }

    private fun registerServiceStatusReceiver() {
        if (statusReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            serviceStatusReceiver,
            IntentFilter(ScreenCaptureService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        statusReceiverRegistered = true
    }

    private companion object {
        const val REQUEST_SCREEN_CAPTURE = 4100
        const val REQUEST_RUNTIME_PERMISSIONS = 4101
    }
}
