// background.js - Service Worker
// 負責處理全域事件與初始化設定

chrome.runtime.onInstalled.addListener(() => {
  console.log("YT WhatsSong 套件已安裝");

  // 初始化預設設定
  chrome.storage.local.set({
    isEnabled: true,          // 總開關
    smartTitleParsing: true,  // 智慧標題解析 (去除括號)
    announceAds: false,       // 是否播報廣告 (預設不播報)
    playbackSpeed: 1.0,       // TTS 語速
    volume: 0.3,              // TTS 音量 (預設 30%)
    useItunesApi: true        // iTunes API 優化
  }, () => {
    console.log("預設設定已初始化");
  });
});

// 監聽來自 Content Script 的請求
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.type === 'FETCH_ITUNES_INFO') {
    fetchItunesInfo(request.query)
      .then(result => sendResponse({ success: true, data: result }))
      .catch(error => sendResponse({ success: false, error: error.message }));
    return true; // 保持通道開啟以進行非同步回應
  }
});

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
      return `${result.artistName} - ${result.trackName}`;
    }
    return null;
  } catch (err) {
    console.warn("iTunes API Fetch Error", err);
    return null;
  }
}
