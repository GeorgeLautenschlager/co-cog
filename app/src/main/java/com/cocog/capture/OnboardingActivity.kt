package com.cocog.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Blocking onboarding gate (D18). Until RECORD_AUDIO and unrestricted-battery are
 * both granted, the screen blocks with a request affordance for the next missing
 * grant. Once fully permissioned, it shows the start affordance. UI is intentionally
 * bare — this is the wave-1 skeleton, not the product surface.
 */
class OnboardingActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            }
        status = TextView(this)
        actionButton = Button(this)
        root.addView(status)
        root.addView(actionButton)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        when {
            !Permissions.hasRecordAudio(this) -> {
                status.text = "Microphone access is required.\nco-cog cannot capture without it."
                actionButton.text = "Grant microphone"
                actionButton.setOnClickListener {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                }
            }
            !Permissions.hasUnrestrictedBattery(this) -> {
                status.text = "Unrestricted battery is required so capture survives all day."
                actionButton.text = "Allow unrestricted battery"
                actionButton.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            else -> {
                status.text = "Ready to capture."
                actionButton.text = "Start capture"
                actionButton.setOnClickListener {
                    val intent = Intent(this, CaptureService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private companion object {
        const val REQ_MIC = 1001
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
