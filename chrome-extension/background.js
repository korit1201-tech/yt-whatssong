// background.js - AI DJ 核心協調者
// 負責：監聽 Content Script 訊息 -> 依序嘗試 iTunes API / Gemini AI -> 選擇適當的開場白 (模板) -> 控制 TTS 與音量

// 預設設定值
const DEFAULT_SETTINGS = {
  isEnabled: true,
  smartTitleParsing: true,
  useItunesApi: true,
  useAiApi: false,
  geminiApiKey: "",
  geminiModel: "gemma-4-31b-it",
  djMode: true,
  volume: 1.0,
  playbackSpeed: 1.2,
  selectedVoiceURI: null,
  notificationMode: false,
  ignoreList: "直播,精華,全集,合集",
  announceOnResume: true
};

// 智慧重試（括號關鍵字）的最大遞迴深度，避免無限遞迴
const MAX_API_RETRY_DEPTH = 1;
// 候選結果與查詢字串的最低相似度門檻，低於此分數視為無把握、放棄配對
const SEARCH_SIMILARITY_THRESHOLD = 0.35;
// 依序嘗試查詢的 iTunes Store 地區，TW 找不到時退而求其其他地區
const ITUNES_SEARCH_COUNTRIES = ["TW", "US"];

// 初始化設定
chrome.runtime.onInstalled.addListener(() => {
  // === Smart Icon Visibility ===
  // 1. 預設停用 Action (圖示變灰)
  chrome.action.disable();

  // 2. 清除舊規則並加入新規則
  chrome.declarativeContent.onPageChanged.removeRules(undefined, () => {
    chrome.declarativeContent.onPageChanged.addRules([{
      conditions: [
        new chrome.declarativeContent.PageStateMatcher({
          pageUrl: { hostSuffix: 'youtube.com' },
        })
      ],
      actions: [new chrome.declarativeContent.ShowAction()]
    }]);
  });

  // === Default Settings ===
  chrome.storage.local.get(Object.keys(DEFAULT_SETTINGS), (result) => {
    const newSettings = {};
    Object.keys(DEFAULT_SETTINGS).forEach(key => {
      if (result[key] === undefined) {
        newSettings[key] = DEFAULT_SETTINGS[key];
      }
    });
    if (Object.keys(newSettings).length > 0) {
      chrome.storage.local.set(newSettings);
    }
  });
});

// 監聽來自 Content Script 或 Popup 的訊息
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.type === 'PROCESS_NEW_SONG') {
    // 收到新歌通知，開始處理
    handleNewSongProcess(request.title, sender.tab.id, sender.tab.url);
  } else if (request.type === 'RESUME_ANNOUNCE') {
    // 暫停恢復播放：直接重講上一次的播報內容，不重新查一次 API
    handleResumeAnnounce(request.title, sender.tab.id);
  } else if (request.type === 'TEST_AI_KEY') {
    // Popup 的「測試 API Key」按鈕
    testGeminiKey(request.apiKey, request.model).then(sendResponse);
    return true; // 保持通道開啟以等待非同步回應
  } else if (request.type === 'SPEAK') {
    // 簡單朗讀 (Fallback)
    chrome.storage.local.get(['volume', 'playbackSpeed'], (settings) => {
      const options = {
        lang: 'zh-TW',
        rate: parseFloat(settings.playbackSpeed) || 1.0,
        volume: parseFloat(settings.volume) || 1.0
      };
      chrome.tts.speak(request.text, options);
    });
  }
  // 必須回傳 true 以保持 sendResponse 通道開啟 (若有非同步操作)
  return true;
});

async function handleNewSongProcess(rawTitle, tabId, tabUrl) {
  const settings = await chrome.storage.local.get(Object.keys(DEFAULT_SETTINGS));

  if (!settings.isEnabled) return;
  if (shouldIgnore(rawTitle, settings.ignoreList)) return;

  addToHistory(rawTitle, tabUrl);

  const metadata = await resolveMetadata(rawTitle, settings);
  const script = buildScript(metadata, settings);

  // 快取這次的播報內容，「暫停恢復播放」時可以直接重講，不必再打一次網路請求
  chrome.storage.session.set({ lastProcessedTitle: rawTitle, lastScript: script });

  await speakScript(script, tabId, settings);
}

