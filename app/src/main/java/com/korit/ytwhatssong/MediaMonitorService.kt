package com.korit.ytwhatssong

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Display
import com.korit.ytwhatssong.R
import java.util.UUID
import java.util.Locale
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes

/**
 * 媒體監控服務
 *
 * 此服務負責在背景監聽系統通知，當偵測到目標應用程式（如 YouTube, ReVanced）的媒體播放通知時，
 * 透過文字轉語音 (TTS) 播報歌曲標題。
 *
 * 主要功能：
 * 1. 監聽通知欄的媒體控制通知。
 * 2. 過濾及解析歌曲標題。
 * 3. 暫停播放 -> 語音播報 -> 恢復播放。
 * 4. 處理服務的生命週期與前景通知 (Foreground Notification)。
 */
class MediaMonitorService : NotificationListenerService(), TextToSpeech.OnInitListener {

    // 文字轉語音引擎
    private var tts: TextToSpeech? = null
    // TTS 是否已初始化完成
    private var isTtsReady = false
    // 記錄上一首處理過的標題，避免重複播報
    private var lastProcessedTitle = ""
    // 記錄上一首處理的時間
    private var lastProcessedTime = 0L
    // TTS 播放佇列 (當 TTS 尚未初始化完成時暫存)
    private val ttsQueue = java.util.LinkedList<Pair<String, MediaController>>() 
    // 主執行緒 Handler，用於操作 UI 或延遲任務
    private val mainHandler = Handler(Looper.getMainLooper())
    // 用於追蹤正在播報的 Utterance ID 對應的 MediaController，以便播報結束後恢復播放
    private val controllerMap = mutableMapOf<String, MediaController>()
    // 應用程式偏好設定
    private lateinit var sharedPrefs: SharedPreferences
    // 電源鎖，防止在播報過程中 CPU 休眠
    private var wakeLock: PowerManager.WakeLock? = null
    // 執行緒池，用於執行網路請求 (iTunes API)
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var audioManager: AudioManager
    
    // DJ Templates
    private val djTemplates = listOf(
        "接下來這首，來自 #S 的經典作品，#N。",
        "讓 #S 的聲音陪你度過這個時刻，送上 #N。",
        "轉換一下心情，聽聽 #S 帶來的 #N。",
        "深夜了，適合讓 #S 的 #N 沉澱一下思緒。",
        "耳朵借給我，這是 #S 的 #N。",
        "沒什麼好說的，聽就對了，#S 演唱的 #N。",
        "來自 #S 的 #N，收錄在 #D 這張專輯裡。",
        "讓音樂連結我們，送上 #S 的 #N。",
        "不管過多久，#S 的 #N 總是能打動人心。",
        "這是屬於 #S 的時刻，請欣賞 #N。",
        "下一首，讓我們沉浸在 #S 的 #N 之中。",
        "推薦這首 #S 的 #N，出自經典專輯 #D。",
        "心情不好的時候，聽聽 #S 的 #N 最療癒。",
        "閉上眼睛，感受 #S 在 #N 裡的情感。",
        "這是 #S 很有代表性的一首歌，#N。",
        "今天的私房推薦，#S 的 #N，收錄在 #D 專輯。",
        "這旋律一出來你就知道是誰，#S 的 #N。",
        "來自 #Y 年的聲音，#S 的 #N。",
        "#S 的 #D 專輯中，這首 #N 絕對不能錯過。",
        "讓 #S 的 #N 帶走你的煩惱。",
        "節奏輕快起來，這是 #S 的 #N。",
        "很久沒聽這首了吧？#S 的 #N。",
        "獻給所有有故事的人，#S 的 #N。",
        "#S 的情歌總是很對味，這首 #N 也不例外。",
        "讓我們一起重溫 #D 專輯裡的這首好歌，#S 的 #N。",
        "#Y 年，我們一起聽過的 #S 的 #N。",
        "馬上為您送上，#S 的 #N。",
        "這聲音太迷人了，#S 的 #N。",
        "來自 #D 專輯，#S 的 #N。",
        "如果你還沒聽過 #S 的 #N，現在仔細聽。",
        "#Y 年發行的好歌，#S 的 #N。",
        "這是 #S 的 #N，希望能溫暖你的耳朵。",
        "經典永遠不嫌老，#S 的 #N。",
        "在這個城市的一角，我們聽 #S 的 #N。",
        "#S 在 #Y 年留下的美好回憶，#N。",
        "收錄在 #D 裡的隱藏好歌，#S 的 #N。",
        "準備好了嗎？#S 要帶來這首 #N。",
        "這張 #D 專輯真的很強，特別是這首 #S 的 #N。",
        "繼續來聽，這首不錯喔，#S 的 #N。",
        "最後推薦這首 #Y 年的作品，#S 的 #N，希望你喜歡。"
    )

