// content.js - 注入到 YouTube 頁面的主要邏輯
// 負責監聽 DOM 變化、解析歌名、控制播放與 TTS 播報

console.log("YT WhatsSong: Content Script Loaded");

let lastTitle = "";

// 讀取設定
let config = {
    isEnabled: true,
    smartTitleParsing: true,
    useItunesApi: true,
    volume: 0.3 // Default 30%
};

// 更新設定
chrome.storage.onChanged.addListener((changes, namespace) => {
    if (namespace === 'local') {
        for (let key in changes) {
            config[key] = changes[key].newValue;
        }
    }
});

// 初始化讀取設定
chrome.storage.local.get(['isEnabled', 'smartTitleParsing', 'useItunesApi', 'selectedVoiceURI', 'volume', 'playbackSpeed'], (result) => {
    config = Object.assign(config, result);
});

// 啟動 DOM 監聽器
// [修正] 移除 Debounce Timer，改為即時響應
const observer = new MutationObserver((mutations) => {
    if (!config.isEnabled) return;
    checkTitleChange();
});

// 開始觀察
const targetNode = document.querySelector('body');
if (targetNode) {
    observer.observe(targetNode, {
        childList: true,
        subtree: true,
        characterData: true
    });
}

function checkTitleChange() {
    let currentTitle = "";

    // 判斷是 YouTube 還是 YouTube Music
    if (window.location.hostname.includes("music.youtube.com")) {
        const titleEl = document.querySelector('ytmusic-player-bar .title');
        if (titleEl) currentTitle = titleEl.textContent;
    } else {
        const titleEl = document.querySelector('h1.ytd-video-primary-info-renderer');
        if (titleEl) {
            currentTitle = titleEl.textContent;
        } else {
            currentTitle = document.title.replace(" - YouTube", "");
        }
    }

    if (!currentTitle) return;

    currentTitle = currentTitle.trim();

    // 過濾無效標題，避免在頁面載入初期抓到 "YouTube"
    if (currentTitle === "YouTube" || currentTitle === "YouTube Music" || currentTitle === "") {
        return;
    }

    if (currentTitle !== lastTitle) {
        console.log(`偵測到新標題: ${currentTitle}`);
        lastTitle = currentTitle;
        handleNewSong(currentTitle);
    }
}

async function handleNewSong(rawTitle) {
    // 1. 廣告偵測
    if (detectAds()) {
        console.log("偵測到廣告，跳過播報");
        return;
    }

    // 2. [關鍵] 立即暫停影片
    const video = document.querySelector('video');
    if (video && !video.paused) {
        video.pause();
        console.log("影片已暫停");
    }

    let titleToSpeak = rawTitle;

    // 3. iTunes 優化 (若啟用)
    if (config.useItunesApi) {
        const optimizedTitle = await getItunesTitle(rawTitle);
        if (optimizedTitle) {
            console.log(`iTunes 優化成功: ${optimizedTitle}`);
            titleToSpeak = optimizedTitle;
        } else {
            // 降級
            if (config.smartTitleParsing) {
                titleToSpeak = parseTitle(rawTitle);
            }
        }
    } else {
        // 4. 本地解析
        if (config.smartTitleParsing) {
            titleToSpeak = parseTitle(rawTitle);
        }
    }

    // 5. 播報
    speak(titleToSpeak, () => {
        // 6. 恢復播放 (callback)
        if (video) {
            video.play();
            console.log("影片恢復播放");
        }
    });
}

function detectAds() {
    const adShowing = document.querySelector('.ad-showing');
    const adText = document.querySelector('.ytp-ad-text');
    return !!(adShowing || adText);
}