async function handleResumeAnnounce(title, tabId) {
  const settings = await chrome.storage.local.get(Object.keys(DEFAULT_SETTINGS));
  if (!settings.isEnabled || !settings.announceOnResume) return;

  const cache = await chrome.storage.session.get(['lastProcessedTitle', 'lastScript']);
  if (cache.lastProcessedTitle !== title || !cache.lastScript) {
    // 快取沒有命中同一首歌（可能剛好換了新歌，或 Service Worker 剛被系統回收重啟過）。
    // 這種情況不特別處理，避免跟真正的換歌播報重複觸發。
    return;
  }

  await speakScript(cache.lastScript, tabId, settings);
}

/**
 * 依序嘗試 iTunes API（速度快，優先）-> Gemini AI（查無把握的結果才問，當備援）-> 本地標題解析，
 * 找出第一個成功的結果。
 */
async function resolveMetadata(rawTitle, settings) {
  let metadata = null;

  if (settings.useItunesApi) {
    metadata = await fetchItunesInfo(rawTitle);
  }

  if (!metadata && settings.useAiApi && settings.geminiApiKey) {
    metadata = await fetchGeminiInfo(rawTitle, settings.geminiApiKey, settings.geminiModel || DEFAULT_SETTINGS.geminiModel);
  }

  if (!metadata) {
    const localTitle = settings.smartTitleParsing ? cleanTitle(rawTitle) : rawTitle;
    metadata = extractMetadataFromLocalTitle(localTitle);
  }

  return metadata;
}

function buildScript(metadata, settings) {
  if (settings.djMode) {
    const script = generateDJScript(metadata);
    if (script) return script;
  }

  const S = metadata.artistName || "";
  const N = metadata.trackName || "";
  return S ? `現在播放的是，${S} 的 ${N}。` : `現在播放的是，${N}。`;
}

async function speakScript(script, tabId, settings) {
  const userRate = settings.playbackSpeed !== undefined ? parseFloat(settings.playbackSpeed) : 1.2;
  const userVolume = settings.volume !== undefined ? parseFloat(settings.volume) : 1.0;
  const selectedVoice = settings.selectedVoiceURI;

  if (settings.notificationMode) {
    chrome.notifications.create({
      type: 'basic',
      iconUrl: 'icons/icon128.png',
      title: 'Now Playing',
      message: script
    });
    return;
  }

  console.log(`[TTS Config] Voice: ${selectedVoice || 'System Default'}, Rate: ${userRate}, Volume: ${userVolume}`);

  // 播放語音時，總是降低背景音量 (Ducking)，不論是否為 DJ 模式
  chrome.tabs.sendMessage(tabId, { action: 'DUCK_ON' });

  const ttsOptions = {
    rate: userRate,
    volume: userVolume,
    onEvent: (event) => {
      // 監聽結束或錯誤事件，恢復音量
      if (event.type === 'end' || event.type === 'interrupted' || event.type === 'error') {
        chrome.tabs.sendMessage(tabId, { action: 'DUCK_OFF' });
      }
    }
  };

  if (selectedVoice) {
    ttsOptions.voiceName = selectedVoice;
  } else {
    ttsOptions.lang = 'zh-TW';
  }

  chrome.tts.speak(script, ttsOptions);
}

// DJ 播報適用的時段。用瀏覽器裝置目前的本地時間判斷，避免「深夜電台」這類台詞在大白天被播出來
const DjTimeSlot = { MORNING: 'MORNING', AFTERNOON: 'AFTERNOON', EVENING: 'EVENING', NIGHT: 'NIGHT' };

