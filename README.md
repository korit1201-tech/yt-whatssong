# YT WhatsSong — 歌曲播報員

> **「換歌了？讓它自己說出來吧。」**
>
> 當 YouTube / YouTube Music / Spotify 換下一首歌時，自動用語音唸出歌手名與歌名。
> 支援 **Android APP** 與 **Chrome 瀏覽器套件** 兩個平台，核心邏輯一致。

---

## 專案結構

```
metallic-plasma/
├── app/                        # Android APP (Kotlin)
│   └── src/main/java/com/korit/ytwhatssong/
│       ├── MainActivity.kt     # 設定介面 + 權限管理
│       └── MediaMonitorService.kt  # 核心監聽服務
└── chrome-extension/           # Chrome 套件 (MV3)
    ├── manifest.json
    ├── background.js           # Service Worker：核心協調者
    ├── content.js              # 注入頁面：偵測換曲 + 音量 Duck
    ├── popup.html / popup.js   # 設定彈窗 UI
    └── backup_legacy_tts/      # 舊版 TTS 實作備份
```

---

## 核心架構與設計理念

### 問題定義

使用者在背景播放音樂時，換曲後無法得知目前播放的是哪首歌，需要拿起手機或切換視窗才能確認。本工具讓裝置主動「開口報幕」，解決這個日常痛點。

---

### Android APP 核心機制

#### 1. NotificationListenerService — 最穩定的換曲訊號來源

Android 提供 `NotificationListenerService`，可以在背景接收所有 App 的通知異動。音樂 App（YouTube、YT Music、Spotify、ReVanced 等）換曲時，一定會更新媒體通知欄（包含歌曲標題），因此通知訊號是最通用、最穩定的換曲偵測方式。

- `MediaMonitorService` 繼承 `NotificationListenerService`
- 監聽 `onNotificationPosted` 事件
- 從通知的 `extras` 中取出 `EXTRA_TITLE` 欄位作為歌曲標題

#### 2. MediaSession API — 輔助確認播放狀態

通知更新不代表歌曲真的在播（例如暫停後通知依然存在）。透過 `MediaSessionManager` 取得當前 `MediaController`，確認 `PlaybackState` 確實為 `STATE_PLAYING` 才觸發播報，避免誤報。

#### 3. 暫停 → 播報 → 恢復 (Pause-Speak-Resume)

為了讓使用者清楚聽到播報內容，採用以下序列：

1. 對目標 App 的 `MediaController` 送出 `pause()` 指令
2. 使用 Android `TextToSpeech` 引擎播報歌名
3. 透過 `UtteranceProgressListener.onDone()` 回呼，在 TTS 結束後送出 `play()` 恢復音樂

**WakeLock** 確保 CPU 在 TTS 播完前不進入休眠，防止播到一半中斷。

#### 4. 防抖動機制 (Debounce)

連續快速切歌、或 App 在短時間內重複發送相同通知時，會觸發多次播報。防抖邏輯：

- 記錄 `lastProcessedTitle` 與 `lastProcessedTime`
- 若標題與上次相同，且距離上次播報未超過 **3 秒**，則忽略

#### 5. iTunes Search API — 取得完整 Metadata

YouTube 標題往往包含「[Official MV]」、「(HD)」等雜訊，且可能只有歌名沒有歌手。透過 iTunes Search API (`https://itunes.apple.com/search`) 以清理後的標題搜尋，可以取得：

- `artistName` — 正確歌手名
- `trackName` — 正確歌名
- `collectionName` — 專輯名
- `releaseDate` — 發行年份

這些 metadata 在 **DJ 模式** 中用於生成自然語言介紹詞。

#### 6. 智慧標題清理 (Smart Title Parsing)

在送往 iTunes API 前，先用 Regex 去除常見雜訊：

```
[Official Video] → 移除
(Official MV)    → 移除
【HD】           → 移除
```

並嘗試拆解 `歌手 - 歌名` 格式作為備援 metadata。

---

### Chrome 套件核心機制

Chrome 套件採用 Manifest V3 架構，三個元件各司其職：

#### content.js — 駐紮在 YouTube 頁面的哨兵

注入 YouTube / YT Music 頁面，透過 **MutationObserver** 監聽 DOM 變化：

- 觀察 `<title>` 標籤的文字變化（YouTube 換曲時會更新頁面標題）
- 偵測到標題變更後，傳送 `PROCESS_NEW_SONG` 訊息給 `background.js`
- 負責 **Audio Ducking**：收到 `DUCK_ON` 指令時，將 `<video>` 元素音量降低至 30%；收到 `DUCK_OFF` 時恢復 100%

#### background.js — 核心協調者 (Service Worker)

整個播報流程的大腦：

1. 收到 `PROCESS_NEW_SONG` 訊息
2. 比對忽略清單（直播、全集、合集等關鍵字）
3. 呼叫 iTunes API 取得 metadata
4. 根據模式生成播報文案
5. 透過 `chrome.tts.speak()` 播報
6. TTS 的 `end` 事件觸發後，通知 content.js 送出 `DUCK_OFF`