// 代理請求到 Background，並進行結果驗證
function getItunesTitle(originalTitle) {
    return new Promise((resolve) => {
        const cleanQuery = parseTitle(originalTitle);

        // 定義查詢與驗證的內部函式
        const fetchAndValidate = (query, sourceTitle) => {
            return new Promise((res) => {
                chrome.runtime.sendMessage({
                    type: 'FETCH_ITUNES_INFO',
                    query: query
                }, (response) => {
                    if (response && response.success && response.data) {
                        // [新增] 智能過濾：驗證返回結果是否與原標題相關
                        if (isMatchValid(sourceTitle, response.data)) {
                            res(response.data);
                        } else {
                            console.log(`iTunes 匹配度過低，捨棄結果: [${response.data}] vs [${sourceTitle}]`);
                            res(null);
                        }
                    } else {
                        res(null);
                    }
                });
            });
        };

        // 1. 首次嘗試
        fetchAndValidate(cleanQuery, originalTitle).then(result => {
            if (result) {
                resolve(result);
            } else {
                // 2. Smart Retry
                const bracketsRegex = /【(.*?)】/;
                const match = originalTitle.match(bracketsRegex);
                if (match && match[1]) {
                    const smartKeyword = match[1].trim();
                    // 確保關鍵字有效且跟第一次查詢不同
                    if (smartKeyword && smartKeyword !== cleanQuery && smartKeyword.length > 1) {
                        console.log(`iTunes 重試: ${smartKeyword}`);
                        fetchAndValidate(smartKeyword, originalTitle).then(retryResult => {
                            resolve(retryResult);
                        });
                        return;
                    }
                }
                resolve(null);
            }
        });
    });
}

// [新增] 驗證匹配相關性
function isMatchValid(original, result) {
    const normalize = (str) => str.toLowerCase().replace(/[^\w\s\u4e00-\u9fa5]/g, '');
    const tokenize = (str) => normalize(str).split(/\s+/).filter(x => x);

    const tokensOrig = new Set(tokenize(original));
    const tokensRes = new Set(tokenize(result));

    if (tokensRes.size === 0) return false;

    let intersect = 0;
    tokensRes.forEach(t => {
        if (tokensOrig.has(t)) intersect++;
    });

    // 計算雙向覆蓋率
    // 1. Result 在 Original 中的覆蓋率 (避免 Result 包含 Original 沒有的怪字)
    const resCoverage = intersect / tokensRes.size;

    // 2. Original 在 Result 中的覆蓋率 (避免 Result 太短只命中 Original 的一小部分)
    // 注意：Original 可能很長 (包含 Video, Official, Lyrics 等)，所以這個權重可以低一點
    const origSize = tokensOrig.size;
    const origCoverage = origSize > 0 ? intersect / origSize : 0;

    console.log(`匹配驗證: [${result}] vs [${original}] -> Coverage: ${resCoverage.toFixed(2)}, Reverse: ${origCoverage.toFixed(2)}`);

    // 寬鬆判定：只要有一方覆蓋率夠高 (例如 > 50%) 即視為相關
    // 例如 "Numb" vs "Numb - Linkin Park" -> origCoverage 1.0 (Pass)
    // 例如 "Official Video Song" vs "Song" -> resCoverage 1.0 (Pass)
    return resCoverage >= 0.5 || origCoverage >= 0.5;
}

function parseTitle(title) {
    let result = title;
    // 移除常見括號雜訊
    result = result.replace(/(\(.*?Official.*?\))|(\[.*?MV.*?\])|(【.*?HD.*?】)/gi, "");
    result = result.replace(/\(Official Video\)/gi, "");
    result = result.replace(/\[MV\]/gi, "");
    return result.replace(/^\s*[-]\s*/, "").trim();
}

function speak(text, onEndCallback) {
    if (!text) {
        if (onEndCallback) onEndCallback();
        return;
    }

    console.log(`正在播報: ${text}`);
    const utterance = new SpeechSynthesisUtterance("正在播放：" + text);
    // 使用 config.volume，若未定義則預設 0.3
    utterance.volume = config.volume !== undefined ? config.volume : 0.3;
    utterance.rate = config.playbackSpeed || 1.0;

    // 設定語音 (優先尋找 Google 國語)
    const voices = window.speechSynthesis.getVoices();
    let selectedVoice = null;

    if (config.selectedVoiceURI) {
        selectedVoice = voices.find(v => v.voiceURI === config.selectedVoiceURI);
    }

    if (!selectedVoice) {
        selectedVoice = voices.find(v => (v.name.includes("Google") || v.name.includes("Microsoft")) && (v.lang.includes("TW") || v.lang.includes("zh-TW") || v.name.includes("臺灣")));
    }

    if (selectedVoice) {
        utterance.voice = selectedVoice;
        utterance.lang = selectedVoice.lang;
    } else {
        utterance.lang = 'zh-TW';
    }

    utterance.onend = () => {
        if (onEndCallback) onEndCallback();
    };

    utterance.onerror = (e) => {
        console.error("TTS Error", e);
        if (onEndCallback) onEndCallback();
    };

    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
}