function currentDjTimeSlot() {
  const hour = new Date().getHours();
  if (hour >= 5 && hour <= 10) return DjTimeSlot.MORNING;
  if (hour >= 11 && hour <= 16) return DjTimeSlot.AFTERNOON;
  if (hour >= 17 && hour <= 21) return DjTimeSlot.EVENING;
  return DjTimeSlot.NIGHT; // 22, 23, 0~4 點
}

// 100 條 AI DJ 隨機介紹詞庫。timeSlots 省略代表任何時段都合適，只有明確提到時段的台詞才需要標記
const DJ_TEMPLATES = [
  { text: "接下來這首，來自 #S 的經典作品，#N。" },
  { text: "讓 #S 的聲音陪你度過這個時刻，送上 #N。" },
  { text: "轉換一下心情，聽聽 #S 帶來的 #N。" },
  { text: "深夜了，適合讓 #S 的 #N 沉澱一下思緒。", timeSlots: [DjTimeSlot.NIGHT] },
  { text: "耳朵借給我，這是 #S 的 #N。" },
  { text: "沒什麼好說的，聽就對了，#S 演唱的 #N。" },
  { text: "來自 #S 的 #N，收錄在 #D 這張專輯裡。" },
  { text: "讓音樂連結我們，送上 #S 的 #N。" },
  { text: "不管過多久，#S 的 #N 總是能打動人心。" },
  { text: "這是屬於 #S 的時刻，請欣賞 #N。" },
  { text: "下一首，讓我們沉浸在 #S 的 #N 之中。" },
  { text: "推薦這首 #S 的 #N，出自經典專輯 #D。" },
  { text: "心情不好的時候，聽聽 #S 的 #N 最療癒。" },
  { text: "閉上眼睛，感受 #S 在 #N 裡的情感。" },
  { text: "這是 #S 很有代表性的一首歌，#N。" },
  { text: "今天的私房推薦，#S 的 #N，收錄在 #D 專輯。" },
  { text: "這旋律一出來你就知道是誰，#S 的 #N。" },
  { text: "來自 #Y 年的聲音，#S 的 #N。" },
  { text: "#S 的 #D 專輯中，這首 #N 絕對不能錯過。" },
  { text: "讓 #S 的 #N 帶走你的煩惱。" },
  { text: "節奏輕快起來，這是 #S 的 #N。" },
  { text: "很久沒聽這首了吧？#S 的 #N。" },
  { text: "獻給所有有故事的人，#S 的 #N。" },
  { text: "#S 的情歌總是很對味，這首 #N 也不例外。" },
  { text: "讓我們一起重溫 #D 專輯裡的這首好歌，#S 的 #N。" },
  { text: "#Y 年，我們一起聽過的 #S 的 #N。" },
  { text: "馬上為您送上，#S 的 #N。" },
  { text: "這聲音太迷人了，#S 的 #N。" },
  { text: "來自 #D 專輯，#S 的 #N。" },
  { text: "如果你還沒聽過 #S 的 #N，現在仔細聽。" },
  { text: "#Y 年發行的好歌，#S 的 #N。" },
  { text: "這是 #S 的 #N，希望能溫暖你的耳朵。" },
  { text: "經典永遠不嫌老，#S 的 #N。" },
  { text: "在這個城市的一角，我們聽 #S 的 #N。" },
  { text: "#S 在 #Y 年留下的美好回憶，#N。" },
  { text: "收錄在 #D 裡的隱藏好歌，#S 的 #N。" },
  { text: "準備好了嗎？#S 要帶來這首 #N。" },
  { text: "這張 #D 專輯真的很強，特別是這首 #S 的 #N。" },
  { text: "繼續來聽，這首不錯喔，#S 的 #N。" },
  { text: "最後推薦這首 #Y 年的作品，#S 的 #N，希望你喜歡。" },
  { text: "嗨，這裡是深夜電台，陪你聽的是 #S 的 #N。", timeSlots: [DjTimeSlot.NIGHT] },
  { text: "現在幾點了？不管幾點，先聽 #S 的 #N 再說。" },
  { text: "給還沒睡的你，送上 #S 的 #N。", timeSlots: [DjTimeSlot.NIGHT] },
  { text: "這首歌，#S 的 #N，獻給正在通勤路上的你。" },
  { text: "別轉台，接下來是 #S 的 #N。" },
  { text: "說真的，這首 #N 我私心很愛，來自 #S。" },
  { text: "今天想聊點什麼呢？先聽首歌吧，#S 的 #N。" },
  { text: "廣告之後不廢話，直接進 #S 的 #N。" },
  { text: "你聽，這段前奏是不是很熟悉？#S 的 #N。" },
  { text: "心情卡住的時候，放這首就對了，#S 的 #N。" },
  { text: "窗外的天氣配這首剛剛好，#S 的 #N。" },
  { text: "老朋友都知道，這首是 #S 的招牌，#N。" },
  { text: "深呼吸，跟著 #S 的 #N 放鬆一下。" },
  { text: "這是點播率超高的一首，#S 的 #N。" },
  { text: "你今天過得好嗎？先聽首歌再說，#S 的 #N。" },
  { text: "音量可以轉大聲一點，這首是 #S 的 #N。" },
  { text: "剛下班的你，辛苦了，聽首 #S 的 #N。", timeSlots: [DjTimeSlot.EVENING] },
  { text: "這首歌陪我度過很多個晚上，#S 的 #N。", timeSlots: [DjTimeSlot.EVENING, DjTimeSlot.NIGHT] },
  { text: "聽膩了流行榜？試試 #S 的這首 #N。" },
  { text: "這是我今天想跟你分享的歌，#S 的 #N。" },
  { text: "如果你也在等紅燈，順便聽首歌，#S 的 #N。" },
  { text: "開車的朋友注意安全，順便聽 #S 的 #N。" },
  { text: "這首很適合一個人靜靜聽，#S 的 #N。" },
  { text: "週末的早晨，就該配這首 #S 的 #N。", timeSlots: [DjTimeSlot.MORNING] },
  { text: "這首歌後勁很強，#S 的 #N。" },
  { text: "音樂不停，故事繼續，這是 #S 的 #N。" },
  { text: "有人點播了這首，#S 的 #N，一起聽聽。" },
  { text: "忙碌了一天，該放鬆了，聽 #S 的 #N。", timeSlots: [DjTimeSlot.EVENING, DjTimeSlot.NIGHT] },
  { text: "這首歌的旋律，一聽就上癮，#S 的 #N。" },
  { text: "給正在讀書的你，加油，順便聽 #S 的 #N。" },
  { text: "這首歌適合配一杯咖啡，#S 的 #N。", timeSlots: [DjTimeSlot.MORNING, DjTimeSlot.AFTERNOON] },
  { text: "說到經典，就不能不提 #S 的 #N。" },
  { text: "這首歌是今晚的重點，#S 的 #N。", timeSlots: [DjTimeSlot.EVENING, DjTimeSlot.NIGHT] },
  { text: "你準備好了嗎？來自 #S 的 #N。" },
  { text: "想哭的時候，這首很療癒，#S 的 #N。" },
  { text: "換首歌換個心情，#S 的 #N。" },
  { text: "這首歌是我私藏歌單裡的常客，#S 的 #N。" },
  { text: "聽完這首，你會想起誰呢？#S 的 #N。" },
  { text: "這是 #Y 年很紅的一首，#S 的 #N。" },
  { text: "把時間倒轉回 #Y 年，聽聽 #S 的 #N。" },
  { text: "#Y 年出生的朋友，這首歌跟你同年喔，#S 的 #N。" },
  { text: "收錄在 #D 專輯裡，這首 #S 的 #N 值得一聽。" },
  { text: "如果你剛好也在找 #D 這張專輯，先聽這首，#S 的 #N。" },
  { text: "#D 這張專輯的隱藏神曲，#S 的 #N。" },
  { text: "說到 #D 專輯，這首絕對是代表作，#S 的 #N。" },
  { text: "#Y 年發行至今還是很多人單曲循環，#S 的 #N。" },
  { text: "這首歌承包了 #Y 年整個夏天的回憶，#S 的 #N。" },
  { text: "好聽到會想按重播，#S 的 #N。" },
  { text: "這首節奏抓得很好，跟著點頭吧，#S 的 #N。" },
  { text: "慢下來，聽聽 #S 的 #N。" },
  { text: "這是屬於今晚的一首歌，#S 的 #N。", timeSlots: [DjTimeSlot.EVENING, DjTimeSlot.NIGHT] },
  { text: "想放空發呆，就聽這首，#S 的 #N。" },
  { text: "你可能沒注意過，但這首真的很好聽，#S 的 #N。" },
  { text: "我猜你會喜歡這首，#S 的 #N。" },
  { text: "這首很適合當作今天的背景音樂，#S 的 #N。" },
  { text: "這是一首會讓人安靜下來的歌，#S 的 #N。" },
  { text: "不囉嗦，直接進歌，#S 的 #N。" },
  { text: "這首歌一直在我的常駐歌單裡，#S 的 #N。" },
  { text: "給正在等人的你，聽首歌打發時間，#S 的 #N。" },
  { text: "這首很適合戴耳機仔細聽，#S 的 #N。" }
];

