// content.js - 注入到 YouTube 頁面的主要邏輯
// 負責監聽 DOM 變化、通知 Background、以及執行音量控制 (Ducking)

console.log("YT WhatsSong: AI DJ Content Script Loaded");

let lastTitle = "";
let config = { isEnabled: true };

// 更新設定
chrome.storage.onChanged.addListener((changes, namespace) => {
    if (namespace === 'local' && changes.isEnabled) {
        config.isEnabled = changes.isEnabled.newValue;
    }
});

chrome.storage.local.get(['isEnabled'], (result) => {
    config.isEnabled = result.isEnabled !== false;
});

// 啟動 DOM 監聽器
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

    // 過濾無效標題
    if (currentTitle === "YouTube" || currentTitle === "YouTube Music" || currentTitle === "") {
        return;
    }

    if (currentTitle !== lastTitle) {
        console.log(`偵測到新標題: ${currentTitle}`);
        lastTitle = currentTitle;

        // 廣告檢測
        if (detectAds()) {
            console.log("偵測到廣告，跳過通知");
            return;
        }

        // 通知 Background 處理 (AI DJ 核心)
        try {
            chrome.runtime.sendMessage({
                type: 'PROCESS_NEW_SONG',
                title: currentTitle
            });
        } catch (e) {
            console.warn("YT WhatsSong: Extension context invalidated. Please refresh the page.");
        }
    }
}

function detectAds() {
    const adShowing = document.querySelector('.ad-showing');
    const adText = document.querySelector('.ytp-ad-text');
    return !!(adShowing || adText);
}

// 監聽 Background 的指令 (音量控制)
let originalVolume = 1.0;
let isDucking = false;

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'DUCK_ON') {
        startDucking();
    } else if (request.action === 'DUCK_OFF') {
        stopDucking();
    }
});

function startDucking() {
    if (isDucking) return; // 避免重複降低音量

    const video = document.querySelector('video');
    if (video) {
        originalVolume = video.volume;
        // 如果偵測到音量已經過小(可能是不正常的狀態)，就假設原音量是 1.0 或者至少是上次的紀錄
        if (originalVolume < 0.25 && originalVolume > 0) {
            // Do nothing regarding originalVolume update to be safe? 
            // 還是維持目前邏輯，但只壓低
        }

        video.volume = Math.max(0.1, originalVolume * 0.2);
        isDucking = true;
        console.log(`[DJ Mode] Ducking ON: Volume ${originalVolume} -> ${video.volume}`);
    }
}

function stopDucking() {
    if (!isDucking) return;

    const video = document.querySelector('video');
    if (video) {
        // 恢復音量
        video.volume = originalVolume;
        isDucking = false;
        console.log(`[DJ Mode] Ducking OFF: Restored to ${originalVolume}`);
    }
}