    companion object {
        private const val TAG = "KOG_MediaMonitorService"
        // 前景服務通知 ID
        private const val FOREGROUND_SERVICE_ID = 1
        // 通知頻道 ID
        private const val NOTIFICATION_CHANNEL_ID = "media_monitor_channel"
        // 停止服務的 Action
        const val ACTION_STOP_SERVICE = "com.korit.ytwhatssong.ACTION_STOP_SERVICE"
        // 目標監控套件：官方 YouTube
        private const val TARGET_PKG_YOUTUBE = "com.google.android.youtube"
        // 目標監控套件：ReVanced
        private const val TARGET_PKG_REVANCED = "app.revanced.android.youtube"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: 服務正在建立。")
        sharedPrefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // [修正4] 重開機不自動啟動：檢查服務開關狀態
        // 如果使用者之前關閉了服務，這裡就不應該啟動前景通知與 TTS
        val isServiceEnabled = sharedPrefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, false)
        if (!isServiceEnabled) {
            Log.i(TAG, "onCreate: 服務被設為停用，不進行初始化。")
            return
        }

        // **修正點**: 在 onCreate 中立即啟動前景服務通知。
        // 這是防止服務被系統殺死的關鍵步驟。
        updateNotification("正在背景監控媒體播放")

        // 初始化電源鎖 (WakeLock)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Whatssong::WakelockTag")
        wakeLock?.setReferenceCounted(false) // 手動管理釋放