function generateDJScript(metadata) {
  if (!metadata) return "";

  // 準備標籤資料
  const S = metadata.artistName || "";
  const N = metadata.trackName || "";
  const D = metadata.collectionName || ""; // Album
  const Y = metadata.releaseDate ? metadata.releaseDate.substring(0, 4) : "";

  // 如果連歌名都沒有，就回傳無內容
  if (!N) return "";

  // 先根據目前可用的欄位篩選範本
  const fieldEligible = DJ_TEMPLATES.filter(tpl => {
    if (!S && tpl.text.includes("#S")) return false;
    if (!D && tpl.text.includes("#D")) return false;
    if (!Y && tpl.text.includes("#Y")) return false;
    return true;
  });

  // 再依裝置目前時段篩掉不合時宜的台詞（例如白天不該播出「深夜電台」）。
  // 若剛好篩到沒有任何時段合適的候選，就退回只看欄位的結果，寧可少一點時段感，也不要播不出歌名。
  const currentSlot = currentDjTimeSlot();
  const timeEligible = fieldEligible.filter(tpl => !tpl.timeSlots || tpl.timeSlots.includes(currentSlot));
  const validTemplates = timeEligible.length > 0 ? timeEligible : fieldEligible;

  // 如果沒有可用範本 (例如沒有歌手名)，這時候只好回退到最簡單的報幕
  if (validTemplates.length === 0) {
    if (S) return `現在播放的是，${S} 的 ${N}。`;
    return `現在播放的是，${N}。`;
  }

  // 隨機選一個
  const tpl = validTemplates[Math.floor(Math.random() * validTemplates.length)];

  // 替換標籤
  return tpl.text.replace(/#S/g, S)
    .replace(/#N/g, N)
    .replace(/#D/g, D)
    .replace(/#Y/g, Y);
}

/**
 * 移除 "｜" 或 "|" 分隔符之後的內容。
 *
 * 許多戲劇/MV 標題格式為「歌手 - 歌名｜劇名【集數】品牌 MV | 發行方」，
 * 分隔符後面通常是劇名、集數標籤、MV 品牌、發行方等裝飾性資訊，並非歌曲本身的一部分。
 * 若不裁切，這些雜訊會混進搜尋字串，導致 API 查無結果，甚至讓智慧重試誤抓到
 * 像「第一次」這種看似關鍵字、實際上只是集數標籤的字詞，配對到完全不相關的歌曲。
 */
function stripAfterPipeDelimiter(title) {
  const cropped = title.split(/[｜|]/)[0].trim();
  return cropped.length > 0 ? cropped : title;
}

const JUNK_KEYWORDS = [
  "official", "video", "music video", "mv", "lyrics", "lyric",
  "live", "concert", "full audio", "hq", "hd", "4k", "1080p",
  "cover", "acoustic", "remix", "audio", "visualizer", "pv",
  "teaser", "trailer", "highlight", "highlights", "premiere",
  "高畫質", "官方", "完整版", "字幕", "歌詞", "純享版", "純享",
  "抖音版", "動態歌詞", "歌詞版", "無損", "高音質", "首播", "先行版", "搶先聽"
];

/**
 * 解析並優化歌曲標題（與 Android App 的 parseTitle 邏輯對稱）
 *
 * 1. 裁切掉 ｜ 或 | 分隔符後面的裝飾性資訊（劇名、集數、發行方等）。
 * 2. 移除不必要的括號內容（如 HD, MV, Official Video 等）。
 * 3. 移除特定的垃圾關鍵字。
 * 4. 處理全形標點符號。
 */
function cleanTitle(title) {
  let result = stripAfterPipeDelimiter(title);

  // 全形連字號轉半形，方便處理
  result = result.replace(/－/g, "-");

  const kw = JUNK_KEYWORDS.join("|");

  // 移除含有垃圾關鍵字的括號區塊：( ... ) 或 [ ... ] 或 【 ... 】
  const bracketsPattern = new RegExp(
    `(\\([^)]*?(${kw})[^)]*?\\))|(\\[[^\\]]*?(${kw})[^\\]]*?\\])|(【[^】]*?(${kw})[^】]*?】)`,
    "gi"
  );
  result = result.replace(bracketsPattern, "");

  // 再次清除殘留的無括號關鍵字 (有些標題是 "Song Name Official Video")
  const wordsPattern = new RegExp(`\\b(${kw})\\b`, "gi");
  result = result.replace(wordsPattern, "");

  // 清理殘留的空括號與多餘空白
  result = result.replace(/\(\s*\)|\[\s*\]|【\s*】/g, "");
  result = result.replace(/\s{2,}/g, " ").trim();
  result = result.replace(/^[-\s]+|[-\s]+$/g, "");

  return result.length > 0 ? result : title;
}

/**
 * 嘗試從本地標題文字中拆出「歌手 - 歌名」結構，做為 AI/iTunes 都失敗時的最終備援。
 *
 * 依序嘗試：
 * 1. 用 "-"（含全形、破折號等變體，已先正規化）分隔的「歌手 - 歌名」
 * 2. 用「：」或半形 ":" 分隔的「歌手：歌名」
 * 3. 用《書名號》包住歌名的中文常見格式，例如「周杰倫《晴天》」
 * 都拆不出來就整段當作歌名，不硬猜歌手。
 */
function extractMetadataFromLocalTitle(title) {
  const normalized = title.replace(/－/g, "-").replace(/–/g, "-").replace(/—/g, "-");

  for (const separator of ["-", "：", ":"]) {
    if (normalized.includes(separator)) {
      const idx = normalized.indexOf(separator);
      const artist = normalized.slice(0, idx).trim();
      const track = normalized.slice(idx + separator.length).trim();
      if (artist && track) {
        return { artistName: artist, trackName: track, collectionName: "", releaseDate: "" };
      }
    }
  }

  const bookMatch = normalized.match(/《(.+?)》/);
  if (bookMatch) {
    const track = bookMatch[1].trim();
    const artist = normalized.replace(bookMatch[0], "").trim().replace(/^[-\s：:]+|[-\s：:]+$/g, "");
    if (track) {
      return { artistName: artist, trackName: track, collectionName: "", releaseDate: "" };
    }
  }

  return { artistName: "", trackName: normalized, collectionName: "", releaseDate: "" };
}

/**
 * 使用 iTunes Search API 查詢歌曲資訊。
 *
 * 依序嘗試 ITUNES_SEARCH_COUNTRIES 中的每個地區，每次取回多筆候選結果，
 * 並以字詞重疊率挑出與原標題最相近的一筆，避免只取第一筆結果、標題稍微冷門
 * 就配對到完全不相關歌曲的問題。
 *
 * @param originalTitle 原始標題
 * @param depth 智慧重試（括號關鍵字）的遞迴深度，避免無限遞迴
 * @return 符合 {artistName, trackName, collectionName, releaseDate} 形狀的物件；查無高信心度結果則為 null
 */
async function fetchItunesInfo(originalTitle, depth = 0) {
  const searchScope = stripAfterPipeDelimiter(originalTitle);
  const queryTerm = cleanTitle(searchScope);
  if (!queryTerm) return null;

  for (const country of ITUNES_SEARCH_COUNTRIES) {
    const match = await searchItunesOnce(queryTerm, country);
    if (match) return match;
  }

  if (depth < MAX_API_RETRY_DEPTH) {
    // [智慧重試] 若標題包含【 】括號，嘗試搜尋括號內的內容
    const bracketMatch = searchScope.match(/【(.*?)】/);
    if (bracketMatch) {
      const smartKeyword = bracketMatch[1].trim();
      if (smartKeyword && smartKeyword !== queryTerm && smartKeyword.length > 1) {
        console.log(`iTunes API Retry with smart keyword: ${smartKeyword}`);
        return fetchItunesInfo(smartKeyword, depth + 1);
      }
    }
  }

  return null;
}

async function searchItunesOnce(queryTerm, country) {
  try {
    const encodedQuery = encodeURIComponent(queryTerm);
    const url = `https://itunes.apple.com/search?term=${encodedQuery}&entity=song&limit=5&country=${country}`;
    console.log(`Searching iTunes: ${url}`);

    const response = await fetch(url);
    if (!response.ok) {
      console.warn(`iTunes API Failed with status: ${response.status}`);
      return null;
    }

    const data = await response.json();
    if (!data.resultCount || data.resultCount <= 0) {
      console.warn(`iTunes API found no results for: ${queryTerm} (${country})`);
      return null;
    }

    let best = null;
    let bestScore = 0;
    for (const item of data.results) {
      if (!item.artistName || !item.trackName) continue;
      const score = titleSimilarity(queryTerm, `${item.artistName} ${item.trackName}`);
      if (score > bestScore) {
        bestScore = score;
        best = item;
      }
    }

    if (best && bestScore >= SEARCH_SIMILARITY_THRESHOLD) {
      console.log(`iTunes API Match (${country}, score=${bestScore.toFixed(2)}): ${best.artistName} - ${best.trackName}`);
      return best;
    }

    console.warn(`iTunes API: 候選結果相似度過低 (bestScore=${bestScore.toFixed(2)})，視為無把握的配對而放棄`);
    return null;
  } catch (err) {
    console.warn("iTunes API Fetch Error", err);
    return null;
  }
}

/**
 * 使用 Google AI (Generative Language API) 直接理解整段原始標題。
 * 跟 iTunes/本地解析的規則式清理不同，這裡把「完整原始標題」直接交給 AI 判斷，
 * 讓 AI 自行分辨哪些是歌手/歌名/專輯/年份，哪些是戲劇名、集數標籤、MV 品牌等裝飾性雜訊。
 *
 * @return {artistName, trackName, collectionName, releaseDate} 形狀的物件；失敗則回傳 null
 */
async function fetchGeminiInfo(originalTitle, apiKey, model) {
  if (!apiKey) return null;

  try {
    const prompt = "你是一個音樂資訊助手。請閱讀以下 YouTube/音樂平台的通知標題，" +
      "判斷其中真正的歌手、歌名、專輯、發行年份，忽略戲劇名、集數標籤、MV 品牌、發行方、頻道名稱等裝飾性文字。\n" +
      `標題：${originalTitle}\n\n` +
      "請只輸出 JSON，格式為 {\"artist\": \"\", \"track\": \"\", \"album\": \"\", \"year\": \"\"}，" +
      "若不確定或找不到某個欄位，該欄位請填空字串，不要編造不存在的資訊。";

    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(apiKey)}`;
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { responseMimeType: "application/json", temperature: 0.1 }
      })
    });

    if (!response.ok) {
      const errText = await response.text().catch(() => "");
      console.warn(`Gemini API Failed with status: ${response.status}, ${errText}`);
      return null;
    }

    const data = await response.json();
    const contentText = data && data.candidates && data.candidates[0] &&
      data.candidates[0].content && data.candidates[0].content.parts &&
      data.candidates[0].content.parts[0] && data.candidates[0].content.parts[0].text;

    if (!contentText) {
      console.warn("Gemini API: candidate 內容為空");
      return null;
    }

    const parsed = JSON.parse(contentText);
    const track = (parsed.track || "").trim();
    if (!track) {
      console.warn("Gemini API: AI 找不到歌名");
      return null;
    }

    console.log(`Gemini API Match: ${(parsed.artist || "").trim()} - ${track}`);
    return {
      artistName: (parsed.artist || "").trim(),
      trackName: track,
      collectionName: (parsed.album || "").trim(),
      releaseDate: (parsed.year || "").trim()
    };
  } catch (err) {
    console.warn("Gemini API Error", err);
    return null;
  }
}

/** Popup 的「測試 API Key」按鈕用：只驗證金鑰與模型名稱是否可用，不影響播報邏輯。 */
async function testGeminiKey(apiKey, model) {
  if (!apiKey) return { ok: false, message: "尚未輸入 API Key" };
  try {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(apiKey)}`;
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ contents: [{ parts: [{ text: "請回覆「OK」兩個字。" }] }] })
    });
    if (!response.ok) {
      const errText = await response.text().catch(() => "");
      return { ok: false, message: `HTTP ${response.status}: ${errText.slice(0, 150)}` };
    }
    return { ok: true, message: "連線成功" };
  } catch (err) {
    return { ok: false, message: String(err) };
  }
}

