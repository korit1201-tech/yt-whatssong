// background.js - 重構為 AI DJ 核心協調者
// 負責：監聽 Content Script 訊息 -> 調用 iTunes API -> 選擇適當的開場白 (模板) -> 控制 TTS 與音量

// 預設設定值
const DEFAULT_SETTINGS = {
  isEnabled: true,
  smartTitleParsing: true,
  useItunesApi: true,
  djMode: false,
  volume: 1.0,
  playbackSpeed: 1.2,
  selectedVoiceURI: null,
  notificationMode: false,
  ignoreList: "直播,精華,全集,合集"
};

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
    handleNewSongProcess(request.title, sender.tab.id);
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

async function handleNewSongProcess(rawTitle, tabId) {
  const settings = await chrome.storage.local.get(['isEnabled', 'ignoreList', 'smartTitleParsing', 'useItunesApi', 'djMode', 'geminiApiKey', 'geminiModel', 'volume', 'playbackSpeed', 'selectedVoiceURI']);

  if (!settings.isEnabled) return;
  if (shouldIgnore(rawTitle, settings.ignoreList)) return;

  addToHistory(rawTitle);

  // 1. 標題清理
  let searchTitle = rawTitle;
  if (settings.smartTitleParsing) {
    searchTitle = cleanTitle(rawTitle);
  }

  // 2. 獲取 Metadata
  let metadata = null;
  if (settings.useItunesApi) {
    metadata = await fetchItunesInfo(searchTitle);
  }

  // 3. 生成文案
  let script = "";

  // 嘗試解析 metadata (如果 iTunes 沒抓到，嘗試從標題拆解)
  let finalMetadata = metadata;
  if (!finalMetadata) {
    // 簡單嘗試拆解 "Artist - Title"
    const parts = searchTitle.split('-').map(s => s.trim());
    if (parts.length >= 2) {
      finalMetadata = {
        artistName: parts[0],
        trackName: parts.slice(1).join(' '),
        collectionName: "", // Album
        releaseDate: ""    // Year
      };
    } else {
      // 無法拆解，只好把整串當歌名，歌手留空
      finalMetadata = {
        artistName: "",
        trackName: searchTitle,
        collectionName: "",
        releaseDate: ""
      };
    }
  }

  // 根據 DJ 模式決定文案風格
  if (settings.djMode) {
    script = generateDJScript(finalMetadata);
  } else {
    // 簡單模式：只報歌名 (與歌手)
    const S = finalMetadata.artistName || "";
    const N = finalMetadata.trackName || searchTitle; // Fallback to raw title if trackName is empty
    if (S) {
      script = `現在播放的是，${S} 的 ${N}。`;
    } else {
      script = `現在播放的是，${N}。`;
    }
  }

  // 4. 開始 DJ 秀或普通播報
  // 確保使用設定值，若無設定則給予適當預設值
  const userRate = settings.playbackSpeed !== undefined ? parseFloat(settings.playbackSpeed) : 1.2;
  const userVolume = settings.volume !== undefined ? parseFloat(settings.volume) : 1.0;

  // 取得語音設定 (若無設定，chrome.tts 會用系統預設)
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

  // === 使用系統內建語音 (Chrome TTS) ===
  console.log(`[TTS Config] Voice: ${selectedVoice || 'System Default'}, Rate: ${userRate}, Volume: ${userVolume}, DJ Mode: ${settings.djMode}`);

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

// 50條 AI DJ 隨機介紹詞庫
const DJ_TEMPLATES = [
  "接下來這首，來自 #S 的經典作品，#N。",
  "讓 #S 的聲音陪你度過這個時刻，送上 #N。",
  "帶你回到 #Y 年，那時候大街小巷都在放 #S 的 #N。", // Need Year
  "這首 #N 收錄在 #D 專輯中，是 #S 非常動聽的一首歌。", // Need Album
  "轉換一下心情，聽聽 #S 帶來的 #N。",
  "深夜了，適合讓 #S 的 #N 沉澱一下思緒。", // Context-aware?
  "還記得 #Y 年你在做什麼嗎？來回味這首 #S 的 #N。", // Need Year
  "這是 #S 在 #D 專輯裡我最愛的一首，#N。", // Need Album
  "耳朵借給我，這是 #S 的 #N。",
  "沒什麼好說的，聽就對了，#S 演唱的 #N。",
  "時光倒流到 #Y 年，#S 推出了這首 #N，現在聽還是很感動。", // Need Year
  "來自 #S 的 #N，收錄在 #D 這張專輯裡。", // Need Album
  "讓音樂連結我們，送上 #S 的 #N。",
  "不管過多久，#S 的 #N 總是能打動人心。",
  "#Y 年的金曲回顧，這是 #S 的 #N。", // Need Year
  "在 #D 專輯中，#S 用這首 #N 征服了大家的耳朵。", // Need Album
  "這是屬於 #S 的時刻，請欣賞 #N。",
  "下一首，讓我們沉浸在 #S 的 #N 之中。",
  "#Y 年的回憶殺，#S 的 #N，你一定會唱。", // Need Year
  "推薦這首 #S 的 #N，出自經典專輯 #D。", // Need Album
  "心情不好的時候，聽聽 #S 的 #N 最療癒。",
  "閉上眼睛，感受 #S 在 #N 裡的情感。",
  "這是 #S 很有代表性的一首歌，#N。",
  "回到 #Y 年那個夏天，聽著 #S 的 #N。", // Need Year
  "今天的私房推薦，#S 的 #N，收錄在 #D 專輯。", // Need Album
  "這旋律一出來你就知道是誰，#S 的 #N。",
  "來自 #Y 年的聲音，#S 的 #N。", // Need Year
  "#S 的 #D 專輯中，這首 #N 絕對不能錯過。", // Need Album
  "讓 #S 的 #N 帶走你的煩惱。",
  "這是 #Y 年 #S 在 #D 專輯中的主打歌，#N。", // Need Year & Album
  "節奏輕快起來，這是 #S 的 #N。",
  "很久沒聽這首了吧？#S 的 #N。",
  "獻給所有有故事的人，#S 的 #N。",
  "#S 的情歌總是很對味，這首 #N 也不例外。",
  "讓我們一起重溫 #D 專輯裡的這首好歌，#S 的 #N。", // Need Album
  "#Y 年，我們一起聽過的 #S 的 #N。", // Need Year
  "馬上為您送上，#S 的 #N。",
  "這聲音太迷人了，#S 的 #N。",
  "來自 #D 專輯，#S 的 #N。", // Need Album
  "如果你還沒聽過 #S 的 #N，現在仔細聽。",
  "#Y 年發行的好歌，#S 的 #N。", // Need Year
  "這是 #S 的 #N，希望能溫暖你的耳朵。",
  "經典永遠不嫌老，#S 的 #N。",
  "在這個城市的一角，我們聽 #S 的 #N。",
  "#S 在 #Y 年留下的美好回憶，#N。", // Need Year
  "收錄在 #D 裡的隱藏好歌，#S 的 #N。", // Need Album
  "準備好了嗎？#S 要帶來這首 #N。",
  "這張 #D 專輯真的很強，特別是這首 #S 的 #N。", // Need Album
  "繼續來聽，這首不錯喔，#S 的 #N。",
  "最後推薦這首 #Y 年的作品，#S 的 #N，希望你喜歡。" // Need Year
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

  // 過濾可用範本
  const availableTemplates = DJ_TEMPLATES.filter(tpl => {
    // 如果沒有歌手名，不能用 #S (幾乎所有範本都要 #S，所以如果沒歌手，我們只能用後備方案)
    if (!S && tpl.includes("#S")) return false;
    // 如果沒有專輯名，不能用 #D
    if (!D && tpl.includes("#D")) return false;
    // 如果沒有年份，不能用 #Y
    if (!Y && tpl.includes("#Y")) return false;
    return true;
  });

  // 如果沒有可用範本 (例如沒有歌手名)，這時候只好回退到最簡單的報幕
  if (availableTemplates.length === 0) {
    if (S) return `現在播放的是，${S} 的 ${N}。`;
    return `現在播放的是，${N}。`;
  }

  // 隨機選一個
  const tpl = availableTemplates[Math.floor(Math.random() * availableTemplates.length)];

  // 替換標籤
  return tpl.replace(/#S/g, S)
    .replace(/#N/g, N)
    .replace(/#D/g, D)
    .replace(/#Y/g, Y);
}

function cleanTitle(title) {
  return title.replace(/(\(.*?Official.*?\))|(\[.*?MV.*?\])|(【.*?HD.*?】)/gi, "")
    .replace(/\(Official Video\)/gi, "")
    .replace(/\[MV\]/gi, "")
    .replace(/^\s*[-]\s*/, "").trim();
}

async function fetchItunesInfo(term) {
  try {
    const encodedQuery = encodeURIComponent(term);
    const url = `https://itunes.apple.com/search?term=${encodedQuery}&entity=song&limit=1&country=TW&lang=zh_TW`;
    console.log(`Searching iTunes: ${url}`);

    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

    const data = await response.json();
    if (data.resultCount > 0) {
      const result = data.results[0];
      // Return full object for Content Script to parse
      return result;
    }
    return null;
  } catch (err) {
    console.warn("iTunes API Fetch Error", err);
    return null;
  }
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

function addToHistory(songTitle) {
  chrome.storage.local.get(['history'], (result) => {
    let history = result.history || [];
    // 避免重複連續紀錄相同的歌 (Optional)
    if (history.length > 0 && history[0] === songTitle) return;

    history.unshift(songTitle);
    if (history.length > 20) {
      history = history.slice(0, 20);
    }
    chrome.storage.local.set({ history: history });
  });
}
