package com.impel.touchlock

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * The only Activity. Plain `android.widget` views, no AppCompat, no Material: the whole screen is
 * one XML layout and a handful of listeners. It owns permission onboarding and the settings that
 * [BubbleService] reads live through its SharedPreferences listener.
 */
class SettingsActivity : Activity() {

    private lateinit var btnService: Button
    private lateinit var txtService: TextView
    private lateinit var txtOverlay: TextView
    private lateinit var txtNotifications: TextView
    private lateinit var txtAdmin: TextView
    private lateinit var txtAccessibility: TextView
    private lateinit var txtBattery: TextView
    private lateinit var txtSize: TextView
    private lateinit var txtAlpha: TextView
    private lateinit var txtIdleAlpha: TextView
    private lateinit var txtTimer: TextView
    private lateinit var seekSize: SeekBar
    private lateinit var seekAlpha: SeekBar
    private lateinit var seekIdleAlpha: SeekBar
    private lateinit var chkIdle: CheckBox
    private lateinit var chkBoot: CheckBox
    private lateinit var chkZeroBrightness: CheckBox
    private lateinit var editMinutes: EditText
    private lateinit var radioAdmin: RadioButton
    private lateinit var radioA11y: RadioButton

    private var suppressSeekCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnService = findViewById(R.id.btnService)
        txtService = findViewById(R.id.txtService)
        txtOverlay = findViewById(R.id.txtOverlay)
        txtNotifications = findViewById(R.id.txtNotifications)
        txtAdmin = findViewById(R.id.txtAdmin)
        txtAccessibility = findViewById(R.id.txtAccessibility)
        txtBattery = findViewById(R.id.txtBattery)
        txtSize = findViewById(R.id.txtSize)
        txtAlpha = findViewById(R.id.txtAlpha)
        txtIdleAlpha = findViewById(R.id.txtIdleAlpha)
        txtTimer = findViewById(R.id.txtTimer)
        seekSize = findViewById(R.id.seekSize)
        seekAlpha = findViewById(R.id.seekAlpha)
        seekIdleAlpha = findViewById(R.id.seekIdleAlpha)
        chkIdle = findViewById(R.id.chkIdle)
        chkBoot = findViewById(R.id.chkBoot)
        chkZeroBrightness = findViewById(R.id.chkZeroBrightness)
        editMinutes = findViewById(R.id.editMinutes)
        radioAdmin = findViewById(R.id.radioAdmin)
        radioA11y = findViewById(R.id.radioA11y)

        findViewById<TextView>(R.id.txtVersion).text = getString(
            R.string.settings_version,
            versionName(),
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        )

        wirePermissions()
        wireBubbleLook()
        wireTimer()
        wireLocking()

        // Opening the app is the natural "make sure the bubble is running" gesture. It never
        // restarts a service the user stopped explicitly (that clears bubbleEnabled).
        if (Prefs.bubbleEnabled(this) && Settings.canDrawOverlays(this) && !BubbleService.isRunning()) {
            BubbleService.start(this)
        }

