# Changelog

本專案的重大變更會記錄在這份文件中。格式參考 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.0.0/)。

## [Unreleased] - 2026-07-06

### 新增
- **Gemini AI 備援查詢**（Android + Chrome 套件）：iTunes API 查無結果時，改問 Gemini AI 理解標題、忽略劇名/集數/MV 品牌等雜訊；三選一（本地標題簡化／iTunes／AI）互斥選擇。
- **忽略清單**（Android）：新增「編輯忽略清單」按鈕與對話框，命中關鍵字（直播、精華、全集、合集等）的標題不會播報，與 Chrome 套件行為一致。
- **暫停恢復播放時也播報**（Android）：新增設定開關，同一首歌從暫停恢復播放時會重新播報一次，與 Chrome 套件行為一致。
- **Chrome 套件「最近播放」清單可點擊**：每筆紀錄改存 `{ title, url }`，點擊會在新分頁開啟該首歌當時的 YouTube 影片並自動播放。

### 變更
- **DJ 模式預設改為開啟**（Android + Chrome 套件）。
- Chrome 套件 popup 介面重新分組（語音與播報 / 歌曲辨識 / AI 加強 / 忽略名單 / 最近播放），Gemini AI 進階設定改為預設收合的 `<details>` 區塊，減少視覺雜訊。
- Android 端多項設定預設值調整為開啟（服務啟用、螢幕開啟時播報、iTunes API 優化），對齊 Chrome 套件的預設體驗。

## [0.1.0] - 2026-07-06

### 新增
- 初始版本：Android APP（NotificationListenerService + MediaSession 換曲偵測、TTS 播報、iTunes Metadata 查詢、DJ 模式、忽略清單）與 Chrome 套件（MV3，MutationObserver 換曲偵測、TTS 播報、Audio Ducking、播放歷史）。
