package com.korit.ytwhatssong

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setMargins

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var serviceToggle: Switch
    private lateinit var screenOnToggle: Switch
    private lateinit var resumeAnnounceToggle: Switch
    private lateinit var smartParsingToggle: Switch
    private lateinit var useItunesApiToggle: Switch
    private lateinit var useAiApiToggle: Switch
    private lateinit var aiApiKeyButton: Button
    private lateinit var selectAppsButton: Button
    private lateinit var ignoreKeywordsButton: Button
    private lateinit var batteryOptimizationButton: Button
    private lateinit var appInfoButton: Button
    private lateinit var voiceSelectionButton: Button
    private lateinit var googleVoiceToggle: Switch
    private lateinit var djModeToggle: Switch
    private lateinit var audioDuckingToggle: Switch
    private lateinit var speedSeekBar: SeekBar
    private lateinit var volumeSeekBar: SeekBar
    private var ttsForSelection: android.speech.tts.TextToSpeech? = null
    private var ttsForSelectionReady = false

    companion object {
        private const val TAG = "KOG_MainActivity"
        const val PREFS_NAME = "WhatssongPrefs"
        const val KEY_SERVICE_ENABLED = "serviceEnabled" // Use this to track user's intent
        const val KEY_ANNOUNCE_SCREEN_ON = "announceWhenScreenOn"
        const val KEY_ANNOUNCE_ON_RESUME = "announceOnResume"
        const val KEY_SMART_TITLE_PARSING = "smartTitleParsing"
        const val KEY_USE_ITUNES_API = "useItunesApi"
        const val KEY_USE_AI_API = "useAiApi"
        const val KEY_AI_API_KEY = "aiApiKey"
        const val KEY_MONITORED_APPS = "monitoredApps"
        const val KEY_IGNORE_KEYWORDS = "ignoreKeywords"
        const val DEFAULT_IGNORE_KEYWORDS = "直播,精華,全集,合集"
        const val KEY_DJ_MODE = "djMode"
        const val KEY_GOOGLE_VOICE = "googleVoice"
        const val KEY_AUDIO_DUCKING = "audioDucking"
        const val KEY_SELECTED_VOICE_NAME = "selectedVoiceName"
        const val KEY_TTS_SPEED = "ttsSpeed"
        const val KEY_TTS_VOLUME = "ttsVolume"
    }

    // [New] Real-time UI Sync Listener
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_SERVICE_ENABLED) {
            val isEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
            runOnUiThread {
                if (serviceToggle.isChecked != isEnabled) {
                     serviceToggle.isChecked = isEnabled
                     updateUiState(isServiceRunning = isEnabled)
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "POST_NOTIFICATIONS permission granted.")
            Toast.makeText(this, "通知權限已授予", Toast.LENGTH_SHORT).show()
            checkPermissionsAndState()
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
            Toast.makeText(this, "缺少通知權限，前景服務將無法運作", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity created.")

        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Root ScrollView for small screens
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        // Main Container
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5")) // Light Gray Background
        }
        scrollView.addView(mainLayout)

        // Title
        val titleView = TextView(this).apply {
            text = "What's this song"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        mainLayout.addView(titleView)

        // --- Section 1: Service Status ---
        val statusSection = createSectionLayout()
        addSectionTitle(statusSection, "服務狀態")
        
        statusText = TextView(this).apply {
            text = "Status: Checking..."
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
            setTextColor(android.graphics.Color.DKGRAY)
        }
        statusSection.addView(statusText)

        serviceToggle = Switch(this).apply {
            text = "啟用監控服務"
            textSize = 18f
            setTextColor(android.graphics.Color.BLACK)
        }
        statusSection.addView(serviceToggle)
        mainLayout.addView(statusSection)

        // --- Section 2: Recognition Settings ---
        val parsingSection = createSectionLayout()
        addSectionTitle(parsingSection, "解析設定")

        smartParsingToggle = Switch(this).apply {
            text = "本地標題簡化"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_SMART_TITLE_PARSING, false)
            setPadding(0, 24, 0, 24)
        }
        parsingSection.addView(smartParsingToggle)
        
        addDescription(parsingSection, "移除 (Official MV) 等贅字，只保留歌手與歌名。")

        useItunesApiToggle = Switch(this).apply {
            text = "iTunes API 優化"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_USE_ITUNES_API, true)
             setPadding(0, 24, 0, 24)
        }
        parsingSection.addView(useItunesApiToggle)

        addDescription(parsingSection, "連線至 Apple 資料庫查詢最正確的歌曲資訊。")

        useAiApiToggle = Switch(this).apply {
            text = "Gemini AI (Gemma) 優化"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_USE_AI_API, false)
             setPadding(0, 24, 0, 24)
        }
        parsingSection.addView(useAiApiToggle)

        addDescription(parsingSection, "先查 iTunes（速度快），查無結果才改問 AI 理解整段標題、忽略劇名/集數/MV品牌等雜訊，兩者都失敗才退回本地解析。三選一：本地標題簡化／iTunes／AI 只會有一個生效。")

        aiApiKeyButton = Button(this).apply {
            text = "設定 Google AI API Key"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 8) }
        }
        parsingSection.addView(aiApiKeyButton)
        mainLayout.addView(parsingSection)

        // --- Section 3: Monitoring & Actions ---
        val actionSection = createSectionLayout()
        addSectionTitle(actionSection, "監控與行為")

        screenOnToggle = Switch(this).apply {
            text = "螢幕開啟時也播報"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_ANNOUNCE_SCREEN_ON, true)
             setPadding(0, 24, 0, 24)
        }
        actionSection.addView(screenOnToggle)

        resumeAnnounceToggle = Switch(this).apply {
            text = "暫停恢復播放時也播報"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_ANNOUNCE_ON_RESUME, true)
            setPadding(0, 24, 0, 24)
        }
        actionSection.addView(resumeAnnounceToggle)
        addDescription(actionSection, "同一首歌從暫停或停止恢復播放時，重新播報一次歌名與歌手。")

        selectAppsButton = Button(this).apply {
            text = "選擇監控應用程式"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 32, 0, 0) }
        }
        actionSection.addView(selectAppsButton)

        ignoreKeywordsButton = Button(this).apply {
            text = "編輯忽略清單"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 0) }
        }
        actionSection.addView(ignoreKeywordsButton)
        addDescription(actionSection, "命中清單中關鍵字的標題（例如直播、精華、合集）將不會播報，多組關鍵字請用逗號分隔。")
        mainLayout.addView(actionSection)

        // --- Section 4: System ---
        val systemSection = createSectionLayout()
        addSectionTitle(systemSection, "系統設定")

        batteryOptimizationButton = Button(this).apply {
            text = "解決背景中斷問題 (電池設定)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        systemSection.addView(batteryOptimizationButton)

        appInfoButton = Button(this).apply {
            text = "開啟應用程式資訊"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 0) }
        }
        systemSection.addView(appInfoButton)
        mainLayout.addView(systemSection)

        // --- Section New: DJ & Voice Settings ---
        val djSection = createSectionLayout()
        addSectionTitle(djSection, "DJ 與語音設定")

        djModeToggle = Switch(this).apply {
            text = "DJ 報幕模式"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_DJ_MODE, true)
            setPadding(0, 24, 0, 24)
            setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.edit().putBoolean(KEY_DJ_MODE, isChecked).apply()
            }
        }
        djSection.addView(djModeToggle)
        addDescription(djSection, "使用類似電台 DJ 的開場白介紹歌曲。")

        googleVoiceToggle = Switch(this).apply {
            text = "Google 語音優化 (預設女聲)"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_GOOGLE_VOICE, true)
            setPadding(0, 24, 0, 24)
            setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.edit().putBoolean(KEY_GOOGLE_VOICE, isChecked).apply()
                voiceSelectionButton.isEnabled = !isChecked // Disable manual selection if auto-optimization is on? Or maybe allow override?
                // Let's allow override, but maybe show status. 
                // Actually, if "Google Voice Optimization" is ON, we might want to hide/disable manual selection 
                // OR make manual selection override it.
                // User requirement: "Default is gentle female voice".
                // Strategy: "Google Voice Optimization" effectively means "Auto-pick best female voice".
                // If user uses manual selection, they probably want full control.
                // Let's keep them independent but maybe update text.
            }
        }
        djSection.addView(googleVoiceToggle)
        addDescription(djSection, "優先自動選擇 Google 高品質中文女聲。若要手動選擇，請關閉此選項或直接下方選擇。")

        voiceSelectionButton = Button(this).apply {
            text = "選擇語音 (載入中...)"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 32) }
        }
        djSection.addView(voiceSelectionButton)

        audioDuckingToggle = Switch(this).apply {
            text = "背景音樂不中斷"
            textSize = 16f
            isChecked = sharedPrefs.getBoolean(KEY_AUDIO_DUCKING, true)
            setPadding(0, 24, 0, 24)
            setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.edit().putBoolean(KEY_AUDIO_DUCKING, isChecked).apply()
            }
        }
        djSection.addView(audioDuckingToggle)
        addDescription(djSection, "播報時降低音樂音量，而非完全暫停。")

        // TTS Speed Slider
        val currentSpeed = sharedPrefs.getFloat(KEY_TTS_SPEED, 1.2f)
        val speedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }
        val speedLabel = TextView(this).apply {
            text = "語速: ${String.format("%.1f", currentSpeed)}x"
            textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
        }
        speedLayout.addView(speedLabel)

        speedSeekBar = SeekBar(this).apply {
            max = 150 // 0.5 to 2.0 -> Range 1.5. Steps 0.1? Let's do 0.01 precision internally but 0.1 UI? 
            // 0 -> 0.5, 70 -> 1.2, 150 -> 2.0
            progress = ((currentSpeed - 0.5f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = 0.5f + (progress / 100f)
                    speedLabel.text = "語速: ${String.format("%.1f", speed)}x"
                    sharedPrefs.edit().putFloat(KEY_TTS_SPEED, speed).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        speedLayout.addView(speedSeekBar)
        djSection.addView(speedLayout)

        // TTS Volume Slider
        val currentVolume = sharedPrefs.getFloat(KEY_TTS_VOLUME, 1.0f)
        val volumeLayout = LinearLayout(this).apply {
             orientation = LinearLayout.VERTICAL
             setPadding(0, 0, 0, 24)
        }
        val volumeLabel = TextView(this).apply {
            text = "音量: ${(currentVolume * 100).toInt()}%"
             textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
        }
        volumeLayout.addView(volumeLabel)

        volumeSeekBar = SeekBar(this).apply {
            max = 100
            progress = (currentVolume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val volume = progress / 100f
                    volumeLabel.text = "音量: $progress%"
                    sharedPrefs.edit().putFloat(KEY_TTS_VOLUME, volume).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        volumeLayout.addView(volumeSeekBar)
        djSection.addView(volumeLayout)

        mainLayout.addView(djSection)

        // --- Section 5: About ---
        val aboutSection = createSectionLayout()
        addSectionTitle(aboutSection, "關於")

        val aboutText = TextView(this).apply {
            val appVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) { "1.0" }
            
            text = "版本: $appVersion\n作者: Korit\n信箱: Korit1201@gmail.com"
            textSize = 14f
            setTextColor(android.graphics.Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.5f) // 增加行距
        }
        aboutSection.addView(aboutText)
        mainLayout.addView(aboutSection)

        setContentView(scrollView)

        setupListeners()
        
        // Init TTS for selection
        ttsForSelection = android.speech.tts.TextToSpeech(this) { status ->
             if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                 ttsForSelectionReady = true
                 runOnUiThread {
                     voiceSelectionButton.text = "選擇語音"
                     val isServiceEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SERVICE_ENABLED, true)
                     updateUiState(isServiceRunning = isServiceEnabled)
                 }
             } else {
                 Log.e(TAG, "TTS Init failed for selection")
                 runOnUiThread {
                     voiceSelectionButton.text = "語音引擎初始化失敗"
                 }
             }
        }
    }

    override fun onDestroy() {
        ttsForSelection?.shutdown()
        super.onDestroy()
    }

    // Helper functions for UI
    private fun createSectionLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 24f
            }
            elevation = 8f
            setPadding(48, 48, 48, 48)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 48)
            }
        }
    }

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        val view = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#1976D2")) // Android Blue
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        parent.addView(view)
    }

    private fun addDescription(parent: LinearLayout, desc: String) {
        val view = TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 0, 0, 24)
        }
        parent.addView(view)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Checking permissions and state.")
        checkPermissionsAndState()
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStop() {
        super.onStop()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun setupListeners() {
        serviceToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !areAllPermissionsGranted()) {
                serviceToggle.isChecked = false
                Toast.makeText(this, "請先授予所有必要權限", Toast.LENGTH_SHORT).show()
                checkPermissionsAndState()
                return@setOnCheckedChangeListener
            }
            Log.d(TAG, "Service toggle changed by user: isChecked = $isChecked")
            setServiceState(isChecked)
            updateUiState(isServiceRunning = isChecked)
        }

        screenOnToggle.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANNOUNCE_SCREEN_ON, isChecked).apply()
        }

        resumeAnnounceToggle.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANNOUNCE_ON_RESUME, isChecked).apply()
        }

        // 本地標題簡化 / iTunes API / Gemini AI 三選一，互斥
        smartParsingToggle.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SMART_TITLE_PARSING, isChecked).apply()
            if (isChecked) {
                if (useItunesApiToggle.isChecked) useItunesApiToggle.isChecked = false
                if (useAiApiToggle.isChecked) useAiApiToggle.isChecked = false
            }
        }

        useItunesApiToggle.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_USE_ITUNES_API, isChecked).apply()
            if (isChecked) {
                if (smartParsingToggle.isChecked) smartParsingToggle.isChecked = false
                if (useAiApiToggle.isChecked) useAiApiToggle.isChecked = false
            }
        }

        useAiApiToggle.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_USE_AI_API, isChecked).apply()
            if (isChecked) {
                if (smartParsingToggle.isChecked) smartParsingToggle.isChecked = false
                if (useItunesApiToggle.isChecked) useItunesApiToggle.isChecked = false
                val hasKey = !getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_AI_API_KEY, "").isNullOrBlank()
                if (!hasKey) {
                    Toast.makeText(this, "尚未設定 Google AI API Key，iTunes 查無結果時 AI 備援會直接失敗", Toast.LENGTH_LONG).show()
                }
            }
        }

        aiApiKeyButton.setOnClickListener {
            showAiApiKeyDialog()
        }

        selectAppsButton.setOnClickListener {
            // Start with only launcher apps (cleaner list)
            showAppSelectionDialog(showAll = false)
        }

        ignoreKeywordsButton.setOnClickListener {
            showIgnoreKeywordsDialog()
        }

        voiceSelectionButton.setOnClickListener {
            showVoiceSelectionDialog()
        }

        batteryOptimizationButton.setOnClickListener { showBatteryOptimizationDialog() }
        
        appInfoButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }
    
    private fun areAllPermissionsGranted(): Boolean {
        val listenerEnabled = isNotificationServiceEnabled()
        val postEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return listenerEnabled && postEnabled
    }

    private fun checkPermissionsAndState() {
        if (!isNotificationServiceEnabled()) {
            showPermissionDialog("需要「通知存取」權限", "為了偵測歌曲變化，請在接下來的畫面中，給予 App「通知存取」的權限。", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            // Ensure component is enabled so it shows up in the list
            val componentName = ComponentName(this, MediaMonitorService::class.java)
            packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            updateUiState(statusMsg = "缺少「通知存取」權限")
            return
        }

        // Always ensure the component is enabled when we check state
        val componentName = ComponentName(this, MediaMonitorService::class.java)
        packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                 showPermissionDialog("需要「傳送通知」權限", "為了讓服務能在背景穩定運作並顯示狀態，App 需要「傳送通知」的權限。", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            updateUiState(statusMsg = "缺少「傳送通知」權限")
            return
        }

        Log.i(TAG, "checkPermissionsAndState: All required permissions are GRANTED.")
        val isServiceEnabledByUser = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SERVICE_ENABLED, true)
        updateUiState(isServiceRunning = isServiceEnabledByUser)
    }
    
    // **THE FIX**: Added a default value to isServiceRunning
    private fun updateUiState(isServiceRunning: Boolean = false, statusMsg: String? = null) {
        if (statusMsg != null) {
            statusText.text = statusMsg
            serviceToggle.isEnabled = false
            screenOnToggle.isEnabled = false
            resumeAnnounceToggle.isEnabled = false
            smartParsingToggle.isEnabled = false
            useItunesApiToggle.isEnabled = false
            useAiApiToggle.isEnabled = false
            aiApiKeyButton.isEnabled = false
            selectAppsButton.isEnabled = false
            ignoreKeywordsButton.isEnabled = false
            djModeToggle.isEnabled = false
            googleVoiceToggle.isEnabled = false
            voiceSelectionButton.isEnabled = false
            audioDuckingToggle.isEnabled = false
            speedSeekBar.isEnabled = false
            volumeSeekBar.isEnabled = false
        } else {
            statusText.text = if (isServiceRunning) "服務正在運作中" else "服務已停用"
            serviceToggle.isEnabled = true
            serviceToggle.isChecked = isServiceRunning
            screenOnToggle.isEnabled = isServiceRunning
            resumeAnnounceToggle.isEnabled = isServiceRunning
            smartParsingToggle.isEnabled = isServiceRunning
            useItunesApiToggle.isEnabled = isServiceRunning
            useAiApiToggle.isEnabled = isServiceRunning
            aiApiKeyButton.isEnabled = isServiceRunning
            selectAppsButton.isEnabled = isServiceRunning
            ignoreKeywordsButton.isEnabled = isServiceRunning
            djModeToggle.isEnabled = isServiceRunning
            googleVoiceToggle.isEnabled = isServiceRunning
            // 語音選擇還額外依賴「選擇用 TTS 引擎」是否已初始化完成
            voiceSelectionButton.isEnabled = isServiceRunning && ttsForSelectionReady
            audioDuckingToggle.isEnabled = isServiceRunning
            speedSeekBar.isEnabled = isServiceRunning
            volumeSeekBar.isEnabled = isServiceRunning
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
    }

    private fun showPermissionDialog(title: String, message: String, intent: Intent) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("前往設定") { _, _ -> startActivity(intent) }.setCancelable(false).show()
    }
    
    private fun setServiceState(enable: Boolean) {
        // Save the user's intent
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SERVICE_ENABLED, enable).apply()
        Log.d(TAG, "setServiceState: User intent to enable service set to $enable")

        val serviceIntent = Intent(this, MediaMonitorService::class.java)
        if (enable) {
            Log.i(TAG, "Starting service...")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                // Ensure component is enabled
                val componentName = ComponentName(this, MediaMonitorService::class.java)
                packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

                // explicit rebind request
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(componentName)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting service", e)
            }
        } else {
            Log.i(TAG, "Stopping service via Intent...")
            val stopIntent = Intent(this, MediaMonitorService::class.java).apply {
                action = MediaMonitorService.ACTION_STOP_SERVICE
            }
            try {
                // Using startService to deliver the intent to onStartCommand even if service is running
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                     startService(stopIntent)
                } else {
                     startService(stopIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stop intent: ${e.message}")
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        val intents = mutableListOf<Intent>()
        // Add specific vendor intents if needed, but start with generic ones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            intents.add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        }
        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))

        AlertDialog.Builder(this)
            .setTitle("背景運作設定指引")
            .setMessage("為了讓 App 能在背景穩定運作，建議將本 App 加入電池優化白名單：\n\n1. 在接下來的畫面中，找到「什麼歌」。\n2. 找到「電池」或「省電」相關選項。\n3. 將設定從「智慧控制」或「最佳化」改為「不受限制」。")
            .setPositiveButton("前往設定") { _, _ ->
                var started = false
                for (intent in intents) {
                    if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                        try {
                            startActivity(intent)
                            started = true
                            break
                        } catch (e: Exception) { /* Continue */ }
                    }
                }
                if (!started) {
                    Toast.makeText(this, "無法自動開啟任何設定頁面，請手動前往", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showVoiceSelectionDialog() {
        val tts = ttsForSelection ?: return
        val voices = try { tts.voices } catch (e: Exception) { null }
        
        if (voices.isNullOrEmpty()) {
            Toast.makeText(this, "找不到可用的語音", Toast.LENGTH_SHORT).show()
            return
        }

        // Filter valid voices (Chinese)
        val validVoices = voices.filter { 
            it.locale.language == java.util.Locale.TRADITIONAL_CHINESE.language ||
            it.locale.toLanguageTag().contains("zh-TW", ignoreCase = true) ||
            it.locale.toLanguageTag().contains("zh-HK", ignoreCase = true)
        }.sortedByDescending { 
            // Sort: Google Network > Google Local > Others
            var score = 0
            if (it.name.contains("google", ignoreCase = true)) score += 5
            if (it.name.contains("network", ignoreCase = true)) score += 5 
            if (it.name.contains("female", ignoreCase = true)) score += 3
            score
        }

        if (validVoices.isEmpty()) {
             Toast.makeText(this, "找不到繁體中文語音", Toast.LENGTH_SHORT).show()
             return
        }

        val voiceNames = validVoices.map { voice ->
            var label = voice.name
            if (label.contains("network", ignoreCase = true)) label += " (高品質)"
            label
        }.toTypedArray()

        val currentSelectedName = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SELECTED_VOICE_NAME, "")
        var selectedIndex = validVoices.indexOfFirst { it.name == currentSelectedName }
        if (selectedIndex == -1) selectedIndex = 0

        AlertDialog.Builder(this)
            .setTitle("選擇語音")
            .setSingleChoiceItems(voiceNames, selectedIndex) { dialog, which ->
                val selectedVoice = validVoices[which]
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SELECTED_VOICE_NAME, selectedVoice.name)
                    .putBoolean(KEY_GOOGLE_VOICE, false)
                    .apply()
                
                googleVoiceToggle.isChecked = false
                voiceSelectionButton.isEnabled = true
                
                Toast.makeText(this, "已選擇: ${voiceNames[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAiApiKeyDialog() {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = sharedPrefs.getString(KEY_AI_API_KEY, "")

        val input = android.widget.EditText(this).apply {
            setText(current)
            hint = "貼上你的 Google AI API Key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("設定 Google AI API Key")
            .setMessage("到 Google AI Studio (aistudio.google.com) 建立並取得 API Key 後貼在這裡，僅儲存在本機裝置上。")
            .setView(container)
            .setPositiveButton("儲存") { _, _ ->
                sharedPrefs.edit().putString(KEY_AI_API_KEY, input.text.toString().trim()).apply()
                Toast.makeText(this, "已儲存 API Key", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showIgnoreKeywordsDialog() {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = sharedPrefs.getString(KEY_IGNORE_KEYWORDS, DEFAULT_IGNORE_KEYWORDS)

        val input = android.widget.EditText(this).apply {
            setText(current)
            hint = "例如：直播,精華,全集,合集"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("編輯忽略清單")
            .setMessage("標題命中以下任一關鍵字時，將不會播報（不分大小寫），請用逗號分隔多組關鍵字。")
            .setView(container)
            .setPositiveButton("儲存") { _, _ ->
                sharedPrefs.edit().putString(KEY_IGNORE_KEYWORDS, input.text.toString()).apply()
                Toast.makeText(this, "已儲存忽略清單", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAppSelectionDialog(showAll: Boolean) {
        // 在背景執行以免載入 App 列表時凍結 UI
        Thread {
            val packageManager = packageManager
            val installedApps = if (showAll) {
                 // 顯示所有 App (使用者要求)
                 packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            } else {
                 // 僅顯示可啟動 (Launcher) 的 App (預設)
                 val intent = Intent(Intent.ACTION_MAIN, null)
                 intent.addCategory(Intent.CATEGORY_LAUNCHER)
                 val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                 // 將 ResolveInfo 轉換為類似 PackageInfo 的結構以維持一致性
                 resolveInfos.mapNotNull { 
                     try {
                         packageManager.getPackageInfo(it.activityInfo.packageName, 0)
                     } catch (e: Exception) { null }
                 }
            }
            
            // 過濾重複項與系統核心 App (如果顯示全部)
            // 但若是 Launcher 模式，我們信任 Intent 的結果
            // 依照標籤 (Label) 排序
            val displayedApps = installedApps.distinctBy { it.packageName }.sortedBy { 
                it.applicationInfo.loadLabel(packageManager).toString() 
            }

            val appNames = displayedApps.map { it.applicationInfo.loadLabel(packageManager).toString() }.toTypedArray()
            val packageNames = displayedApps.map { it.packageName }.toTypedArray()
            
            val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedAppsSet = sharedPrefs.getStringSet(KEY_MONITORED_APPS, null)
            
            // Default checked apps logic
            val checkedItems = BooleanArray(displayedApps.size) { index ->
                if (savedAppsSet == null) {
                    // Default checks: YouTube, ReVanced, YouTube Music, Spotify
                    val pkg = packageNames[index]
                    pkg == "com.google.android.youtube" || 
                    pkg == "app.revanced.android.youtube" ||
                    pkg == "com.google.android.apps.youtube.music" ||
                    pkg == "com.spotify.music"
                } else {
                    savedAppsSet.contains(packageNames[index])
                }
            }

            runOnUiThread {
                val builder = AlertDialog.Builder(this)
                    .setTitle(if (showAll) "所有應用程式" else "選擇應用程式")
                    .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                        checkedItems[which] = isChecked
                    }
                    .setPositiveButton("儲存") { _, _ ->
                        // [修正] 如果 savedAppsSet 為 null，必須先載入預設清單，
                        // 否則那些預設但未顯示在此列表的 App (不可見的預設值) 會被遺失。
                        val defaultApps = setOf(
                            "com.google.android.youtube",
                            "app.revanced.android.youtube",
                            "com.google.android.apps.youtube.music",
                            "com.spotify.music"
                        )
                        val selectedPackages = if (savedAppsSet != null) HashSet(savedAppsSet) else HashSet(defaultApps)
                        
                        // 根據目前的顯示狀態更新集合
                         for (i in checkedItems.indices) {
                            if (checkedItems[i]) {
                                selectedPackages.add(packageNames[i])
                            } else {
                                selectedPackages.remove(packageNames[i])
                            }
                        }
                        
                        sharedPrefs.edit().putStringSet(KEY_MONITORED_APPS, selectedPackages).apply()
                        Toast.makeText(this, "已儲存監控列表", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)

                if (!showAll) {
                    builder.setNeutralButton("找不到？顯示全部") { _, _ ->
                        showAppSelectionDialog(showAll = true)
                    }
                }
                
                builder.show()
            }
        }.start()
    }
}