        if (intent?.getBooleanExtra(EXTRA_FOCUS_TIMER, false) == true) {
            editMinutes.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FOCUS_TIMER, false)) editMinutes.requestFocus()
    }

    // ------------------------------------------------------------------ permissions

    private fun wirePermissions() {
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openSafely(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        findViewById<Button>(R.id.btnNotifications).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                openSafely(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }

        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            if (Locker.isAdminActive(this)) {
                try {
                    getSystemService(DevicePolicyManager::class.java)
                        .removeActiveAdmin(Locker.adminComponent(this))
                } catch (_: Throwable) {
                }
                Toast.makeText(this, "Device admin removed", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                openSafely(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Locker.adminComponent(this))
                        .putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Used only for the sleep timer and the “lock now” action."
                        )
                )
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val opened = openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            if (!opened) {
                openSafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }

        btnService.setOnClickListener {
            if (BubbleService.isRunning()) {
                Prefs.setBubbleEnabled(this, false)
                stopService(Intent(this, BubbleService::class.java))
            } else {
                Prefs.setBubbleEnabled(this, true)
                if (Settings.canDrawOverlays(this)) {
                    BubbleService.start(this)
                } else {
                    Toast.makeText(
                        this,
                        "Grant “Display over other apps” first",
                        Toast.LENGTH_LONG
                    ).show()
                    openSafely(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            btnService.postDelayed({ refresh() }, 350L)
        }
    }

    // ------------------------------------------------------------------ bubble look

    private fun wireBubbleLook() {
        seekSize.max = (MAX_SIZE_DP - MIN_SIZE_DP)
        seekSize.setOnSeekBarChangeListener(SimpleSeekListener { progress ->
            Prefs.setBubbleSizeDp(this, MIN_SIZE_DP + progress)
            txtSize.text = sizeLabel()
        })

        seekAlpha.max = 70
        seekAlpha.setOnSeekBarChangeListener(SimpleSeekListener { progress ->
            Prefs.setBubbleAlpha(this, (progress + 30) / 100f)
            txtAlpha.text = alphaLabel()
        })

        seekIdleAlpha.max = 100
        seekIdleAlpha.setOnSeekBarChangeListener(SimpleSeekListener { progress ->
            Prefs.setIdleAlpha(this, progress / 100f)
            txtIdleAlpha.text = idleAlphaLabel()
        })

        chkIdle.setOnCheckedChangeListener { _, checked -> Prefs.setIdleFade(this, checked) }
        chkBoot.setOnCheckedChangeListener { _, checked -> Prefs.setStartOnBoot(this, checked) }
    }

    // ------------------------------------------------------------------ timer

    private fun wireTimer() {
        val presets = mapOf(
            R.id.btnTimer5 to 5,
            R.id.btnTimer10 to 10,
            R.id.btnTimer15 to 15,
            R.id.btnTimer30 to 30,
            R.id.btnTimer60 to 60
        )
        for ((id, minutes) in presets) {
            findViewById<Button>(id).setOnClickListener { startTimer(minutes) }
        }

        findViewById<Button>(R.id.btnTimerCustom).setOnClickListener {
            val minutes = editMinutes.text.toString().toIntOrNull() ?: 0
            if (minutes in 1..600) {
                Prefs.setDefaultTimerMin(this, minutes)
                startTimer(minutes)
            } else {
                Toast.makeText(this, "Enter 1 – 600 minutes", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnTimerCancel).setOnClickListener {
            cancelTimer()
        }
    }

    private fun startTimer(minutes: Int) {
        if (BubbleService.isRunning()) {
            BubbleService.send(this, BubbleService.ACTION_SET_TIMER, minutes)
        } else {
            SleepTimerManager(this).set(minutes)
        }
        Toast.makeText(this, getString(R.string.timer_set, minutes), Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun cancelTimer() {
        if (BubbleService.isRunning()) {
            BubbleService.send(this, BubbleService.ACTION_CANCEL_TIMER)
        } else {
            SleepTimerManager(this).cancel()
        }
        Toast.makeText(this, getString(R.string.timer_off), Toast.LENGTH_SHORT).show()
        refresh()
    }

    // ------------------------------------------------------------------ locking

    private fun wireLocking() {
        radioAdmin.setOnClickListener {
            Prefs.setLockMethod(this, Prefs.LOCK_METHOD_ADMIN)
            refresh()
        }
        radioA11y.setOnClickListener {
            Prefs.setLockMethod(this, Prefs.LOCK_METHOD_ACCESSIBILITY)
            refresh()
        }
        chkZeroBrightness.setOnCheckedChangeListener { _, checked ->
            Prefs.setZeroBrightness(this, checked)
        }
    }

    // ------------------------------------------------------------------ state

    private fun refresh() {
        val running = BubbleService.isRunning()
        btnService.text = if (running) "Stop bubble" else "Start bubble"
        txtService.text = when {
            running -> "Running as a foreground service. Tap the bubble to lock touches, hold it for the menu."
            Settings.canDrawOverlays(this) -> "Not running. Tap “Start bubble”."
            else -> "Waiting for the overlay permission below."
        }

        txtOverlay.text = status(
            Settings.canDrawOverlays(this),
            "Granted — the bubble can be drawn over other apps.",
            "Required. Without it the bubble cannot be shown at all."
        )

        val notificationsOn = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        txtNotifications.text = status(
            notificationsOn,
            "Granted — the ongoing notification (and its Unlock button) is visible.",
            "Optional, but without it the ongoing notification — and its unlock action — stay hidden."
        )

        txtAdmin.text = status(
            Locker.isAdminActive(this),
            "Active — the sleep timer can call lockNow().",
            "Recommended. Used only by the sleep timer and the notification lock action."
        )

        txtAccessibility.text = status(
            Locker.isAccessibilityEnabled(this),
            "Enabled — screen lock action available, and the bubble wakes on any screen touch.",
            "Optional alternative to device admin. Never reads screen content."
        )

        txtBattery.text = getString(R.string.battery_tip)

        suppressSeekCallbacks = true
        seekSize.progress = Prefs.bubbleSizeDp(this) - MIN_SIZE_DP
        seekAlpha.progress = (Prefs.bubbleAlpha(this) * 100f).toInt() - 30
        seekIdleAlpha.progress = (Prefs.idleAlpha(this) * 100f).toInt()
        chkIdle.isChecked = Prefs.idleFade(this)
        chkBoot.isChecked = Prefs.startOnBoot(this)
        chkZeroBrightness.isChecked = Prefs.zeroBrightness(this)
        (if (Prefs.lockMethod(this) == Prefs.LOCK_METHOD_ACCESSIBILITY) radioA11y else radioAdmin).isChecked = true
        editMinutes.setText(getString(R.string.label_custom_minutes, Prefs.defaultTimerMin(this)))
        suppressSeekCallbacks = false

        txtSize.text = sizeLabel()
        txtAlpha.text = alphaLabel()
        txtIdleAlpha.text = idleAlphaLabel()

        val manager = SleepTimerManager(this)
        txtTimer.text = if (manager.isActive()) {
            getString(R.string.label_timer_running, manager.remainingLabel())
        } else {
            "Not running. The timer locks the phone screen (device admin or accessibility action)."
        }
    }

    private fun sizeLabel() = getString(R.string.label_size, Prefs.bubbleSizeDp(this))

    private fun alphaLabel() =
        getString(R.string.label_alpha, (Prefs.bubbleAlpha(this) * 100).toInt())

    private fun idleAlphaLabel() =
        getString(R.string.label_idle_alpha, (Prefs.idleAlpha(this) * 100).toInt())

    private fun status(ok: Boolean, okText: String, missingText: String) =
        if (ok) "✓ $okText" else "✗ $missingText"

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun openSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    private inner class SimpleSeekListener(private val onProgress: (Int) -> Unit) :
        SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (suppressSeekCallbacks) return
            onProgress(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    companion object {
        const val EXTRA_FOCUS_TIMER = "focus_timer"
        private const val MIN_SIZE_DP = 36
        private const val MAX_SIZE_DP = 68
    }
}
