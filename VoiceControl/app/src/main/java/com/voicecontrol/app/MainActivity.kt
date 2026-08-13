package com.voicecontrol.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var appsContainer: LinearLayout
    private lateinit var actionsContainer: LinearLayout
    private lateinit var logText: TextView

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(this, if (granted) "تمام، الإذن اتاخد" else "الإذن مرفوض", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appsContainer = findViewById(R.id.appsContainer)
        actionsContainer = findViewById(R.id.actionsContainer)
        logText = findViewById(R.id.logText)

        setupPermissionButtons()
        setupServiceButtons()
        setupSettings()
        setupAppShortcuts()
        setupCalibratedActions()
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshAppsList()
        refreshActionsList()
        refreshLog()
    }

    // ---------- Permissions ----------

    private fun setupPermissionButtons() {
        findViewById<Button>(R.id.btnMicPermission).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "الإذن متاخد بالفعل", Toast.LENGTH_SHORT).show()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "دور على 'المساعد الصوتي' في القائمة وفعّله", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن متاخد بالفعل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Service control ----------

    private fun setupServiceButtons() {
        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, VoiceListenerService::class.java))
            Toast.makeText(this, "المساعد بدأ الاستماع", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopService(Intent(this, VoiceListenerService::class.java))
        }
    }

    // ---------- Settings ----------

    private fun setupSettings() {
        val wakeWordEdit = findViewById<EditText>(R.id.editWakeWord)
        wakeWordEdit.setText(CommandStore.getWakeWord(this))
        findViewById<Button>(R.id.btnSaveWakeWord).setOnClickListener {
            val word = wakeWordEdit.text.toString().trim()
            if (word.isNotEmpty()) {
                CommandStore.setWakeWord(this, word)
                Toast.makeText(this, "اتحفظت", Toast.LENGTH_SHORT).show()
            }
        }

        val confirmSwitch = findViewById<Switch>(R.id.switchVoiceConfirm)
        confirmSwitch.isChecked = CommandStore.isVoiceConfirmEnabled(this)
        confirmSwitch.setOnCheckedChangeListener { _, checked ->
            CommandStore.setVoiceConfirmEnabled(this, checked)
        }

        val autoDrivingSwitch = findViewById<Switch>(R.id.switchAutoDriving)
        autoDrivingSwitch.isChecked = CommandStore.isAutoDrivingModeEnabled(this)
        autoDrivingSwitch.setOnCheckedChangeListener { _, checked ->
            CommandStore.setAutoDrivingModeEnabled(this, checked)
        }
    }

    // ---------- App shortcuts ----------

    private fun setupAppShortcuts() {
        refreshAppsList()
        findViewById<Button>(R.id.btnAddApp).setOnClickListener {
            val label = findViewById<EditText>(R.id.editAppLabel).text.toString().trim()
            val pkg = findViewById<EditText>(R.id.editAppPackage).text.toString().trim()
            if (label.isEmpty() || pkg.isEmpty()) {
                Toast.makeText(this, "اكتب الاسم واسم الحزمة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val apps = CommandStore.getApps(this).toMutableList()
            apps.add(AppShortcut(label, pkg))
            CommandStore.saveApps(this, apps)
            findViewById<EditText>(R.id.editAppLabel).text.clear()
            findViewById<EditText>(R.id.editAppPackage).text.clear()
            refreshAppsList()
        }
    }

    private fun refreshAppsList() {
        appsContainer.removeAllViews()
        val apps = CommandStore.getApps(this)
        for (app in apps) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "${app.label} (${app.packageName})"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val deleteBtn = Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    val updated = CommandStore.getApps(this@MainActivity).filterNot { it.label == app.label && it.packageName == app.packageName }
                    CommandStore.saveApps(this@MainActivity, updated)
                    refreshAppsList()
                }
            }
            row.addView(label)
            row.addView(deleteBtn)
            appsContainer.addView(row)
        }
    }

    // ---------- Calibrated actions ----------

    private fun setupCalibratedActions() {
        refreshActionsList()
        findViewById<Button>(R.id.btnRecordAction).setOnClickListener {
            val label = findViewById<EditText>(R.id.editActionLabel).text.toString().trim()
            val pkg = findViewById<EditText>(R.id.editActionPackage).text.toString().trim()
            val type = if (findViewById<RadioButton>(R.id.radioSwipe).isChecked) "SWIPE" else "TAP"

            if (label.isEmpty() || pkg.isEmpty()) {
                Toast.makeText(this, "اكتب الأمر واسم الحزمة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "لازم تفعّل صلاحية الظهور فوق التطبيقات الأول", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val actionId = "action_" + System.currentTimeMillis()
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                Toast.makeText(this, "التطبيق ده مش متثبت على الجهاز", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(launchIntent)

            // Give the app a moment to open, then launch the calibration overlay on top of it.
            findViewById<Button>(R.id.btnRecordAction).postDelayed({
                val calIntent = Intent(this, CalibrationActivity::class.java).apply {
                    putExtra("actionId", actionId)
                    putExtra("label", label)
                    putExtra("appPackage", pkg)
                    putExtra("type", type)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(calIntent)
            }, 1500)
        }
    }

    private fun refreshActionsList() {
        actionsContainer.removeAllViews()
        val actions = CommandStore.getActions(this)
        for (action in actions) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "${action.label} [${action.type}] — ${action.appPackage}"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val deleteBtn = Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    val updated = CommandStore.getActions(this@MainActivity).filterNot { it.id == action.id }
                    CommandStore.saveActions(this@MainActivity, updated)
                    refreshActionsList()
                }
            }
            row.addView(label)
            row.addView(deleteBtn)
            actionsContainer.addView(row)
        }
    }

    // ---------- Log ----------

    private fun refreshLog() {
        val log = CommandStore.getLog(this)
        logText.text = if (log.isEmpty()) "لسه مفيش أوامر" else
            TextUtils.join("\n", log.take(15).map { "${it.heardText} → ${it.result}" })
    }
}