        // 初始化文字轉語音引擎
        tts = TextToSpeech(this, this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: 服務收到指令，Action: ${intent?.action}")

        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.w(TAG, "onStartCommand: 收到停止 Action，正在停止服務。")

            // [修正] 更新 SharedPreferences，讓 MainActivity 知道服務已關閉
            sharedPrefs.edit().putBoolean(MainActivity.KEY_SERVICE_ENABLED, false).apply()
            
            // 1. 立即移除通知並停止前景服務狀態
            stopForeground(true)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(FOREGROUND_SERVICE_ID)
            
            // 2. 顯式請求解除綁定通知監聽器 (僅 Android N 以上)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestUnbind()
                     Log.i(TAG, "onStartCommand: 已請求 Unbind。")
                } catch (e: Exception) {
                    Log.e(TAG, "onStartCommand: 請求 Unbind 失敗。", e)
                }
            }

            // 3. 停止服務本身
            stopSelf() 
            return START_NOT_STICKY
        }

        // 服務在 onCreate 時已經啟動了前景通知。
        // 但如果服務被系統重啟，我們需要再次確認前景狀態。
        updateNotification("正在背景監控媒體播放")

        // 強制請求重新綁定通知監聽器 (解決服務意外重啟後失效的問題)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
             try {
                 val componentName = ComponentName(this, MediaMonitorService::class.java)
                 NotificationListenerService.requestRebind(componentName)
                 Log.i(TAG, "onStartCommand: 已請求 Rebind。")
             } catch (e: Exception) { /* 忽略錯誤 */ }
        }
        
        return START_REDELIVER_INTENT
    }

    /**
     * 更新前景服務通知內容
     * @param contentText 通知顯示的文字
     */
    private fun updateNotification(contentText: String) {
        val stopSelf = Intent(this, MediaMonitorService::class.java).apply { action = ACTION_STOP_SERVICE }
        val pStopSelf = PendingIntent.getService(this, 0, stopSelf, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "什麼歌 服務監控", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("什麼歌 正在運作中")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "關閉服務", pStopSelf)
            .setOngoing(true) // 設定為 Persistent，使用者無法滑動清除
            .build()

        startForeground(FOREGROUND_SERVICE_ID, notification)
        Log.d(TAG, "updateNotification: 前景通知已更新: '$contentText'")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected: 通知監聽器已連線。")
        
        // [修正4] 重開機確認狀態，若為關閉則解除綁定
        val isServiceEnabled = sharedPrefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, false)
        if (!isServiceEnabled) {
             Log.i(TAG, "onListenerConnected: 服務停用中，請求 Unbind。")
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                 requestUnbind()
             }
             return
        }

        updateNotification("服務已連線，準備監控")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected: 通知監聽器已中斷連線。")
        // [注意] 此處不請求 Rebind，避免造成無限重啟迴圈。
        // 我們只在使用者顯式開啟服務時請求 Rebind。
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy: 服務正在銷毀。")
        releaseWakeLock()
        tts?.stop()
        tts?.shutdown()
        controllerMap.clear()
        networkExecutor.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            Log.i(TAG, "onInit: TTS 初始化成功。")
            
            // Voice Selection Logic
            val useGoogleVoice = sharedPrefs.getBoolean(MainActivity.KEY_GOOGLE_VOICE, true)
            val selectedVoiceName = sharedPrefs.getString(MainActivity.KEY_SELECTED_VOICE_NAME, "")
            
            val voices = tts?.voices
            var targetVoice: android.speech.tts.Voice? = null

            if (!selectedVoiceName.isNullOrEmpty() && !useGoogleVoice) {
                // Priority 1: User explicitly selected a voice AND disabled auto-optimize
                targetVoice = voices?.find { it.name == selectedVoiceName }
                if (targetVoice != null) {
                    Log.i(TAG, "onInit: 使用使用者指定語音: ${targetVoice.name}")
                }
            }
            
            if (targetVoice == null && useGoogleVoice) {
                // Priority 2: Auto Optimization (Default Gentle Female)
                // Look for "zh-TW" and "Network" (High Quality) or at least "Google"
                targetVoice = voices?.find { 
                    it.locale.language == Locale.TRADITIONAL_CHINESE.language &&
                    (it.name.contains("google", ignoreCase = true) && it.name.contains("network", ignoreCase = true))
                }
                
                // Fallback: If no network voice, just find any Google voice
                if (targetVoice == null) {
                     targetVoice = voices?.find { 
                        it.locale.language == Locale.TRADITIONAL_CHINESE.language &&
                        it.name.contains("google", ignoreCase = true)
                    }
                }
                
                if (targetVoice != null) Log.i(TAG, "onInit: 自動選擇優化語音: ${targetVoice.name}")
            }

            if (targetVoice != null) {
                tts?.voice = targetVoice
            } else {
                // Fallback: Just set Language
                tts?.language = Locale.TRADITIONAL_CHINESE
                Log.i(TAG, "onInit: 使用系統預設中文語音")
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { Log.d(TAG, "TTS 開始播報: '$utteranceId'") }
                override fun onDone(utteranceId: String?) { 
                    Log.d(TAG, "TTS 播報完成: '$utteranceId'")
                    resumePlayback(utteranceId)
                }
                override fun onError(utteranceId: String?) { 
                    Log.e(TAG, "TTS 發生錯誤: '$utteranceId'")
                    // 即使發生錯誤，也要嘗試恢復音訊狀態
                    mainHandler.post { 
                        controllerMap.remove(utteranceId) 
                        abandonAudioFocus()
                    } 
                    releaseWakeLock()
                }
            })
            
            // 處理佇列中等待播報的項目
            while (ttsQueue.isNotEmpty()) {
                val (title, controller) = ttsQueue.poll() ?: break
                handleMediaEvent(title, controller)
            }
        } else {
            Log.e(TAG, "onInit: TTS 初始化失敗，錯誤代碼: $status")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // [安全機制] 檢查服務是否在 App 設定中被啟用
        val isServiceEnabled = sharedPrefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, false)
        if (!isServiceEnabled) {
             return
        }

        val packageName = sbn.packageName
        
        // [修改] 檢查是否為監控對象
        val monitoredApps = sharedPrefs.getStringSet(MainActivity.KEY_MONITORED_APPS, null)
        val isTargetApp = if (monitoredApps.isNullOrEmpty()) {
            // Default targets if user hasn't selected any (or accidentally saved empty list)
            packageName == TARGET_PKG_YOUTUBE || 
            packageName == TARGET_PKG_REVANCED ||
            packageName == "com.google.android.apps.youtube.music" ||
            packageName == "com.spotify.music"
        } else {
            monitoredApps.contains(packageName)
        }

        if (!isTargetApp) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)

        val announceWhenScreenOn = sharedPrefs.getBoolean(MainActivity.KEY_ANNOUNCE_SCREEN_ON, false)
        if (!isScreenOff() && !announceWhenScreenOn) {
            // [Debug] 讓使用者知道服務活著，只是因為螢幕亮著而暫停播報
            updateNotification("暫停播報 (螢幕開啟中)")
            return // 螢幕開啟且設定不播報，則忽略
        }

        if (title == null || shouldIgnore(title)) {
            return
        }

        val controller = findMediaController(packageName, extras)
        if (controller == null) {
            Log.w(TAG, "onNotificationPosted: 找不到 MediaController。")
            return
        }

        // [修正2, 3] 檢查播放狀態，只在正在播放 (Playing) 時才進行播報
        // 解決：暫停時觸發、換歌時因中間狀態而重複觸發
        val playbackState = controller.playbackState
        if (playbackState != null && playbackState.state != PlaybackState.STATE_PLAYING) {
            // Log.d(TAG, "忽略非播放狀態: ${playbackState.state}") 
            return
        }

        handleMediaEvent(title, controller)
    }

    private fun findMediaController(packageName: String, extras: Bundle): MediaController? {
        val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (token != null) return MediaController(this, token)
        
        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MediaMonitorService::class.java)
            return mediaSessionManager.getActiveSessions(componentName).find { it.packageName == packageName }
        } catch (e: SecurityException) {
            Log.e(TAG, "findMediaController: 發生 SecurityException。", e)
        }
        return null
    }
    
    private fun isScreenOff(): Boolean {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.all { it.state != Display.STATE_ON }
    }

    private fun shouldIgnore(title: String): Boolean {
        if (title.isBlank()) return true
        
        // 10秒內重複的標題不處理
        // 10秒內重複的標題不處理 -> 改為 [修正1] 嚴格比對標題
        // 原本: if (title == lastProcessedTitle && (now - lastProcessedTime) < 10000)
        // 修正: 只要標題沒變，就永遠不重複播報。避免螢幕關閉、解鎖、或進度條更新時重複觸發。
        // 只有當「換歌」(標題改變) 時才觸發。
        if (title == lastProcessedTitle) {
            return true
        }
        
        val lowerTitle = title.lowercase()
        // [修正] 優化廣告偵測
        // 之前使用 'contains' 會誤釀殺包含「...廣告曲」的正版歌曲
        // 現在改用嚴格比對常見的廣告標題
        if (lowerTitle == "advertisement" || lowerTitle == "廣告" || 
            lowerTitle.startsWith("youtube advertisement") || lowerTitle.startsWith("youtube 廣告")) {
            return true
        }
        return false
    }

    private fun handleMediaEvent(title: String, controller: MediaController) {
        Log.i(TAG, "handleMediaEvent: 正在處理 \"$title\"。")
        lastProcessedTitle = title
        lastProcessedTime = System.currentTimeMillis()

        if (!isTtsReady) {
            Log.w(TAG, "handleMediaEvent: TTS 尚未準備好，加入佇列。")
            ttsQueue.add(Pair(title, controller))
            return
        }

        val useSmartParsing = sharedPrefs.getBoolean(MainActivity.KEY_SMART_TITLE_PARSING, true)
        val useItunesApi = sharedPrefs.getBoolean(MainActivity.KEY_USE_ITUNES_API, false)
        val audioDucking = sharedPrefs.getBoolean(MainActivity.KEY_AUDIO_DUCKING, true)
        
        updateNotification("正在處理: $title")
        acquireWakeLock()
        
        // Audio Control Strategy
        if (!audioDucking) {
            controller.transportControls.pause()
        } else {
            // Apply Audio Focus Ducking immediately before network request to prevent sudden burst if network is fast
            // However, typical flow is: Request Focus -> Speak.
            // If we request focus now and network takes 3 seconds, volume dips for 3 seconds of silence.
            // Better to duck right before speaking.
            // But if we DON'T pause (Ducking mode), music continues playing during network request.
            // This is desired.
        }

        if (useItunesApi) {
            networkExecutor.execute {
                try {
                    val iTunesTitle = fetchTitleFromiTunes(title)
                    val finalTitle = if (iTunesTitle != null) iTunesTitle else parseTitle(title)
                    
                    mainHandler.post {
                        val textToSpeak = generateSpeechText(finalTitle, iTunesTitle != null) // If coming from iTunes, structure allows better DJ-ing
                        updateNotification("正在播報: $textToSpeak")
                        speakTitle(textToSpeak, controller)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Network task failed", e)
                    mainHandler.post {
                        val textToSpeak = generateSpeechText(title, false)
                        updateNotification("正在播報: $textToSpeak")
                        speakTitle(textToSpeak, controller)
                    }
                }
            }
        } else {
            val titleToProcess = if (useSmartParsing) parseTitle(title) else title
            val textToSpeak = generateSpeechText(titleToProcess, false) // Simple parsing doesn't give us Artist separation easily unless we try harder
            updateNotification("正在播報: $textToSpeak")
            speakTitle(textToSpeak, controller)
        }
    }

    private fun generateSpeechText(titleOrMetadata: String, isStructuredAttributes: Boolean): String {
        val djMode = sharedPrefs.getBoolean(MainActivity.KEY_DJ_MODE, false)
        if (!djMode) return "正在播放：$titleOrMetadata"

        // Try to parse basic "Artist - Track" even if not from iTunes
        var artist = ""
        var track = titleOrMetadata
        var album = "" 
        var year = ""

        if (titleOrMetadata.contains("-")) {
            val parts = titleOrMetadata.split("-", limit = 2)
            artist = parts[0].trim()
            track = parts[1].trim()
        }

        // Filter templates
        val validTemplates = djTemplates.filter { 
            (!it.contains("#S") || artist.isNotEmpty()) &&
            (!it.contains("#D") || album.isNotEmpty()) &&
            (!it.contains("#Y") || year.isNotEmpty())
        }

        if (validTemplates.isEmpty()) return "正在為您播放，$track"

        val template = validTemplates.random()
        return template.replace("#S", artist).replace("#N", track).replace("#D", album).replace("#Y", year)
    }

    private var currentAudioFocusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus(): Boolean {
        val am = audioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) // 使用 Speech 類型
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    // 簡單處理 Focus 變化，通常 Ducking 不需要特別做什麼，系統會處理
                    Log.d(TAG, "Audio Focus Changed: $focusChange")
                }
                .build()
            
            currentAudioFocusRequest = focusRequest
            val result = am.requestAudioFocus(focusRequest)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentAudioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun speakTitle(text: String, controller: MediaController) {
        val audioDucking = sharedPrefs.getBoolean(MainActivity.KEY_AUDIO_DUCKING, true)

        if (audioDucking) {
             val focusGranted = requestAudioFocus()
             if (!focusGranted) {
                 Log.w(TAG, "Audio Focus request failed, speaking anyway.")
             }
        } else {
             // If not ducking, we should have already paused in handleMediaEvent.
             // Double check or reinforce pause if needed?
             // Actually, if !audioDucking, we paused WAY back at start of handleMediaEvent.
             // But if we used iTunes API, music has been stopped for a while.
             // Re-ensure pause not needed as we haven't played.
        }

        val utteranceId = UUID.randomUUID().toString()
        Log.i(TAG, "speakTitle: 播報 \"$text\"，ID: $utteranceId")
        controllerMap[utteranceId] = controller
        
        // Apply Speed and Volume
        val speed = sharedPrefs.getFloat(MainActivity.KEY_TTS_SPEED, 1.2f)
        val volume = sharedPrefs.getFloat(MainActivity.KEY_TTS_VOLUME, 1.0f)

        tts?.setSpeechRate(speed)
        
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speakTitle: TTS 請求失敗 (Result code: ERROR)")
            resumePlayback(utteranceId)
        }
    }

    private fun resumePlayback(utteranceId: String?) {
        mainHandler.post {
            val controller = controllerMap.remove(utteranceId)
            val audioDucking = sharedPrefs.getBoolean(MainActivity.KEY_AUDIO_DUCKING, true)

            if (audioDucking) {
                abandonAudioFocus()
                // No need to call play(), music should just 'unduck' (volume up) automatically
                // BUT, if the music app paused itself because of focus loss (some do), 
                // we might need to send play?
                // Usually with DUCK, they just lower volume.
                // However, let's keep it safe. If we didn't pause it, we don't play it.
                // Wait, logic check: In handleMediaEvent we ONLY pause if !audioDucking.
                // So here we only Play if !audioDucking.
            } else {
                if (controller != null) {
                    Log.i(TAG, "resumePlayback: 恢復播放，ID: $utteranceId")
                    controller.transportControls.play()
                }
            }
            releaseWakeLock()
            updateNotification("正在背景監控媒體播放")
        }
    }
    
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            Log.i(TAG, "取得 WakeLock。")
            wakeLock?.acquire(30*1000L /* 30 秒逾時安全機制 */)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            Log.i(TAG, "釋放 WakeLock。")
            wakeLock?.release()
        }
    }
    
    /**
     * 解析並優化歌曲標題
     *
     * 邏輯說明：
     * 1. 移除不必要的括號內容（如 HD, MV, Official Video 等）。
     * 2. 移除特定的垃圾關鍵字。
     * 3. 處理全形標點符號。
     * 4. 盡量保留 "歌手 - 歌名" 的完整結構，而不是只取最長的一段。
     */
    private fun parseTitle(title: String): String {
        var result = title

        // 1. 正規化：將全形連字號轉為半形，方便處理
        result = result.replace("－", "-")

        // 2. 移除常見的括號內雜訊 (例如 (Official Video), [MV], 【HD】)
        // 這裡我們不直接移除所有括號內容，因為括號內可能是 (feat. Artist) 或 (Live)
        // 策略：移除含有特定關鍵字的括號區塊
        val junkKeywords = listOf(
            "official", "video", "music video", "mv", "lyrics", "lyric", 
            "live", "concert", "full audio", "hq", "hd", "4k", "1080p", 
            "高畫質", "官方", "完整版", "字幕", "歌詞"
        )
        
        // 建立 Regex：匹配 ( ... ) 或 [ ... ] 或 【 ... 】，且內容包含垃圾關鍵字
        // (?i) 表示忽略大小寫
        val bracketsPattern = Regex("(?i)(\\([^\\)]*?(${junkKeywords.joinToString("|")})[^\\)]*?\\))|(\\[[^\\]]*?(${junkKeywords.joinToString("|")})[^\\]]*?\\])|(【[^】]*?(${junkKeywords.joinToString("|")})[^】]*?】)")
        result = result.replace(bracketsPattern, "")

        // 3. 再次清除殘留的無括號關鍵字 (有些標題是 "Song Name Official Video")
        val junkWordsPattern = Regex("(?i)\\b(${junkKeywords.joinToString("|")})\\b")
        result = result.replace(junkWordsPattern, "")

        // 4. 清理多餘的符號與空白
        // 移除可能殘留的空括號 () [] 【】
        result = result.replace(Regex("\\(\\s*\\)|\\[\\s*\\]|【\\s*】"), "")
        // 將多個空白合併為一個
        result = result.replace(Regex("\\s{2,}"), " ").trim()
        
        // 5. 移除頭尾的連字號 (例如 "- Song Name" 變成 "Song Name")
        result = result.trim('-', ' ')

        return if (result.isBlank()) title else result
    }

    /**
     * 使用 iTunes Search API 查詢歌曲資訊
     * 
     * @param originalTitle 原始標題
     * @return 格式化後的 "Artist - Track" 字串，若查詢失敗或無結果則回傳 null
     */
    private fun fetchTitleFromiTunes(originalTitle: String): String? {
        try {
            // 先做簡單的清理，提高命中率
            val queryTerm = parseTitle(originalTitle) // Reuse our local parser to clean junk first
            val encodedQuery = java.net.URLEncoder.encode(queryTerm, "UTF-8")
            val urlString = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=1&country=TW&lang=zh_TW"
            
            Log.i(TAG, "iTunes API Query: $urlString")

            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000 // 3秒 timeout，避免卡住太久
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val stream = connection.inputStream
                val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                
                val jsonResponse = org.json.JSONObject(response.toString())
                val resultCount = jsonResponse.optInt("resultCount")
                
                if (resultCount > 0) {
                    val results = jsonResponse.getJSONArray("results")
                    val firstResult = results.getJSONObject(0)
                    val artistName = firstResult.optString("artistName")
                    val trackName = firstResult.optString("trackName")
                    
                    if (artistName.isNotEmpty() && trackName.isNotEmpty()) {
                        Log.i(TAG, "iTunes API Success: $artistName - $trackName")
                        return "$artistName - $trackName"
                    }
                } else {
                     Log.w(TAG, "iTunes API found no results for: $queryTerm")
                     
                     // [新功能] 智慧重試 (Smart Retry): 若標題包含括號，嘗試搜尋括號內的內容
                     // 例如: "Artist【Song Name】Promo" -> 改搜 "Song Name"
                     val bracketsRegex = Regex("【(.*?)】")
                     val match = bracketsRegex.find(originalTitle)
                     if (match != null && match.groupValues.size > 1) {
                         val smartKeyword = match.groupValues[1].trim()
                         // 防止無限遞迴或無意義的搜尋
                         if (smartKeyword.isNotEmpty() && smartKeyword != queryTerm && smartKeyword.length > 1) {
                             Log.i(TAG, "iTunes API Retry with smart keyword: $smartKeyword")
                             return fetchTitleFromiTunes(smartKeyword) // 遞迴呼叫，使用更簡單的關鍵字
                         }
                     }
                }
            } else {
                 Log.w(TAG, "iTunes API Failed with code: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "iTunes API Error", e)
        }
        return null
    }
}