/**
 * 以字詞重疊率 (Jaccard Similarity) 估算兩段文字的相似度，用來從多筆候選結果中
 * 挑出與查詢字串最相近的一筆，取代過去「無條件相信第一筆結果」的作法。
 * 中文以單字為 token、英數字以連續字元組成 token，兩者混合計算。
 */
function titleSimilarity(a, b) {
  const tokensA = tokenize(a);
  const tokensB = tokenize(b);
  if (tokensA.size === 0 || tokensB.size === 0) return 0;

  let intersection = 0;
  for (const token of tokensA) {
    if (tokensB.has(token)) intersection++;
  }
  const union = new Set([...tokensA, ...tokensB]).size;
  return intersection / union;
}

function tokenize(text) {
  const tokens = new Set();
  let word = "";
  for (const ch of text.toLowerCase()) {
    const code = ch.codePointAt(0);
    if (code >= 0x4E00 && code <= 0x9FFF) {
      if (word) { tokens.add(word); word = ""; }
      tokens.add(ch);
    } else if (/[a-z0-9]/i.test(ch)) {
      word += ch;
    } else {
      if (word) { tokens.add(word); word = ""; }
    }
  }
  if (word) tokens.add(word);
  return tokens;
}

function shouldIgnore(text, ignoreListStr) {
  if (!ignoreListStr) return false;
  // 預設關鍵字：直播, 精華, 全集, 合集 (如果使用者清除設定，這裡可以做最後防線，但主要靠 storage)
  const keywords = ignoreListStr.split(/,|，/).map(k => k.trim()).filter(k => k);
  for (const keyword of keywords) {
    if (text.includes(keyword)) return true;
  }
  return false;
}

function addToHistory(songTitle, url) {
  chrome.storage.local.get(['history'], (result) => {
    let history = result.history || [];
    // 避免重複連續紀錄相同的歌 (Optional)
    const prevTitle = typeof history[0] === 'string' ? history[0] : history[0]?.title;
    if (history.length > 0 && prevTitle === songTitle) return;

    history.unshift({ title: songTitle, url: url || null });
    if (history.length > 20) {
      history = history.slice(0, 20);
    }
    chrome.storage.local.set({ history: history });
  });
}
