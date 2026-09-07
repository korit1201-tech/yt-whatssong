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
 * 歌曲結構化資訊，DJ 模式的 #S #N #D #Y 佔位符都由此物件提供對應欄位。
 */
data class SongMetadata(
    val artist: String = "",
    val track: String = "",
    val album: String = "",
    val year: String = ""
)

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
    // 記錄上一首歌的結構化 Metadata，暫停恢復播放時可直接重用，不必重打一次網路請求
    private var lastMetadata: SongMetadata? = null
    // 記錄每個套件上一次觀察到的播放狀態，用於偵測「暫停/停止 -> 播放」的恢復事件
    private val lastPlaybackStateMap = mutableMapOf<String, Int>()
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
    // 正在查詢/播報中的標題。與 lastProcessedTitle 分開：前者代表「處理中」，後者代表「已成功播報」。
    // 播報失敗時只清掉 inFlightTitle 而不寫入 lastProcessedTitle，同一首歌的下一則通知才有機會重試。
    private var inFlightTitle: String? = null
    // 線上查詢的世代編號。使用者換到下一首歌時遞增，讓還在路上的舊查詢結果自動作廢，避免播報上一首歌。
    private var lookupGeneration = 0
    // TTS 引擎重新初始化的重試次數（語音引擎被更新或重裝後，舊的 TextToSpeech 實例會永久失效）
    private var ttsRestartAttempts = 0
    // 通知監聽器重新綁定的重試次數，連線成功時歸零
    private var rebindAttempts = 0
    // MediaSession 監聽（換曲偵測的第二條路徑，見 startSessionWatch 的說明）
    private var sessionManager: MediaSessionManager? = null
    private val registeredControllers =
        mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncSessionCallbacks(controllers)
        }

    /**
     * 安全網：播報流程若因為非預期的例外中途斷掉，[inFlightTitle] 會一直卡著，
     * 導致這首歌之後的所有通知都被當成「處理中」而跳過。這個 watchdog 會把它清掉。
     */
    private val clearInFlightTask = Runnable {
        if (inFlightTitle != null) {
            Log.w(TAG, "播報流程逾時未收尾，清除 inFlightTitle: $inFlightTitle")
            inFlightTitle = null
        }
    }

    // DJ 播報適用的時段。用裝置目前的本地時間判斷，避免「深夜電台」這類台詞在大白天被播出來
    private enum class DjTimeSlot { MORNING, AFTERNOON, EVENING, NIGHT }

    // 一句 DJ 台詞。timeSlots 為 null 代表任何時段都合適；只有明確提到時段的台詞才需要標記
    private data class DjTemplate(val text: String, val timeSlots: Set<DjTimeSlot>? = null)

    private fun djTemplate(text: String, vararg slots: DjTimeSlot) =
        DjTemplate(text, if (slots.isEmpty()) null else slots.toSet())

    // DJ Templates
    private val djTemplates = listOf(
        djTemplate("接下來這首，來自 #S 的經典作品，#N。"),
        djTemplate("讓 #S 的聲音陪你度過這個時刻，送上 #N。"),
        djTemplate("轉換一下心情，聽聽 #S 帶來的 #N。"),
        djTemplate("深夜了，適合讓 #S 的 #N 沉澱一下思緒。", DjTimeSlot.NIGHT),
        djTemplate("耳朵借給我，這是 #S 的 #N。"),
        djTemplate("沒什麼好說的，聽就對了，#S 演唱的 #N。"),
        djTemplate("來自 #S 的 #N，收錄在 #D 這張專輯裡。"),
        djTemplate("讓音樂連結我們，送上 #S 的 #N。"),
        djTemplate("不管過多久，#S 的 #N 總是能打動人心。"),
        djTemplate("這是屬於 #S 的時刻，請欣賞 #N。"),
        djTemplate("下一首，讓我們沉浸在 #S 的 #N 之中。"),
        djTemplate("推薦這首 #S 的 #N，出自經典專輯 #D。"),
        djTemplate("心情不好的時候，聽聽 #S 的 #N 最療癒。"),
        djTemplate("閉上眼睛，感受 #S 在 #N 裡的情感。"),
        djTemplate("這是 #S 很有代表性的一首歌，#N。"),
        djTemplate("今天的私房推薦，#S 的 #N，收錄在 #D 專輯。"),
        djTemplate("這旋律一出來你就知道是誰，#S 的 #N。"),
        djTemplate("來自 #Y 年的聲音，#S 的 #N。"),
        djTemplate("#S 的 #D 專輯中，這首 #N 絕對不能錯過。"),
        djTemplate("讓 #S 的 #N 帶走你的煩惱。"),
        djTemplate("節奏輕快起來，這是 #S 的 #N。"),
        djTemplate("很久沒聽這首了吧？#S 的 #N。"),
        djTemplate("獻給所有有故事的人，#S 的 #N。"),
        djTemplate("#S 的情歌總是很對味，這首 #N 也不例外。"),
        djTemplate("讓我們一起重溫 #D 專輯裡的這首好歌，#S 的 #N。"),
        djTemplate("#Y 年，我們一起聽過的 #S 的 #N。"),
        djTemplate("馬上為您送上，#S 的 #N。"),
        djTemplate("這聲音太迷人了，#S 的 #N。"),
        djTemplate("來自 #D 專輯，#S 的 #N。"),
        djTemplate("如果你還沒聽過 #S 的 #N，現在仔細聽。"),
        djTemplate("#Y 年發行的好歌，#S 的 #N。"),
        djTemplate("這是 #S 的 #N，希望能溫暖你的耳朵。"),
        djTemplate("經典永遠不嫌老，#S 的 #N。"),
        djTemplate("在這個城市的一角，我們聽 #S 的 #N。"),
        djTemplate("#S 在 #Y 年留下的美好回憶，#N。"),
        djTemplate("收錄在 #D 裡的隱藏好歌，#S 的 #N。"),
        djTemplate("準備好了嗎？#S 要帶來這首 #N。"),
        djTemplate("這張 #D 專輯真的很強，特別是這首 #S 的 #N。"),
        djTemplate("繼續來聽，這首不錯喔，#S 的 #N。"),
        djTemplate("最後推薦這首 #Y 年的作品，#S 的 #N，希望你喜歡。"),
        djTemplate("嗨，這裡是深夜電台，陪你聽的是 #S 的 #N。", DjTimeSlot.NIGHT),
        djTemplate("現在幾點了？不管幾點，先聽 #S 的 #N 再說。"),
        djTemplate("給還沒睡的你，送上 #S 的 #N。", DjTimeSlot.NIGHT),
        djTemplate("這首歌，#S 的 #N，獻給正在通勤路上的你。"),
        djTemplate("別轉台，接下來是 #S 的 #N。"),
        djTemplate("說真的，這首 #N 我私心很愛，來自 #S。"),
        djTemplate("今天想聊點什麼呢？先聽首歌吧，#S 的 #N。"),
        djTemplate("廣告之後不廢話，直接進 #S 的 #N。"),
        djTemplate("你聽，這段前奏是不是很熟悉？#S 的 #N。"),
        djTemplate("心情卡住的時候，放這首就對了，#S 的 #N。"),
        djTemplate("窗外的天氣配這首剛剛好，#S 的 #N。"),
        djTemplate("老朋友都知道，這首是 #S 的招牌，#N。"),
        djTemplate("深呼吸，跟著 #S 的 #N 放鬆一下。"),
        djTemplate("這是點播率超高的一首，#S 的 #N。"),
        djTemplate("你今天過得好嗎？先聽首歌再說，#S 的 #N。"),
        djTemplate("音量可以轉大聲一點，這首是 #S 的 #N。"),
        djTemplate("剛下班的你，辛苦了，聽首 #S 的 #N。", DjTimeSlot.EVENING),
        djTemplate("這首歌陪我度過很多個晚上，#S 的 #N。", DjTimeSlot.EVENING, DjTimeSlot.NIGHT),
        djTemplate("聽膩了流行榜？試試 #S 的這首 #N。"),
        djTemplate("這是我今天想跟你分享的歌，#S 的 #N。"),
        djTemplate("如果你也在等紅燈，順便聽首歌，#S 的 #N。"),
        djTemplate("開車的朋友注意安全，順便聽 #S 的 #N。"),
        djTemplate("這首很適合一個人靜靜聽，#S 的 #N。"),
        djTemplate("週末的早晨，就該配這首 #S 的 #N。", DjTimeSlot.MORNING),
        djTemplate("這首歌後勁很強，#S 的 #N。"),
        djTemplate("音樂不停，故事繼續，這是 #S 的 #N。"),
        djTemplate("有人點播了這首，#S 的 #N，一起聽聽。"),
        djTemplate("忙碌了一天，該放鬆了，聽 #S 的 #N。", DjTimeSlot.EVENING, DjTimeSlot.NIGHT),
        djTemplate("這首歌的旋律，一聽就上癮，#S 的 #N。"),
        djTemplate("給正在讀書的你，加油，順便聽 #S 的 #N。"),
        djTemplate("這首歌適合配一杯咖啡，#S 的 #N。", DjTimeSlot.MORNING, DjTimeSlot.AFTERNOON),
        djTemplate("說到經典，就不能不提 #S 的 #N。"),
        djTemplate("這首歌是今晚的重點，#S 的 #N。", DjTimeSlot.EVENING, DjTimeSlot.NIGHT),
        djTemplate("你準備好了嗎？來自 #S 的 #N。"),
        djTemplate("想哭的時候，這首很療癒，#S 的 #N。"),
        djTemplate("換首歌換個心情，#S 的 #N。"),
        djTemplate("這首歌是我私藏歌單裡的常客，#S 的 #N。"),
        djTemplate("聽完這首，你會想起誰呢？#S 的 #N。"),
        djTemplate("這是 #Y 年很紅的一首，#S 的 #N。"),
        djTemplate("把時間倒轉回 #Y 年，聽聽 #S 的 #N。"),
        djTemplate("#Y 年出生的朋友，這首歌跟你同年喔，#S 的 #N。"),
        djTemplate("收錄在 #D 專輯裡，這首 #S 的 #N 值得一聽。"),
        djTemplate("如果你剛好也在找 #D 這張專輯，先聽這首，#S 的 #N。"),
        djTemplate("#D 這張專輯的隱藏神曲，#S 的 #N。"),
        djTemplate("說到 #D 專輯，這首絕對是代表作，#S 的 #N。"),
        djTemplate("#Y 年發行至今還是很多人單曲循環，#S 的 #N。"),
        djTemplate("這首歌承包了 #Y 年整個夏天的回憶，#S 的 #N。"),
        djTemplate("好聽到會想按重播，#S 的 #N。"),
        djTemplate("這首節奏抓得很好，跟著點頭吧，#S 的 #N。"),
        djTemplate("慢下來，聽聽 #S 的 #N。"),
        djTemplate("這是屬於今晚的一首歌，#S 的 #N。", DjTimeSlot.EVENING, DjTimeSlot.NIGHT),
        djTemplate("想放空發呆，就聽這首，#S 的 #N。"),
        djTemplate("你可能沒注意過，但這首真的很好聽，#S 的 #N。"),
        djTemplate("我猜你會喜歡這首，#S 的 #N。"),
        djTemplate("這首很適合當作今天的背景音樂，#S 的 #N。"),
        djTemplate("這是一首會讓人安靜下來的歌，#S 的 #N。"),
        djTemplate("不囉嗦，直接進歌，#S 的 #N。"),
        djTemplate("這首歌一直在我的常駐歌單裡，#S 的 #N。"),
        djTemplate("給正在等人的你，聽首歌打發時間，#S 的 #N。"),
        djTemplate("這首很適合戴耳機仔細聽，#S 的 #N。")
    )

    /**
     * 根據裝置目前的本地時間判斷現在屬於哪個時段，用來過濾帶有時段限定的 DJ 台詞
     * （例如「深夜電台」不該在下午被播出來）。
     */
    private fun currentDjTimeSlot(): DjTimeSlot {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> DjTimeSlot.MORNING
            in 11..16 -> DjTimeSlot.AFTERNOON
            in 17..21 -> DjTimeSlot.EVENING
            else -> DjTimeSlot.NIGHT // 22, 23, 0~4 點
        }
    }

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

        /**
         * 使用者沒有自訂監控清單時的預設監控對象。
         *
         * ReVanced 有兩套並存的套件命名：官方 ReVanced 用 `app.revanced.*`，
         * 而流傳更廣的 ReVanced Extended (RVX) 用 `app.rvx.*`。舊版只列了前者，
         * 於是用 RVX 播放的歌曲完全不會被視為監控對象，一句都不會播報。
         */
        private val DEFAULT_TARGET_PACKAGES = setOf(
            TARGET_PKG_YOUTUBE,
            TARGET_PKG_REVANCED,
            "app.rvx.android.youtube",                  // ReVanced Extended - YouTube
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",  // ReVanced - YouTube Music
            "app.rvx.android.apps.youtube.music",       // ReVanced Extended - YouTube Music
            "com.spotify.music"
        )
        // 智慧重試（括號關鍵字）的最大遞迴深度，避免無限遞迴（iTunes 專用）
        private const val MAX_API_RETRY_DEPTH = 1
        // 候選結果與原標題的最低相似度門檻，低於此分數視為無把握、放棄配對（iTunes 專用）
        private const val SEARCH_SIMILARITY_THRESHOLD = 0.35
        // 依序嘗試查詢的 iTunes Store 地區，TW 找不到時退而求其其他地區
        private val ITUNES_SEARCH_COUNTRIES = listOf("TW", "US")
        // 同一首歌從暫停/停止恢復播放時，至少要間隔這麼久才會再次觸發播報，避免快速連續暫停/播放造成洗版
        private const val RESUME_ANNOUNCE_COOLDOWN_MS = 2000L
        // Google AI (Generative Language API) 使用的模型名稱。若這個模型 ID 失效或改名，
        // 只要改這一個常數即可，其餘呼叫邏輯不用動。
        private const val AI_MODEL_NAME = "gemma-4-31b-it"
        // AI 推論通常比 iTunes 慢，給比較長的逾時時間
        private const val AI_CONNECT_TIMEOUT_MS = 5000
        private const val AI_READ_TIMEOUT_MS = 10000
        // 線上查詢（iTunes/AI）的總逾時。超過就先用本地標題解析播報，
        // 避免網路慢的時候整首歌都播完了才聽到播報。
        private const val LOOKUP_TIMEOUT_MS = 4000L
        // inFlightTitle 的安全清除時間，必須大於 LOOKUP_TIMEOUT_MS 加上 TTS 播報時間
        private const val IN_FLIGHT_WATCHDOG_MS = 20000L
        // TTS 引擎失效後重新初始化的最多嘗試次數與基礎延遲
        private const val MAX_TTS_RESTART_ATTEMPTS = 3
        private const val TTS_RESTART_BASE_DELAY_MS = 2000L
        // 通知監聽器斷線後重新綁定的最多嘗試次數與基礎延遲（第 n 次等 n * BASE 毫秒）
        private const val MAX_REBIND_ATTEMPTS = 5
        private const val REBIND_BASE_DELAY_MS = 5000L
        // 主動輪詢 MediaSession 的間隔（換曲偵測的第三條路徑，見 pollTask 的說明）
        private const val SESSION_POLL_INTERVAL_MS = 15000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: 服務正在建立。")
        sharedPrefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // 這些資源不論服務開關與否都要初始化。
        // 之前這裡在服務停用時直接 return，導致 tts / wakeLock / audioManager 全部沒建立；
        // 而系統綁定通知監聽器時只會呼叫一次 onCreate，使用者稍後把開關打開走的是 onStartCommand，
        // 於是服務「活著、通知也在，但 isTtsReady 永遠是 false」，所有歌都被丟進佇列、一句都不會播。
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Whatssong::WakelockTag")
        wakeLock?.setReferenceCounted(false) // 手動管理釋放
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)

        // [修正4] 重開機不自動啟動：服務被使用者關閉時，不要佔用前景通知
        val isServiceEnabled = sharedPrefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, true)
        if (!isServiceEnabled) {
            Log.i(TAG, "onCreate: 服務被設為停用，不啟動前景通知。")
            return
        }

        // **修正點**: 在 onCreate 中立即啟動前景服務通知。
        // 這是防止服務被系統殺死的關鍵步驟。
        updateNotification("正在背景監控媒體播放")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: 服務收到指令，Action: ${intent?.action}")

        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.w(TAG, "onStartCommand: 收到停止 Action，正在停止服務。")

            // [修正] 更新 SharedPreferences，讓 MainActivity 知道服務已關閉
            sharedPrefs.edit().putBoolean(MainActivity.KEY_SERVICE_ENABLED, false).apply()

            stopSessionWatch()

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
        startSessionWatch()

        // 強制請求重新綁定通知監聽器 (解決服務意外重啟後失效的問題)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
             try {
                 val componentName = ComponentName(this, MediaMonitorService::class.java)
                 NotificationListenerService.requestRebind(componentName)
                 Log.i(TAG, "onStartCommand: 已請求 Rebind。")
             } catch (e: Exception) { /* 忽略錯誤 */ }
        }

        // 用 START_STICKY 而非 START_REDELIVER_INTENT：後者會在服務被系統殺掉後
        // 把「最後一個尚未以 stopSelf(startId) 標記完成的 Intent」重新投遞，
        // 而本服務從來沒有呼叫過 stopSelf(startId)，等於讓舊指令（含關閉指令）有機會被重播。
        // 這裡的 Intent 不帶任何狀態，重啟時重新讀 SharedPreferences 即可。
        return START_STICKY
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
        rebindAttempts = 0

        // [修正4] 重開機確認狀態，若為關閉則解除綁定
        val isServiceEnabled = sharedPrefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, true)
        if (!isServiceEnabled) {
             Log.i(TAG, "onListenerConnected: 服務停用中，請求 Unbind。")
             stopSessionWatch()
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                 requestUnbind()
             }
             return
        }

        updateNotification("服務已連線，準備監控")
        startSessionWatch()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected: 通知監聽器已中斷連線。")

        // 服務被系統（或 ColorOS/MIUI 這類廠商省電機制）殺掉時會走到這裡。
        // 舊版完全不重新綁定，於是監聽器就此失效，要等使用者手動開 App 或系統剛好重綁才會恢復——
        // 這正是「螢幕關著沒聲音、開螢幕或換歌才突然有」的來源。
        //
        // 為了避免無限重啟迴圈，這裡加上遞增退避與次數上限；服務被使用者關閉時則不重綁。
        val isServiceEnabled = sharedPrefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, true)
        if (!isServiceEnabled) {
            Log.i(TAG, "onListenerDisconnected: 服務停用中，不重新綁定。")
            return
        }
        scheduleRebind()
    }

    /**
     * 以遞增退避請求重新綁定通知監聽器。連線成功時 [onListenerConnected] 會把計數歸零。
     */
    private fun scheduleRebind() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (rebindAttempts >= MAX_REBIND_ATTEMPTS) {
            Log.e(TAG, "scheduleRebind: 已達重綁上限 ($MAX_REBIND_ATTEMPTS 次)，放棄自動恢復。")
            return
        }
        rebindAttempts++
        val delay = REBIND_BASE_DELAY_MS * rebindAttempts
        Log.i(TAG, "scheduleRebind: ${delay}ms 後請求第 $rebindAttempts 次重新綁定。")
        mainHandler.postDelayed({
            try {
                NotificationListenerService.requestRebind(ComponentName(this, MediaMonitorService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "scheduleRebind: requestRebind 失敗。", e)
            }
        }, delay)
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy: 服務正在銷毀。")
        stopSessionWatch()
        // 服務被銷毀後這些延遲任務再跑起來只會操作到已失效的實例
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        controllerMap.clear()
        ttsQueue.clear()
        inFlightTitle = null
        networkExecutor.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            ttsRestartAttempts = 0
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
                // 自動挑選語音：**離線語音優先**。
                //
                // 舊版優先挑名稱含 "network" 的 Google 高品質語音，但那種語音每唸一句都要即時連網取音檔。
                // 螢幕關閉、裝置進入 Doze 之後系統會切斷背景 App 的網路，於是 TTS 整句唸不出來——
                // 這正是「螢幕關著沒聲音、一開螢幕就有」的主因。本 App 的使用情境幾乎都在螢幕關閉時，
                // 所以寧可用音質稍差但一定唸得出來的離線語音，網路語音只留作最後備援。
                val zhVoices = voices.orEmpty().filter {
                    it.locale.language == Locale.TRADITIONAL_CHINESE.language &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }

                fun pick(predicate: (android.speech.tts.Voice) -> Boolean) = zhVoices.firstOrNull(predicate)

                targetVoice =
                    pick { !it.isNetworkConnectionRequired && it.locale.country == "TW" && it.name.contains("google", ignoreCase = true) }
                        ?: pick { !it.isNetworkConnectionRequired && it.locale.country == "TW" }
                        ?: pick { !it.isNetworkConnectionRequired && it.name.contains("google", ignoreCase = true) }
                        ?: pick { !it.isNetworkConnectionRequired }
                        ?: pick { it.locale.country == "TW" }
                        ?: zhVoices.firstOrNull()

                if (targetVoice != null) {
                    Log.i(TAG, "onInit: 自動選擇語音: ${targetVoice.name} (需要網路=${targetVoice.isNetworkConnectionRequired})")
                }
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
            isTtsReady = false
            Log.e(TAG, "onInit: TTS 初始化失敗，錯誤代碼: $status")
            scheduleTtsRestart()
        }
    }

    /**
     * 重新建立 TextToSpeech 實例。
     *
     * 語音引擎（通常是 Google TTS）被 Play 商店更新或重新安裝之後，舊的 TextToSpeech 實例會永久失效：
     * speak() 一律回傳 ERROR，而且不會有任何回呼通知。舊版沒有任何重試，服務就此變成啞巴，
     * 只能靠重開機或重開服務恢復。這裡以遞增退避重建，成功後 [onInit] 會把計數歸零。
     */
    private fun scheduleTtsRestart() {
        if (ttsRestartAttempts >= MAX_TTS_RESTART_ATTEMPTS) {
            Log.e(TAG, "scheduleTtsRestart: 已達重試上限，放棄重建 TTS。")
            updateNotification("語音引擎無法初始化，請檢查系統 TTS 設定")
            return
        }
        ttsRestartAttempts++
        val delay = TTS_RESTART_BASE_DELAY_MS * ttsRestartAttempts
        Log.w(TAG, "scheduleTtsRestart: ${delay}ms 後重建 TTS（第 $ttsRestartAttempts 次）。")
        mainHandler.postDelayed({
            try { tts?.shutdown() } catch (e: Exception) { Log.w(TAG, "shutdown 舊 TTS 失敗", e) }
            isTtsReady = false
            tts = TextToSpeech(this, this)
        }, delay)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!isServiceEnabled()) return

        val packageName = sbn.packageName
        if (!isMonitoredPackage(packageName)) return  // 非監控對象，量太大不記 log

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        Log.d(TAG, "來源=通知 pkg=$packageName title=\"$title\"")

        processSongEvent(packageName, title, findMediaController(packageName, extras))
    }

    /**
     * 服務是否在 App 設定中被啟用。
     */
    private fun isServiceEnabled(): Boolean =
        sharedPrefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, true)

    /**
     * 這個套件是否在監控清單內。使用者沒有自訂清單時採用 [DEFAULT_TARGET_PACKAGES]。
     */
    private fun isMonitoredPackage(packageName: String): Boolean {
        val monitoredApps = sharedPrefs.getStringSet(MainActivity.KEY_MONITORED_APPS, null)
        return if (monitoredApps.isNullOrEmpty()) {
            DEFAULT_TARGET_PACKAGES.contains(packageName)
        } else {
            monitoredApps.contains(packageName)
        }
    }

    /**
     * 換曲事件的共用處理路徑，[onNotificationPosted] 與 MediaSession 回呼都匯流到這裡，
     * 因此兩條來源共用同一套過濾條件與去重狀態（[inFlightTitle] / [lastProcessedTitle]），
     * 同一首歌不論由哪一邊先偵測到，都只會播報一次。
     */
    private fun processSongEvent(packageName: String, title: String?, controller: MediaController?) {
        val announceWhenScreenOn = sharedPrefs.getBoolean(MainActivity.KEY_ANNOUNCE_SCREEN_ON, true)
        if (!isScreenOff() && !announceWhenScreenOn) {
            Log.d(TAG, "略過: 螢幕開啟中且設定為不播報")
            // [Debug] 讓使用者知道服務活著，只是因為螢幕亮著而暫停播報
            updateNotification("暫停播報 (螢幕開啟中)")
            return // 螢幕開啟且設定不播報，則忽略
        }

        if (title == null || isHardIgnored(title)) {
            Log.d(TAG, "略過: 標題為空或命中忽略清單 (title=$title)")
            return
        }

        if (controller == null) {
            Log.w(TAG, "processSongEvent: 找不到 MediaController。")
            return
        }

        // [修正2, 3] 檢查播放狀態，只在正在播放 (Playing) 時才進行播報
        // 解決：暫停時觸發、換歌時因中間狀態而重複觸發
        val playbackState = controller.playbackState
        val currentState = playbackState?.state ?: PlaybackState.STATE_NONE
        val previousState = lastPlaybackStateMap[packageName]
        // 只有播放器真的回報了狀態才記錄，避免 null 汙染後續「暫停 -> 播放」的判斷
        if (playbackState != null) lastPlaybackStateMap[packageName] = currentState

        // playbackState 為 null 代表播放器還沒設定播放狀態（換歌瞬間、Spotify/ReVanced 很常見）。
        // 0.1.0 在這種情況是照樣播報的，1.1.0 改成一律跳過，整首歌就這樣被吃掉。這裡恢復舊行為。
        if (playbackState != null && currentState != PlaybackState.STATE_PLAYING) {
            Log.d(TAG, "略過: 非播放狀態 (state=$currentState, prev=$previousState)")
            return
        }

        // [新功能] 暫停/停止後恢復播放：即使歌名沒變，也重新播報一次
        // 只有從「明確暫停或停止」轉為「播放中」才算數，避免緩衝(Buffering)等暫態誤觸發
        val announceOnResume = sharedPrefs.getBoolean(MainActivity.KEY_ANNOUNCE_ON_RESUME, true)
        val isResumeOfSameSong = title == lastProcessedTitle &&
            announceOnResume &&
            (previousState == PlaybackState.STATE_PAUSED || previousState == PlaybackState.STATE_STOPPED) &&
            (System.currentTimeMillis() - lastProcessedTime) >= RESUME_ANNOUNCE_COOLDOWN_MS

        if (title == inFlightTitle) {
            // 這首歌正在查詢或播報中。媒體通知每秒都可能重發，這裡直接略過避免重複觸發。
            Log.d(TAG, "略過: 這首歌正在處理中")
            return
        }

        if (title == lastProcessedTitle && !isResumeOfSameSong) {
            // 同一首歌、且不構成一次有效的「恢復播放」事件，略過避免重複播報
            Log.d(TAG, "略過: 與上一首已播報的標題相同")
            return
        }

        handleMediaEvent(title, controller, isResumeOfSameSong)
    }

    // ---------------------------------------------------------------------
    // MediaSession 監聽：獨立於通知監聽器的第二條換曲偵測路徑
    // ---------------------------------------------------------------------

    /**
     * 啟動 MediaSession 監聽。
     *
     * 為什麼需要這條路：實測發現部分廠商 ROM（例如 ColorOS）在螢幕關閉、App 閒置數分鐘後，
     * 會停止把 onNotificationPosted 派送給第三方通知監聽器——此時系統的通知紀錄確實已更新、
     * App 程序也活著沒被凍結，但回呼就是不進來，於是整首歌沒有播報，直到使用者點亮螢幕才補上。
     *
     * MediaSession 的回呼由 media_session 服務派送，與通知監聽器是完全不同的 IPC 路徑，
     * 因此可作為換曲偵測的備援。兩條路都會匯流到 [processSongEvent]，由既有的去重邏輯確保只播報一次。
     */
    private fun startSessionWatch() {
        if (sessionManager != null) return
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaMonitorService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsChangedListener, component, mainHandler)
            sessionManager = msm
            syncSessionCallbacks(msm.getActiveSessions(component))
            mainHandler.removeCallbacks(pollTask)
            mainHandler.postDelayed(pollTask, SESSION_POLL_INTERVAL_MS)
            Log.i(TAG, "startSessionWatch: 已開始監聽 MediaSession（含 ${SESSION_POLL_INTERVAL_MS}ms 輪詢）。")
        } catch (e: SecurityException) {
            // 沒有通知存取權限時會拋這個，屬於預期情形（此時通知監聽器也不會運作）
            Log.e(TAG, "startSessionWatch: 缺少通知存取權限，無法監聽 MediaSession。", e)
        } catch (e: Exception) {
            Log.e(TAG, "startSessionWatch: 監聽 MediaSession 失敗。", e)
        }
    }

    private fun stopSessionWatch() {
        mainHandler.removeCallbacks(pollTask)
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            Log.w(TAG, "stopSessionWatch: 移除監聽器失敗。", e)
        }
        sessionManager = null
        registeredControllers.values.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (e: Exception) { /* 忽略 */ }
        }
        registeredControllers.clear()
    }

    /**
     * 依目前的活躍 session 清單，補上新出現的監控對象、移除已消失的，維持回呼註冊與現況一致。
     */
    private fun syncSessionCallbacks(controllers: List<MediaController>?) {
        val targets = controllers.orEmpty().filter { isMonitoredPackage(it.packageName) }
        val liveTokens = targets.map { it.sessionToken }.toSet()

        registeredControllers.keys.toList()
            .filterNot { liveTokens.contains(it) }
            .forEach { token ->
                registeredControllers.remove(token)?.let { (controller, callback) ->
                    try { controller.unregisterCallback(callback) } catch (e: Exception) { /* 忽略 */ }
                    Log.i(TAG, "syncSessionCallbacks: 已解除 ${controller.packageName} 的回呼。")
                }
            }

        targets.forEach { controller ->
            if (registeredControllers.containsKey(controller.sessionToken)) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                    onSessionUpdate(controller, metadata)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    onSessionUpdate(controller, controller.metadata)
                }

                override fun onSessionDestroyed() {
                    registeredControllers.remove(controller.sessionToken)
                    Log.i(TAG, "MediaSession 已銷毀: ${controller.packageName}")
                }
            }
            try {
                controller.registerCallback(callback, mainHandler)
                registeredControllers[controller.sessionToken] = controller to callback
                Log.i(TAG, "syncSessionCallbacks: 已註冊 ${controller.packageName} 的 MediaSession 回呼。")
            } catch (e: Exception) {
                Log.e(TAG, "syncSessionCallbacks: 註冊 ${controller.packageName} 回呼失敗。", e)
            }
        }
    }

    /**
     * 主動輪詢目前的活躍 MediaSession，作為換曲偵測的第三條路徑。
     *
     * 通知監聽器與 MediaSession 回呼都是「系統推給我們」的被動路徑，實測在部分廠商 ROM 上
     * 螢幕關閉一段時間後兩者會同時停止派送。這個輪詢改由 App 主動去問，只要程序還能執行就抓得到換曲。
     *
     * 每次執行都會留下一行 log，因此這條輪詢同時是判斷「程序究竟是被凍結、還是只有回呼被擋掉」的依據：
     * log 斷掉代表程序沒在跑，log 持續代表程序活著、只是收不到推送。
     *
     * 重複的標題會被 [processSongEvent] 既有的去重邏輯擋掉，不會造成重複播報。
     */
    private val pollTask = object : Runnable {
        override fun run() {
            pollActiveSessions()
            mainHandler.postDelayed(this, SESSION_POLL_INTERVAL_MS)
        }
    }

    private fun pollActiveSessions() {
        val msm = sessionManager ?: return
        if (!isServiceEnabled()) return
        try {
            val controllers = msm.getActiveSessions(ComponentName(this, MediaMonitorService::class.java))
            // 順便補註冊/清理回呼，session 換人時不必等系統通知我們
            syncSessionCallbacks(controllers)

            val targets = controllers.filter { isMonitoredPackage(it.packageName) }
            Log.d(TAG, "輪詢: 活躍 session=${controllers.size} 監控中=${targets.size}")

            targets.forEach { controller ->
                val title = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                if (!title.isNullOrBlank()) {
                    processSongEvent(controller.packageName, title, controller)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pollActiveSessions: 輪詢失敗。", e)
        }
    }

    private fun onSessionUpdate(controller: MediaController, metadata: android.media.MediaMetadata?) {
        if (!isServiceEnabled()) return
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        if (title.isNullOrBlank()) return
        Log.d(TAG, "來源=MediaSession pkg=${controller.packageName} title=\"$title\"")
        processSongEvent(controller.packageName, title, controller)
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

    /**
     * 判斷標題是否應被「硬性忽略」：空白、廣告、或命中使用者自訂的忽略關鍵字清單。
     * 這與「同一首歌重複播報」的判斷分開處理，避免恢復播放功能誤判。
     */
    private fun isHardIgnored(title: String): Boolean {
        if (title.isBlank()) return true

        val lowerTitle = title.lowercase()
        // [修正] 優化廣告偵測
        // 之前使用 'contains' 會誤殺包含「...廣告曲」的正版歌曲
        // 現在改用嚴格比對常見的廣告標題
        if (lowerTitle == "advertisement" || lowerTitle == "廣告" ||
            lowerTitle.startsWith("youtube advertisement") || lowerTitle.startsWith("youtube 廣告")) {
            return true
        }

        // [新功能] 使用者自訂忽略清單（與 Chrome 套件行為對齊）：直播、精華、合集等關鍵字命中則靜默跳過
        val ignoreKeywords = sharedPrefs.getString(MainActivity.KEY_IGNORE_KEYWORDS, MainActivity.DEFAULT_IGNORE_KEYWORDS)
            ?.split(",", "，")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (ignoreKeywords.any { lowerTitle.contains(it.lowercase()) }) {
            return true
        }

        return false
    }

    private fun handleMediaEvent(title: String, controller: MediaController, isResumeAnnounce: Boolean = false) {
        Log.i(TAG, "handleMediaEvent: 正在處理 \"$title\" (resume=$isResumeAnnounce)。")

        if (!isTtsReady) {
            // 只保留最新一首：TTS 就緒後把積了好幾首的舊歌一次唸完沒有意義，只會洗版。
            Log.w(TAG, "handleMediaEvent: TTS 尚未準備好，先記住這首歌。")
            ttsQueue.clear()
            ttsQueue.add(Pair(title, controller))
            inFlightTitle = null
            if (tts == null) scheduleTtsRestart()
            return
        }

        // 標記為「處理中」。這裡刻意不寫 lastProcessedTitle：
        // 舊版一進來就把標題記成已處理，只要後面任何一步失敗（TTS 沒就緒、speak 回傳 ERROR、
        // 網路例外），這首歌就被永久當成播過了，使用者必須換歌才可能再聽到播報。
        inFlightTitle = title
        mainHandler.removeCallbacks(clearInFlightTask)
        mainHandler.postDelayed(clearInFlightTask, IN_FLIGHT_WATCHDOG_MS)

        acquireWakeLock()

        // [新功能] 暫停恢復播放：直接沿用上次查到的 metadata 重新播報，不必再打一次網路請求，
        // 這樣恢復播放後幾乎能立即播報，也不會因為網路狀況給出不同的配對結果。
        if (isResumeAnnounce) {
            announceMetadata(title, lastMetadata ?: extractMetadataFromLocalTitle(title), controller, "恢復播放，重新播報")
            return
        }

        val useSmartParsing = sharedPrefs.getBoolean(MainActivity.KEY_SMART_TITLE_PARSING, false)
        val useItunesApi = sharedPrefs.getBoolean(MainActivity.KEY_USE_ITUNES_API, true)
        val useAiApi = sharedPrefs.getBoolean(MainActivity.KEY_USE_AI_API, false)
        val audioDucking = sharedPrefs.getBoolean(MainActivity.KEY_AUDIO_DUCKING, true)

        updateNotification("正在處理: $title")

        // Audio Control Strategy
        // 若不使用 Ducking（音量閃避），則直接暫停音樂，稍後在 TTS 播報結束後恢復播放。
        // 若使用 Ducking，則保持播放，改在 speakTitle() 中透過 AudioFocus 讓系統自動壓低音量。
        if (!audioDucking) {
            controller.transportControls.pause()
        }

        // [三選一] 本地標題簡化 / iTunes API / Gemini AI，三個設定互斥，只會有一個生效
        // AI 模式的容錯鏈是 iTunes 優先（速度快很多）-> 查無結果才改問 AI -> 還是失敗才退回本地解析
        when {
            useAiApi -> performApiLookupAndSpeak(title, controller) { fetchTitleFromItunesThenAi(it) }
            useItunesApi -> performApiLookupAndSpeak(title, controller) { fetchTitleFromiTunes(it) }
            else -> {
                val titleToProcess = if (useSmartParsing) parseTitle(title) else title
                announceMetadata(title, extractMetadataFromLocalTitle(titleToProcess), controller)
            }
        }
    }

    /**
     * 統一的播報收尾：產生台詞、送進 TTS，並且**只有真的送成功**才把標題記為已播報。
     * 失敗時只清掉 [inFlightTitle]，讓同一首歌的下一則媒體通知還有機會重試。
     */
    private fun announceMetadata(
        title: String,
        metadata: SongMetadata,
        controller: MediaController,
        noticePrefix: String = "正在播報"
    ) {
        lastMetadata = metadata
        val textToSpeak = generateSpeechText(metadata)
        updateNotification("$noticePrefix: $textToSpeak")

        val success = speakTitle(textToSpeak, controller)
        mainHandler.removeCallbacks(clearInFlightTask)
        inFlightTitle = null
        if (success) {
            lastProcessedTitle = title
            lastProcessedTime = System.currentTimeMillis()
        } else {
            Log.w(TAG, "announceMetadata: 播報失敗，不記錄 lastProcessedTitle，保留重試機會。")
        }
    }

    /**
     * 在背景執行緒呼叫線上 API（iTunes 或 Gemini AI，由呼叫端傳入 [fetcher]）查詢歌曲 Metadata，
     * 查無把握的結果或發生例外時，退回本地標題解析作為備援，最後統一在主執行緒播報。
     */
    private fun performApiLookupAndSpeak(title: String, controller: MediaController, fetcher: (String) -> SongMetadata?) {
        val generation = ++lookupGeneration
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)

        // 線上查詢最多等 LOOKUP_TIMEOUT_MS。iTunes 會依序試 TW/US 兩個地區、各自還有連線與讀取逾時，
        // 而且所有查詢共用同一條 networkExecutor 執行緒，網路差的時候整首歌都播完了才會聽到播報。
        // 逾時就先用本地標題解析播報，寧可資訊少一點也不要遲到。
        val timeoutTask = Runnable {
            if (generation != lookupGeneration) return@Runnable
            if (!answered.compareAndSet(false, true)) return@Runnable
            Log.w(TAG, "performApiLookupAndSpeak: 線上查詢逾時，改用本地標題解析播報。")
            announceMetadata(title, extractMetadataFromLocalTitle(parseTitle(title)), controller)
        }
        mainHandler.postDelayed(timeoutTask, LOOKUP_TIMEOUT_MS)

        networkExecutor.execute {
            val metadata = try {
                fetcher(title) ?: extractMetadataFromLocalTitle(parseTitle(title))
            } catch (e: Exception) {
                Log.e(TAG, "Network task failed", e)
                extractMetadataFromLocalTitle(parseTitle(title))
            }

            mainHandler.post {
                mainHandler.removeCallbacks(timeoutTask)
                // 查詢還在路上時使用者已經換到下一首歌，這筆結果直接丟棄，不然會播報上一首。
                if (generation != lookupGeneration) {
                    Log.w(TAG, "performApiLookupAndSpeak: 已換歌，丟棄過期的查詢結果。")
                    return@post
                }
                if (!answered.compareAndSet(false, true)) return@post
                announceMetadata(title, metadata, controller)
            }
        }
    }

    /**
     * 嘗試從本地標題文字中拆出「歌手 - 歌名」結構，做為沒有 AI/iTunes 資料時的最終備援 Metadata。
     *
     * 依序嘗試：
     * 1. 用 "-"（含全形、破折號等變體，已先正規化）分隔的「歌手 - 歌名」
     * 2. 用「：」或半形 ":" 分隔的「歌手：歌名」，涵蓋不少標題不用連字號的情況
     * 3. 用《書名號》包住歌名的中文常見格式，例如「周杰倫《晴天》」
     * 都拆不出來就整段當作歌名，不硬猜歌手。
     */
    private fun extractMetadataFromLocalTitle(title: String): SongMetadata {
        val normalized = title.replace("－", "-").replace("–", "-").replace("—", "-")

        for (separator in listOf("-", "：", ":")) {
            if (normalized.contains(separator)) {
                val parts = normalized.split(separator, limit = 2)
                val artist = parts[0].trim()
                val track = parts.getOrNull(1)?.trim().orEmpty()
                if (artist.isNotEmpty() && track.isNotEmpty()) {
                    return SongMetadata(artist = artist, track = track)
                }
            }
        }

        val bookTitleMatch = Regex("《(.+?)》").find(normalized)
        if (bookTitleMatch != null) {
            val track = bookTitleMatch.groupValues[1].trim()
            val artist = normalized.replace(bookTitleMatch.value, "").trim(' ', '-', '：', ':')
            if (track.isNotEmpty()) {
                return SongMetadata(artist = artist, track = track)
            }
        }

        return SongMetadata(track = normalized)
    }

    private fun generateSpeechText(metadata: SongMetadata): String {
        val djMode = sharedPrefs.getBoolean(MainActivity.KEY_DJ_MODE, true)
        if (!djMode) {
            return if (metadata.artist.isNotEmpty()) {
                "正在播放：${metadata.artist} - ${metadata.track}"
            } else {
                "正在播放：${metadata.track}"
            }
        }

        // 根據目前可用的欄位篩選範本，確保播報內容永遠合理
        val fieldEligible = djTemplates.filter {
            (!it.text.contains("#S") || metadata.artist.isNotEmpty()) &&
            (!it.text.contains("#D") || metadata.album.isNotEmpty()) &&
            (!it.text.contains("#Y") || metadata.year.isNotEmpty())
        }

        // 再依裝置目前時段篩掉不合時宜的台詞（例如白天不該播出「深夜電台」）。
        // 若篩到最後剛好沒有任何時段合適的候選（理論上不會發生，保險起見還是防一下），
        // 就退回只看欄位的結果，寧可少一點時段感，也不要播不出歌名。
        val currentSlot = currentDjTimeSlot()
        val timeEligible = fieldEligible.filter { it.timeSlots == null || it.timeSlots.contains(currentSlot) }
        val validTemplates = timeEligible.ifEmpty { fieldEligible }

        if (validTemplates.isEmpty()) return "正在為您播放，${metadata.track}"

        val template = validTemplates.random().text
        return template.replace("#S", metadata.artist).replace("#N", metadata.track).replace("#D", metadata.album).replace("#Y", metadata.year)
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

    private fun speakTitle(text: String, controller: MediaController): Boolean {
        val audioDucking = sharedPrefs.getBoolean(MainActivity.KEY_AUDIO_DUCKING, true)

        if (audioDucking) {
             val focusGranted = requestAudioFocus()
             if (!focusGranted) {
                 Log.w(TAG, "Audio Focus request failed, speaking anyway.")
             }
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
            // 語音引擎被更新/重裝後會一直落到這裡，必須重建 TextToSpeech 才會恢復
            Log.e(TAG, "speakTitle: TTS 請求失敗 (Result code: ERROR)")
            isTtsReady = false
            resumePlayback(utteranceId)
            scheduleTtsRestart()
            return false
        }
        return true
    }

    private fun resumePlayback(utteranceId: String?) {
        mainHandler.post {
            val controller = controllerMap.remove(utteranceId)
            val audioDucking = sharedPrefs.getBoolean(MainActivity.KEY_AUDIO_DUCKING, true)

            if (audioDucking) {
                abandonAudioFocus()
                // Ducking 模式下音樂本來就沒有暫停，Focus 一放掉音量會自動回復，不需呼叫 play()
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
     * 移除 "｜" 或 "|" 分隔符之後的內容。
     *
     * 許多戲劇/MV 標題格式為「歌手 - 歌名｜劇名【集數】品牌 MV | 發行方」，
     * 分隔符後面通常是劇名、集數標籤、MV 品牌、發行方等裝飾性資訊，並非歌曲本身的一部分。
     * 若不裁切，這些雜訊會混進搜尋字串，導致 API 查無結果，甚至讓智慧重試誤抓到
     * 像「第一次」這種看似關鍵字、實際上只是集數標籤的字詞，配對到完全不相關的歌曲。
     */
    private fun stripAfterPipeDelimiter(title: String): String {
        val cropped = title.split("｜", "|").first().trim()
        return if (cropped.isNotBlank()) cropped else title
    }

    /**
     * 解析並優化歌曲標題
     *
     * 邏輯說明：
     * 1. 裁切掉 ｜ 或 | 分隔符後面的裝飾性資訊（劇名、集數、發行方等）。
     * 2. 移除不必要的括號內容（如 HD, MV, Official Video 等）。
     * 3. 移除特定的垃圾關鍵字。
     * 4. 處理全形標點符號。
     * 5. 盡量保留 "歌手 - 歌名" 的完整結構，而不是只取最長的一段。
     */
    private fun parseTitle(title: String): String {
        var result = stripAfterPipeDelimiter(title)

        // 1. 正規化：將全形連字號轉為半形，方便處理
        result = result.replace("－", "-")

        // 2. 移除常見的括號內雜訊 (例如 (Official Video), [MV], 【HD】)
        // 這裡我們不直接移除所有括號內容，因為括號內可能是 (feat. Artist) 或 (Live)
        // 策略：移除含有特定關鍵字的括號區塊
        val junkKeywords = listOf(
            "official", "video", "music video", "mv", "lyrics", "lyric",
            "live", "concert", "full audio", "hq", "hd", "4k", "1080p",
            "cover", "acoustic", "remix", "audio", "visualizer", "pv",
            "teaser", "trailer", "highlight", "highlights", "premiere",
            "高畫質", "官方", "完整版", "字幕", "歌詞", "純享版", "純享",
            "抖音版", "動態歌詞", "歌詞版", "無損", "高音質", "首播", "先行版", "搶先聽"
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
     * 依序嘗試 [ITUNES_SEARCH_COUNTRIES] 中的每個地區，每次取回多筆候選結果，
     * 並以字詞重疊率挑出與原標題最相近的一筆，避免像過去只取第一筆結果、
     * 標題稍微冷門或不常見就配對到完全不相關歌曲的問題。
     *
     * @param originalTitle 原始標題
     * @param depth 智慧重試（括號關鍵字）的遞迴深度，避免無限遞迴
     * @return 結構化的歌曲 Metadata，若查無高信心度的結果則回傳 null
     */
    private fun fetchTitleFromiTunes(originalTitle: String, depth: Int = 0): SongMetadata? {
        // 只在 ｜/| 分隔符前的範圍內找歌曲相關資訊，避免智慧重試誤抓到分隔符後面的
        // 劇名/集數標籤（例如「劇名【第一次】MV」的「第一次」其實是集數，不是歌名）
        val searchScope = stripAfterPipeDelimiter(originalTitle)
        val queryTerm = parseTitle(searchScope)
        if (queryTerm.isBlank()) return null

        for (country in ITUNES_SEARCH_COUNTRIES) {
            val match = searchItunesOnce(queryTerm, country)
            if (match != null) return match
        }

        if (depth < MAX_API_RETRY_DEPTH) {
            // [智慧重試] 若標題包含【 】括號，嘗試搜尋括號內的內容
            // 例如: "Artist【Song Name】Promo" -> 改搜 "Song Name"
            val bracketsRegex = Regex("【(.*?)】")
            val bracketMatch = bracketsRegex.find(searchScope)
            if (bracketMatch != null && bracketMatch.groupValues.size > 1) {
                val smartKeyword = bracketMatch.groupValues[1].trim()
                // 防止無限遞迴或無意義的搜尋
                if (smartKeyword.isNotEmpty() && smartKeyword != queryTerm && smartKeyword.length > 1) {
                    Log.i(TAG, "iTunes API Retry with smart keyword: $smartKeyword")
                    return fetchTitleFromiTunes(smartKeyword, depth + 1)
                }
            }
        }

        return null
    }

    /**
     * 對單一地區發出一次 iTunes 查詢，取回最多 5 筆候選並挑出相似度最高者。
     */
    private fun searchItunesOnce(queryTerm: String, country: String): SongMetadata? {
        try {
            val encodedQuery = java.net.URLEncoder.encode(queryTerm, "UTF-8")
            val urlString = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=5&country=$country"

            Log.i(TAG, "iTunes API Query: $urlString")

            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000 // 3秒 timeout，避免卡住太久
            connection.readTimeout = 3000

            if (connection.responseCode != 200) {
                Log.w(TAG, "iTunes API Failed with code: ${connection.responseCode}")
                return null
            }

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

            if (resultCount <= 0) {
                Log.w(TAG, "iTunes API found no results for: $queryTerm ($country)")
                return null
            }

            val results = jsonResponse.getJSONArray("results")
            var best: SongMetadata? = null
            var bestScore = 0.0

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val artistName = item.optString("artistName")
                val trackName = item.optString("trackName")
                if (artistName.isEmpty() || trackName.isEmpty()) continue

                val candidate = SongMetadata(
                    artist = artistName,
                    track = trackName,
                    album = item.optString("collectionName"),
                    year = item.optString("releaseDate").take(4)
                )
                val score = titleSimilarity(queryTerm, "$artistName $trackName")
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }

            if (best != null && bestScore >= SEARCH_SIMILARITY_THRESHOLD) {
                Log.i(TAG, "iTunes API Match ($country, score=$bestScore): ${best.artist} - ${best.track}")
                return best
            }

            Log.w(TAG, "iTunes API: 候選結果相似度過低 (bestScore=$bestScore)，視為無把握的配對而放棄")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "iTunes API Error", e)
        }
        return null
    }

    /**
     * AI 模式的容錯鏈：iTunes 速度快很多，優先呼叫；只有 iTunes 查無把握的結果時，
     * 才改問 Gemini/Gemma（AI 推論通常慢上好幾秒，當備援用可以兼顧準確度與平均回應速度）。
     * 若兩者皆失敗回傳 null，交由呼叫端 (performApiLookupAndSpeak) 再退回本地標題解析。
     */
    private fun fetchTitleFromItunesThenAi(title: String): SongMetadata? {
        return fetchTitleFromiTunes(title) ?: fetchTitleFromGemini(title)
    }

    /**
     * 使用 Google AI (Generative Language API，模型見 [AI_MODEL_NAME]) 直接理解整段原始標題。
     * 跟 iTunes/本地解析的規則式清理不同，這裡把「完整原始標題」直接交給 AI 判斷，
     * 讓 AI 自行分辨哪些是歌手/歌名/專輯/年份，哪些是戲劇名、集數標籤、MV 品牌等裝飾性雜訊——
     * 這正是規則式清理最容易誤判的地方（例如把集數標籤誤認成歌名關鍵字）。
     *
     * @return 結構化的歌曲 Metadata；未設定 API Key、呼叫失敗或 AI 回傳內容無法解析則回傳 null
     */
    private fun fetchTitleFromGemini(originalTitle: String): SongMetadata? {
        val apiKey = sharedPrefs.getString(MainActivity.KEY_AI_API_KEY, "")
        if (apiKey.isNullOrBlank()) return null

        try {
            val prompt = buildString {
                append("你是一個音樂資訊助手。請閱讀以下 YouTube/音樂 App 的通知標題，")
                append("判斷其中真正的歌手、歌名、專輯、發行年份，忽略戲劇名、集數標籤、MV 品牌、發行方、頻道名稱等裝飾性文字。\n")
                append("標題：")
                append(originalTitle)
                append("\n\n請只輸出 JSON，格式為 {\"artist\": \"\", \"track\": \"\", \"album\": \"\", \"year\": \"\"}，")
                append("若不確定或找不到某個欄位，該欄位請填空字串，不要編造不存在的資訊。")
            }

            val requestBody = org.json.JSONObject().apply {
                put(
                    "contents",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put(
                            "parts",
                            org.json.JSONArray().put(org.json.JSONObject().put("text", prompt))
                        )
                    )
                )
                put(
                    "generationConfig",
                    org.json.JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.1)
                    }
                )
            }

            val encodedKey = java.net.URLEncoder.encode(apiKey, "UTF-8")
            val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$AI_MODEL_NAME:generateContent?key=$encodedKey"

            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = AI_CONNECT_TIMEOUT_MS
            connection.readTimeout = AI_READ_TIMEOUT_MS

            connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode != 200) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "Gemini API Failed with code: ${connection.responseCode}, $errorText")
                return null
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val responseJson = org.json.JSONObject(responseText)
            val candidates = responseJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Log.w(TAG, "Gemini API: 沒有回傳任何 candidate")
                return null
            }

            val contentText = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")

            if (contentText.isNullOrBlank()) {
                Log.w(TAG, "Gemini API: candidate 內容為空")
                return null
            }

            val metadataJson = org.json.JSONObject(contentText)
            val artist = metadataJson.optString("artist").trim()
            val track = metadataJson.optString("track").trim()

            if (track.isEmpty()) {
                Log.w(TAG, "Gemini API: AI 找不到歌名")
                return null
            }

            val metadata = SongMetadata(
                artist = artist,
                track = track,
                album = metadataJson.optString("album").trim(),
                year = metadataJson.optString("year").trim()
            )
            Log.i(TAG, "Gemini API Match: ${metadata.artist} - ${metadata.track}")
            return metadata
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API Error", e)
        }
        return null
    }

    /**
     * 以字詞重疊率 (Jaccard Similarity) 估算兩段文字的相似度，用來從多筆候選結果中
     * 挑出與查詢字串最相近的一筆，取代過去「無條件相信第一筆結果」的作法。
     * 中文以單字為 token、英數字以連續字元組成 token，兩者混合計算。
     */
    private fun titleSimilarity(a: String, b: String): Double {
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return intersection.toDouble() / union
    }

    private fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val wordBuilder = StringBuilder()
        for (ch in text.lowercase()) {
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    if (wordBuilder.isNotEmpty()) { tokens.add(wordBuilder.toString()); wordBuilder.clear() }
                    tokens.add(ch.toString())
                }
                ch.isLetterOrDigit() -> wordBuilder.append(ch)
                else -> {
                    if (wordBuilder.isNotEmpty()) { tokens.add(wordBuilder.toString()); wordBuilder.clear() }
                }
            }
        }
        if (wordBuilder.isNotEmpty()) tokens.add(wordBuilder.toString())
        return tokens
    }
}
