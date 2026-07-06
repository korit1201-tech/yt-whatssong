// popup.js - 設定頁面邏輯

document.addEventListener('DOMContentLoaded', () => {
    const enableToggle = document.getElementById('enableToggle');
    const smartToggle = document.getElementById('smartToggle');
    const itunesToggle = document.getElementById('itunesToggle');
    const voiceSelect = document.getElementById('voiceSelect');
    const volumeRange = document.getElementById('volumeRange');
    const rateRange = document.getElementById('rateRange');
    const statusText = document.getElementById('statusText');
    const volumeValue = document.getElementById('volumeValue');
    const rateValue = document.getElementById('rateValue');
    const djModeToggle = document.getElementById('djModeToggle');
    const resumeToggle = document.getElementById('resumeToggle');
    const aiToggle = document.getElementById('aiToggle');
    const geminiApiKey = document.getElementById('geminiApiKey');
    const geminiModelInput = document.getElementById('geminiModelInput');
    const testApiKeyBtn = document.getElementById('testApiKeyBtn');
    const apiStatus = document.getElementById('apiStatus');

    // 載入目前設定
    const notificationToggle = document.getElementById('notificationToggle');
    const ignoreList = document.getElementById('ignoreList');
    const historyContainer = document.getElementById('historyContainer');
    const ttsSettingsCard = document.getElementById('ttsSettings');
    // djModeToggle already declared at top
    // edgeVoiceSelect removed

    // 載入目前設定
    chrome.storage.local.get(['isEnabled', 'smartTitleParsing', 'useItunesApi', 'useAiApi', 'geminiApiKey', 'geminiModel', 'selectedVoiceURI', 'volume', 'playbackSpeed', 'notificationMode', 'ignoreList', 'history', 'djMode', 'announceOnResume'], (result) => {
        enableToggle.checked = result.isEnabled !== false;
        smartToggle.checked = result.smartTitleParsing !== false;
        itunesToggle.checked = result.useItunesApi !== false;
        notificationToggle.checked = result.notificationMode === true;
        ignoreList.value = result.ignoreList || "直播, 精華, 全集, 合集";
        if (djModeToggle) djModeToggle.checked = result.djMode !== false;
        if (resumeToggle) resumeToggle.checked = result.announceOnResume !== false;
        if (aiToggle) aiToggle.checked = result.useAiApi === true;
        if (geminiApiKey) geminiApiKey.value = result.geminiApiKey || "";
        if (geminiModelInput) geminiModelInput.value = result.geminiModel || "gemma-4-31b-it";

        updateUIState();

        const vol = result.volume !== undefined ? result.volume : 0.3;
        const rate = result.playbackSpeed || 1.2;

        // 音量映射 (Value -> Index)
        // 10% (0.1) -> 0
        // 30% (0.3) -> 1
        // 50% (0.5) -> 2
        // 100% (1.0) -> 3
        let volIndex = 1; // Default 30%
        if (vol <= 0.15) volIndex = 0;
        else if (vol <= 0.35) volIndex = 1;
        else if (vol <= 0.6) volIndex = 2;
        else volIndex = 3;

        if (volumeRange) volumeRange.value = volIndex;
        if (rateRange) rateRange.value = rate;

        updateVolumeDisplay(volIndex);
        updateRateDisplay(rate);

        populateVoices(result.selectedVoiceURI);
        renderHistory(result.history || []);
    });

    // Event Listeners
    enableToggle.addEventListener('change', () => {
        const isEnabled = enableToggle.checked;
        chrome.storage.local.set({ isEnabled: isEnabled });
        updateUIState();
    });

    smartToggle.addEventListener('change', () => {
        chrome.storage.local.set({ smartTitleParsing: smartToggle.checked });
    });

    itunesToggle.addEventListener('change', () => {
        chrome.storage.local.set({ useItunesApi: itunesToggle.checked });
    });

    notificationToggle.addEventListener('change', () => {
        const isNotifyMode = notificationToggle.checked;
        chrome.storage.local.set({ notificationMode: isNotifyMode });
        updateUIState();
    });

    ignoreList.addEventListener('change', () => {
        chrome.storage.local.set({ ignoreList: ignoreList.value });
    });

    if (djModeToggle) {
        djModeToggle.addEventListener('change', () => {
            chrome.storage.local.set({ djMode: djModeToggle.checked });
        });
    }

    if (resumeToggle) {
        resumeToggle.addEventListener('change', () => {
            chrome.storage.local.set({ announceOnResume: resumeToggle.checked });
        });
    }

    if (aiToggle) {
        aiToggle.addEventListener('change', () => {
            chrome.storage.local.set({ useAiApi: aiToggle.checked });
        });
    }

    if (geminiApiKey) {
        geminiApiKey.addEventListener('change', () => {
            chrome.storage.local.set({ geminiApiKey: geminiApiKey.value.trim() });
        });
    }

    if (geminiModelInput) {
        geminiModelInput.addEventListener('change', () => {
            chrome.storage.local.set({ geminiModel: geminiModelInput.value.trim() || "gemma-4-31b-it" });
        });
    }

    if (testApiKeyBtn) {
        testApiKeyBtn.addEventListener('click', () => {
            const apiKey = (geminiApiKey && geminiApiKey.value.trim()) || "";
            const model = (geminiModelInput && geminiModelInput.value.trim()) || "gemma-4-31b-it";

            if (apiStatus) {
                apiStatus.textContent = "測試中...";
                apiStatus.style.color = "#888";
            }

            chrome.runtime.sendMessage({ type: 'TEST_AI_KEY', apiKey, model }, (res) => {
                if (!apiStatus) return;
                if (chrome.runtime.lastError || !res) {
                    apiStatus.textContent = "測試失敗：擴充功能通訊錯誤";
                    apiStatus.style.color = "#d32f2f";
                    return;
                }
                apiStatus.textContent = res.ok ? `✅ ${res.message}` : `❌ ${res.message}`;
                apiStatus.style.color = res.ok ? "#4caf50" : "#d32f2f";
            });
        });
    }

    voiceSelect.addEventListener('change', () => {
        chrome.storage.local.set({ selectedVoiceURI: voiceSelect.value });
    });

    volumeRange.addEventListener('input', () => {
        const index = parseInt(volumeRange.value);
        // Map Index -> Value
        const volMap = [0.1, 0.3, 0.5, 1.0];
        const val = volMap[index];
        chrome.storage.local.set({ volume: val });
        updateVolumeDisplay(index);
    });

    rateRange.addEventListener('input', () => {
        const val = parseFloat(rateRange.value);
        chrome.storage.local.set({ playbackSpeed: val });
        updateRateDisplay(val);
    });

    function updateVolumeDisplay(index) {
        const volTextMap = ["10%", "30%", "50%", "100%"];
        if (volumeValue) volumeValue.textContent = volTextMap[index];
    }

    function updateRateDisplay(val) {
        if (rateValue) rateValue.textContent = `${val.toFixed(1)}x`;
    }

    function updateUIState() {
        const isEnabled = enableToggle.checked;
        const isNotify = notificationToggle.checked;

        // 1. 處理狀態文字
        if (!isEnabled) {
            statusText.textContent = "服務已停用";
            statusText.style.color = "gray";
        } else if (isNotify) {
            statusText.textContent = "靜音通知模式 (不朗讀)";
            statusText.style.color = "#ff9800"; // Orange
        } else {
            statusText.textContent = "服務運作中 (語音播報)";
            statusText.style.color = "#4caf50"; // Green
        }

        // 2. 處理 TTS 設定區塊的啟用/停用
        // 如果服務關閉，或者開啟了靜音模式，則 TTS 區塊應該被禁用 (變灰)
        if (!isEnabled || isNotify) {
            ttsSettingsCard.classList.add('disabled');
        } else {
            ttsSettingsCard.classList.remove('disabled');
        }
    }

    function renderHistory(history) {
        historyContainer.innerHTML = '';
        if (!history || history.length === 0) {
            historyContainer.innerHTML = '<div style="color: #999; text-align: center;">尚無紀錄</div>';
            return;
        }

        history.forEach(entry => {
            // 相容舊格式（純字串）與新格式（{ title, url }）
            const title = typeof entry === 'string' ? entry : entry.title;
            const url = typeof entry === 'string' ? null : entry.url;

            const row = document.createElement('div');
            row.style.borderBottom = '1px solid #eee';
            row.style.padding = '3px 0';
            row.style.whiteSpace = 'nowrap';
            row.style.overflow = 'hidden';
            row.style.textOverflow = 'ellipsis';
            row.title = title; // Tooltip

            if (url) {
                const link = document.createElement('a');
                link.href = url;
                link.target = '_blank';
                link.rel = 'noopener';
                link.textContent = title;
                link.className = 'history-link';
                row.appendChild(link);
            } else {
                row.textContent = title;
            }

            historyContainer.appendChild(row);
        });
    }

    function populateVoices(savedURI) {
        voiceSelect.innerHTML = '';

        chrome.tts.getVoices((voices) => {
            if (voices.length === 0) {
                // chrome.tts 通常不會像 speechSynthesis 那樣非同步載入慢，但防呆
                setTimeout(() => populateVoices(savedURI), 100);
                return;
            }

            // chrome.tts returns objects with voiceName, lang, etc.
            const zhVoices = voices.filter(v =>
                (v.lang && (v.lang.includes('zh') || v.lang.includes('TW') || v.lang.includes('CN'))) ||
                (v.voiceName && (v.voiceName.includes('Chinese') || v.voiceName.includes('Taiwan')))
            );

            // 尋找預設值：優先找 Google 國語（臺灣）
            let targetURI = savedURI;
            // 如果從來沒選過，或者之前選的是 Edge TTS (現在被移除了)，就重置為 Google
            if (!targetURI || targetURI.startsWith("edge:")) {
                const googleTW = zhVoices.find(v => v.voiceName.includes("Google") && (v.voiceName.includes("TW") || v.voiceName.includes("臺灣")));
                if (googleTW) {
                    targetURI = googleTW.voiceName;
                    chrome.storage.local.set({ selectedVoiceURI: targetURI });
                } else if (zhVoices.length > 0) {
                    // 找不到 Google 就隨便找一個中文
                    targetURI = zhVoices[0].voiceName;
                    chrome.storage.local.set({ selectedVoiceURI: targetURI });
                }
            }

            // 建立選項
            // const systemGroup = document.createElement('optgroup'); // 不需要分組了，因為只有一組
            // systemGroup.label = "系統/裝置語音";

            zhVoices.forEach(voice => {
                const option = document.createElement('option');
                option.value = voice.voiceName;
                if (voice.voiceName === targetURI) option.selected = true;

                const isLocal = voice.remote === false ? "💻" : "🌐";
                // 標示 Google 語音
                const isGoogle = voice.voiceName.includes("Google") ? " (Google)" : "";

                option.textContent = `${isLocal} ${voice.voiceName}${isGoogle}`;
                voiceSelect.appendChild(option);
            });
        });
    }

    function updateStatusText(isEnabled) {
        statusText.textContent = isEnabled ? "服務運作中" : "服務已停用";
        statusText.style.color = isEnabled ? "green" : "gray";
    }
});