#### popup.js — 使用者設定介面

提供即時可調的控制面板：

- 主開關（Enable/Disable）
- 語音選擇（列舉系統所有 TTS 聲音）
- 播放速度 / 音量滑桿
- DJ 模式開關
- 忽略清單編輯
- 播放歷史紀錄查看

---

### DJ 模式 — 50 條隨機開場白範本

啟用 DJ 模式後，不再只是平鋪直敘地報幕，而是從 50 條預設台詞中，根據 **當前可用的 metadata 欄位**智慧篩選，隨機選一條播報：

```
#S = 歌手名  #N = 歌名  #D = 專輯名  #Y = 發行年份
```

範例：
- `"帶你回到 #Y 年，那時候大街小巷都在放 #S 的 #N。"` ← 需要年份
- `"收錄在 #D 裡的隱藏好歌，#S 的 #N。"` ← 需要專輯名

若 iTunes 回傳的結果缺少某些欄位，該欄位對應的範本會被自動濾除，確保播報內容永遠合理。

---

### 忽略清單機制

部分 YouTube 影片雖然帶有通知，但本質上不是「歌曲」：直播、精華回放、長片合集等。

設定一組逗號分隔的關鍵字（預設：`直播,精華,全集,合集`），系統在處理任何標題前先比對，命中則靜默跳過。

---

## Android APP 安裝

### 前置需求

- Android 8.0 (API 26) 以上
- Android Studio Hedgehog 或更新版本
- JDK 17+

### 建置步驟

```bash
# 進入專案根目錄
cd metallic-plasma

# 建置 Debug APK
./gradlew assembleDebug

# APK 輸出位置
app/build/outputs/apk/debug/app-debug.apk
```

### 首次使用必要權限

| 權限 | 用途 |
|------|------|
| 通知存取權（NotificationListenerService）| 偵測媒體通知換曲 |
| 顯示通知（POST_NOTIFICATIONS, Android 13+）| 顯示前景服務通知 |
| 忽略電池優化 | 確保背景服務不被系統殺死 |
| 喚醒鎖定（WAKE_LOCK）| TTS 完整播放不中斷 |

> **重要**：必須在系統設定的「通知存取」中手動授予本 App 監聽通知的權限，這是 Android 的安全機制，無法透過程式自動授予。

---

## Chrome 套件安裝

### 開發者模式安裝（未上架版本）

1. 解壓縮 `yt-whatssong-release.zip`
2. 開啟 `chrome://extensions`
3. 右上角開啟「**開發者模式**」
4. 點擊「**載入未封裝項目**」
5. 選擇解壓縮後包含 `manifest.json` 的資料夾

### 套件權限說明

| 權限 | 用途 |
|------|------|
| `tts` | 呼叫 Chrome 內建語音合成 |
| `storage` | 儲存使用者設定與歷史紀錄 |
| `scripting` | 注入 content.js 至 YouTube 頁面 |
| `notifications` | 通知模式（替代語音）|
| `declarativeContent` | 只在 YouTube 網域顯示套件圖示 |
| `host: *.youtube.com` | 讀取頁面標題 |
| `host: itunes.apple.com` | iTunes Metadata API |

---

## 功能對照表

| 功能 | Android APP | Chrome 套件 |
|------|:-----------:|:-----------:|
| 換曲自動播報 | ✅ | ✅ |
| iTunes Metadata 查詢 | ✅ | ✅ |
| 智慧標題清理 | ✅ | ✅ |
| DJ 模式隨機台詞 | ✅ | ✅ |
| 忽略清單 | ✅ | ✅ |
| 音量 Ducking | ✅（暫停/恢復）| ✅（30% 音量）|
| 語音引擎選擇 | ✅ | ✅ |
| 播放速度調整 | ✅ | ✅ |
| 支援 Spotify | ✅ | ❌ |
| 支援 YouTube ReVanced | ✅ | ❌ |
| 播放歷史紀錄 | ❌ | ✅ |
| 通知模式 | ❌ | ✅ |

---

## 技術堆疊

### Android
- **語言**：Kotlin
- **架構**：NotificationListenerService + MediaSession API
- **TTS**：Android TextToSpeech (`android.speech.tts`)
- **網路**：`java.net.HttpURLConnection`（執行緒池）
- **Metadata**：iTunes Search API

### Chrome 套件
- **架構**：Manifest V3 (Service Worker)
- **TTS**：Chrome TTS API (`chrome.tts`)
- **換曲偵測**：MutationObserver（監聽 DOM `<title>`）
- **Metadata**：iTunes Search API
- **設定儲存**：`chrome.storage.local`

---

## 注意事項

- iTunes API 為免費公開 API，無需 API Key，但搜尋結果依賴標題相似度，部分冷門歌曲可能找不到。
- Android 版本需手動關閉電池優化，否則長時間螢幕關閉後服務可能被系統回收。
- Chrome 套件僅支援 YouTube 與 YouTube Music，如需支援 Spotify Web 版可延伸 `host_permissions`。

---

## License

Private / Personal Use Only
