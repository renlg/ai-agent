/**
 * Chat UI Controller
 * Handles DOM manipulation, Java <-> JS bridge, session management
 */
(function () {
    'use strict';

    /* ===== State ===== */
    var TOKEN_BUDGET = 200000;
    var isProcessing = false;
    var currentAssistantEl = null;
    var currentContentEl = null;
    var accumulatedContent = '';
    var messageQueue = [];
    var ready = false;
    var totalUsedTokens = 0;

    /* ===== DOM refs ===== */
    var messagesArea, welcomeScreen, messageInput, sendBtn, inputWrapper;
    var tabList, modelDropdown, modelDropdownTrigger, modelDropdownMenu, modelDropdownLabel;
    var modeDropdown, modeDropdownTrigger, modeDropdownMenu, modeDropdownLabel;
    var newSessionBtn, clearBtn;

    /* ===== Init ===== */
    document.addEventListener('DOMContentLoaded', function () {
        messagesArea = document.getElementById('messagesArea');
        welcomeScreen = document.getElementById('welcomeScreen');
        messageInput = document.getElementById('messageInput');
        sendBtn = document.getElementById('sendBtn');
        tabList = document.getElementById('tabList');
        modelDropdown = document.getElementById('modelDropdown');
        modelDropdownTrigger = document.getElementById('modelSelectTrigger');
        modelDropdownMenu = document.getElementById('modelDropdownMenu');
        modelDropdownLabel = document.getElementById('modelDropdownLabel');
        newSessionBtn = document.getElementById('newSessionBtn');
        clearBtn = document.getElementById('clearBtn');
        modeDropdown = document.getElementById('modeDropdown');
        modeDropdownTrigger = document.getElementById('modeDropdownTrigger');
        modeDropdownMenu = document.getElementById('modeDropdownMenu');
        modeDropdownLabel = document.getElementById('modeDropdownLabel');
        inputWrapper = document.querySelector('.input-wrapper');

        if (window.__TAIW_THEME__ === 'dark') {
            document.body.classList.add('dark');
        }

        messageInput.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        });

        messageInput.addEventListener('input', autoResize);

        sendBtn.addEventListener('click', function () {
            if (isProcessing) {
                stopGeneration();
            } else {
                sendMessage();
            }
        });

        newSessionBtn.addEventListener('click', function () {
            createNewSession();
        });

        clearBtn.addEventListener('click', function () {
            clearChat();
        });

        modelDropdownTrigger.addEventListener('click', function (e) {
            e.stopPropagation();
            modelDropdown.classList.toggle('open');
            // 关闭模式下拉框
            modeDropdown.classList.remove('open');
        });

        modeDropdownTrigger.addEventListener('click', function (e) {
            e.stopPropagation();
            modeDropdown.classList.toggle('open');
            // 关闭模型下拉框
            modelDropdown.classList.remove('open');
        });

        document.addEventListener('click', function () {
            modelDropdown.classList.remove('open');
            modeDropdown.classList.remove('open');
        });

        ready = true;
        flushQueue();
        initTokenProgress();
    });

    function flushQueue() {
        while (messageQueue.length > 0) {
            var fn = messageQueue.shift();
            fn();
        }
    }

    function whenReady(fn) {
        if (ready) { fn(); } else { messageQueue.push(fn); }
    }

    /* ===== Auto-resize textarea ===== */
    function autoResize() {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 160) + 'px';
    }

    /* ===== User actions ===== */
    function sendMessage() {
        var text = messageInput.value.trim();
        if (!text || isProcessing) return;

        messageInput.value = '';
        messageInput.style.height = 'auto';
        isProcessing = true;
        setButtonToStop();

        appendUserMessage(text);
        showThinking();

        callJava('sendMessage', { content: text });
    }

    function stopGeneration() {
        callJava('stopGeneration', {});
    }

    function setButtonToStop() {
        sendBtn.classList.add('stop-mode');
        sendBtn.innerHTML = '&#x25A0;'; // ■ 停止符号
        sendBtn.title = '\u505c\u6b62\u751f\u6210';
    }

    function setButtonToSend() {
        sendBtn.classList.remove('stop-mode');
        sendBtn.innerHTML = '&#x27A4;'; // ➤ 发送符号
        sendBtn.title = '\u53d1\u9001 (Enter)';
    }

    function clearChat() {
        clearMessages();
        callJava('clearChat', {});
    }

    function createNewSession() {
        clearMessages();
        callJava('createSession', {});
    }

    function switchSession(id) {
        clearMessages();
        callJava('switchSession', { sessionId: id });
    }

    function deleteSession(id) {
        callJava('deleteSession', { sessionId: id });
    }

    function callJava(action, data) {
        if (typeof window.taiweiQuery === 'function') {
            window.taiweiQuery(JSON.stringify({ action: action, data: data }));
        }
    }

    /* ===== Java -> JS API ===== */

    window.appendContent = function (content) {
        whenReady(function () {
            accumulatedContent += content;

            if (currentAssistantEl && currentContentEl) {
                // 纯文本增量追加，不触发 Markdown 渲染
                currentContentEl.textContent += content;
                scrollToBottom();
                return;
            }

            // 首次：创建 assistant 消息框（不关闭 thinking 动画，等 onComplete/onError 时再关闭）
            removeWelcome();

            var msg = createMessageEl('assistant', 'AI');
            currentAssistantEl = msg;
            currentContentEl = msg.querySelector('.message-content');
            // 初始内容用 textContent 设置
            currentContentEl.textContent = accumulatedContent;
            scrollToBottom();
        });
    };

    window.showToolCall = function (name, args) {
        whenReady(function () {
            // 不同迭代的文本之间加换行分隔
            if (accumulatedContent.length > 0 && !accumulatedContent.endsWith('\n')) {
                accumulatedContent += '\n';
                if (currentContentEl) {
                    currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                    scrollToBottom();
                }
            }
            // 不要重置 assistant 引用，保持当前 assistant 消息框继续渲染

            var el = document.createElement('div');
            el.className = 'message tool';

            var argsHtml = '';
            if (args) {
                var display = args.length > 200 ? args.substring(0, 200) + '...' : args;
                argsHtml = '<div class="tool-args">' + MarkdownRenderer.escapeHtml(display) + '</div>';
            }

            el.innerHTML =
                '<div class="message-label">&#x1f527; &#x5de5;&#x5177; &middot; <span class="tool-name">' +
                MarkdownRenderer.escapeHtml(name) + '</span></div>' +
                argsHtml +
                '<div class="tool-status">&#x6267;&#x884c;&#x4e2d;...</div>';

            messagesArea.appendChild(el);
            scrollToBottom();
        });
    };

    window.updateToolCall = function (name, result) {
        whenReady(function () {
            var cards = messagesArea.querySelectorAll('.message.tool');
            for (var i = cards.length - 1; i >= 0; i--) {
                var card = cards[i];
                var nameEl = card.querySelector('.tool-name');
                if (nameEl && nameEl.textContent === name) {
                    var statusEl = card.querySelector('.tool-status');
                    if (statusEl) statusEl.remove();

                    var existing = card.querySelector('.tool-result');
                    if (!existing) {
                        var resDiv = document.createElement('div');
                        resDiv.className = 'tool-result';
                        var resDisplay = result && result.length > 500 ? result.substring(0, 500) + '\n...' : (result || '');
                        resDiv.textContent = resDisplay;
                        card.appendChild(resDiv);
                    }
                    break;
                }
            }
            scrollToBottom();
        });
    };

    /* ===== 进度条 UI ===== */
    window.showProgress = function (toolCallId, status) {
        whenReady(function () {
            var existing = document.getElementById('progress-' + toolCallId);
            if (existing) {
                var statusEl = existing.querySelector('.command-status-text');
                if (statusEl) {
                    var display = status.length > 100 ? status.substring(0, 100) + '...' : status;
                    statusEl.textContent = display;
                }
                // 如果是完成状态，移除进度条
                if (status.indexOf('\u2705') !== -1) {
                    var bar = existing.querySelector('.command-progress-bar');
                    if (bar) bar.remove();
                }
                return;
            }

            removeThinking();
            // 不同迭代的文本之间加换行分隔
            if (accumulatedContent.length > 0 && !accumulatedContent.endsWith('\n')) {
                accumulatedContent += '\n';
                if (currentContentEl) {
                    currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                    scrollToBottom();
                }
            }
            // 不要重置 assistant 引用，保持当前 assistant 消息框继续渲染

            var el = document.createElement('div');
            el.className = 'message tool command-progress';
            el.id = 'progress-' + toolCallId;
            el.innerHTML =
                '<div class="message-label">&#x1f527; &#x5de5;&#x5177; &middot; <span class="tool-name">' + MarkdownRenderer.escapeHtml(toolCallId) + '</span></div>' +
                '<div class="command-progress-bar"><div class="command-progress-indeterminate"></div></div>' +
                '<div class="command-status-text">' + MarkdownRenderer.escapeHtml(status) + '</div>';
            messagesArea.appendChild(el);
            scrollToBottom();
        });
    };

    window.hideProgress = function (toolCallId) {
        whenReady(function () {
            var el = document.getElementById('progress-' + toolCallId);
            if (el) el.remove();
        });
    };

    window.clearAllProgress = function () {
        whenReady(function () {
            var els = document.querySelectorAll('[id^="progress-"]');
            for (var i = 0; i < els.length; i++) { els[i].remove(); }
        });
    };

    /* ===== 危险命令运行按钮 ===== */
    window.showRunButton = function (toolCallId, command) {
        whenReady(function () {
            var existing = document.getElementById('runbtn-' + toolCallId);
            if (existing) return;

            removeThinking();
            // 不同迭代的文本之间加换行分隔
            if (accumulatedContent.length > 0 && !accumulatedContent.endsWith('\n')) {
                accumulatedContent += '\n';
                if (currentContentEl) {
                    currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                    scrollToBottom();
                }
            }
            // 不要重置 assistant 引用，保持当前 assistant 消息框继续渲染

            var el = document.createElement('div');
            el.className = 'message tool command-run-container';
            el.id = 'runbtn-' + toolCallId;

            var cmdDisplay = command.length > 400 ? command.substring(0, 400) + '...' : command;

            el.innerHTML =
                '<div class="message-label">&#x26a0;&#xfe0f; &#x5de5;&#x5177; &middot; <span class="tool-name">run_command</span></div>' +
                '<div class="command-warning">&#x26a0;&#xfe0f; &#x6b64;&#x547d;&#x4ee4;&#x88ab;&#x8bc6;&#x522b;&#x4e3a;&#x5371;&#x9669;&#x547d;&#x4ee4;&#xff0c;&#x8bf7;&#x786e;&#x8ba4;&#x540e;&#x6267;&#x884c;</div>' +
                '<div class="command-text">' + MarkdownRenderer.escapeHtml(cmdDisplay) + '</div>' +
                '<button class="command-run-btn" onclick="window.runCommandAction(\'' + toolCallId + '\')">&#x25b6; &#x8fd0;&#x884c;</button>';

            messagesArea.appendChild(el);
            scrollToBottom();
        });
    };

    // 运行按钮点击处理
    window.runCommandAction = function (toolCallId) {
        callJava('runCommand', { toolCallId: toolCallId });
    };

    window.hideRunButton = function (toolCallId) {
        whenReady(function () {
            var el = document.getElementById('runbtn-' + toolCallId);
            if (el) {
                // 替换为"已执行"状态
                el.innerHTML =
                    '<div class="message-label">&#x1f527; &#x5de5;&#x5177; &middot; <span class="tool-name">run_command</span></div>' +
                    '<div class="command-status-text">&#x5df2;&#x6267;&#x884c;</div>';
            }
        });
    };

    window.clearAllRunButtons = function () {
        whenReady(function () {
            var els = document.querySelectorAll('[id^="runbtn-"]');
            for (var i = 0; i < els.length; i++) { els[i].remove(); }
        });
    };

    window.onComplete = function () {
        whenReady(function () {
            removeThinking();
            removeRoundLoading();
            isProcessing = false;
            setButtonToSend();
            sendBtn.disabled = false;
            
            // 流式完成后做一次完整 Markdown 渲染
            if (currentContentEl && accumulatedContent.length > 0) {
                currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                scrollToBottom();
            }
            
            currentAssistantEl = null;
            currentContentEl = null;
            accumulatedContent = '';
        });
    };

    window.onError = function (error) {
        whenReady(function () {
            removeThinking();
            removeRoundLoading();
            // 先做最终 Markdown 渲染
            if (currentContentEl && accumulatedContent.length > 0) {
                currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
            }
            createMessageEl('error', '❌ 错误').querySelector('.message-content').textContent = error;
            isProcessing = false;
            setButtonToSend();
            sendBtn.disabled = false;
            currentAssistantEl = null;
            currentContentEl = null;
            accumulatedContent = '';
            scrollToBottom();
        });
    };

    window.updateTokenUsage = function (data) {
        whenReady(function () {
            var usage = data.usage || {};
            var elapsedMs = data.elapsedMs || 0;
            totalUsedTokens += usage.totalTokens || 0;

            var statsEl = document.createElement('div');
            statsEl.className = 'token-stats';
            var elapsed = (elapsedMs / 1000).toFixed(1);
            statsEl.innerHTML =
                '<span>\u8f93\u5165: ' + (usage.promptTokens || 0).toLocaleString() + '</span>' +
                '<span>\u8f93\u51fa: ' + (usage.completionTokens || 0).toLocaleString() + '</span>' +
                '<span>\u8017\u65f6: ' + elapsed + 's</span>';
            messagesArea.appendChild(statsEl);

            updateTokenProgressRing();
            scrollToBottom();
        });
    };

    window.updateSessionList = function (sessions, activeId) {
        whenReady(function () {
            var sessionList;
            if (typeof sessions === 'string') {
                try { sessionList = JSON.parse(sessions); } catch (e) { return; }
            } else {
                sessionList = sessions;
            }
            renderSessionTabs(sessionList, activeId);
        });
    };

    window.updateMode = function (mode) {
        whenReady(function () {
            if (!modeDropdownLabel || !modeDropdownMenu) return;
            var isPlan = mode === 'plan';
            modeDropdownLabel.textContent = isPlan ? '\uD83D\uDFE1 Plan' : '\uD83D\uDFE2 Build';

            // 渲染下拉框选项
            modeDropdownMenu.innerHTML = '';
            var modes = [
                { value: 'build', label: '\uD83D\uDFE2 Build', desc: '\u6b63\u5e38\u8bfb\u5199' },
                { value: 'plan', label: '\uD83D\uDFE1 Plan', desc: '\u53ea\u8bfb\u5206\u6790' }
            ];
            for (var i = 0; i < modes.length; i++) {
                var item = document.createElement('div');
                item.className = 'mode-dropdown-item' + (modes[i].value === mode ? ' active' : '');
                item.setAttribute('data-mode', modes[i].value);
                item.innerHTML = '<span>' + modes[i].label + '</span><span style="color:var(--text-tertiary);font-size:11px">' + modes[i].desc + '</span>';
                item.addEventListener('click', function (e) {
                    e.stopPropagation();
                    var selectedMode = this.getAttribute('data-mode');
                    callJava('setMode', { mode: selectedMode });
                    modeDropdown.classList.remove('open');
                });
                modeDropdownMenu.appendChild(item);
            }
        });
    };

    window.updateModelList = function (models, activeIndex) {
        whenReady(function () {
            var modelList;
            if (typeof models === 'string') {
                try { modelList = JSON.parse(models); } catch (e) { return; }
            } else {
                modelList = models;
            }
            renderModelSelect(modelList, activeIndex);
        });
    };

    window.loadHistory = function (messagesJson, isActiveProcessing) {
        whenReady(function () {
            clearMessages();
            try {
                var messages;
                if (typeof messagesJson === 'string') {
                    messages = JSON.parse(messagesJson);
                } else {
                    messages = messagesJson || [];
                }
                for (var i = 0; i < messages.length; i++) {
                    var m = messages[i];
                    if (m.role === 'user') {
                        appendUserMessage(m.content);
                    } else if (m.role === 'assistant' && m.content) {
                        var el = createMessageEl('assistant', 'AI');
                        el.querySelector('.message-content').innerHTML = MarkdownRenderer.render(m.content);
                    }
                }

                // 恢复流式传输状态
                if (isActiveProcessing && messages.length > 0) {
                    isProcessing = true;
                    setButtonToStop();

                    // 找到最后一个 assistant 消息，用于后续 appendContent 追加
                    var assistantEls = messagesArea.querySelectorAll('.message.assistant');
                    if (assistantEls.length > 0) {
                        var lastAssistant = assistantEls[assistantEls.length - 1];
                        currentAssistantEl = lastAssistant;
                        var contentEl = lastAssistant.querySelector('.message-content');
                        if (contentEl) {
                            currentContentEl = contentEl;
                            // 用 textContent 获取纯文本长度（与 accumulatedContent 对齐）
                            accumulatedContent = contentEl.textContent || '';
                        }
                    }

                    // 如果没有 assistant 消息但有流式状态，显示思考指示器
                    if (assistantEls.length === 0) {
                        showThinking();
                    }
                }
            } catch (e) { /* ignore parse errors */ }
            scrollToBottom();
        });
    };

    /* ===== Top Bar: Session Tabs ===== */

    function renderSessionTabs(sessions, activeId) {
        if (!tabList) return;
        tabList.innerHTML = '';

        for (var i = 0; i < sessions.length; i++) {
            var s = sessions[i];
            var isActive = s.id === activeId;
            var tab = document.createElement('div');
            tab.className = 'tab-item' + (isActive ? ' active' : '');
            tab.setAttribute('data-id', s.id);

            var titleSpan = document.createElement('span');
            titleSpan.className = 'tab-title';
            var title = s.title || '\u65b0\u4f1a\u8bdd';
            titleSpan.textContent = title.length > 12 ? title.substring(0, 12) + '...' : title;
            titleSpan.addEventListener('click', function (sid) {
                return function () { switchSession(sid); };
            }(s.id));
            tab.appendChild(titleSpan);

            var closeBtn = document.createElement('span');
            closeBtn.className = 'tab-close';
            closeBtn.innerHTML = '\u00d7';
            closeBtn.title = '\u5173\u95ed\u4f1a\u8bdd';
            closeBtn.addEventListener('click', function (sid, evt) {
                return function (e) {
                    e.stopPropagation();
                    deleteSession(sid);
                };
            }(s.id));
            tab.appendChild(closeBtn);

            tabList.appendChild(tab);
        }
    }

    /* ===== Top Bar: Model Selector ===== */

    function renderModelSelect(models, activeIndex) {
        if (!modelDropdownMenu || !modelDropdownLabel) return;
        modelDropdownMenu.innerHTML = '';

        for (var i = 0; i < models.length; i++) {
            var item = document.createElement('div');
            item.className = 'model-dropdown-item' + (i === activeIndex ? ' active' : '');
            item.textContent = models[i].name;
            item.setAttribute('data-index', i);
            item.addEventListener('click', function (e) {
                e.stopPropagation();
                var idx = parseInt(this.getAttribute('data-index'), 10);
                if (!isNaN(idx)) {
                    callJava('selectModel', { index: idx });
                    modelDropdownLabel.textContent = this.textContent;
                    modelDropdown.classList.remove('open');
                    // Update active state
                    var items = modelDropdownMenu.querySelectorAll('.model-dropdown-item');
                    for (var j = 0; j < items.length; j++) {
                        items[j].classList.remove('active');
                    }
                    this.classList.add('active');
                }
            });
            modelDropdownMenu.appendChild(item);
        }

        // Set label to active model
        if (models[activeIndex]) {
            modelDropdownLabel.textContent = models[activeIndex].name;
        }
    }

    /* ===== DOM helpers ===== */

    function appendUserMessage(text) {
        removeWelcome();
        var el = createMessageEl('user', '&#x1f464; &#x4f60;');
        el.querySelector('.message-content').textContent = text;
        scrollToBottom();
    }

    function renderAssistantContent(content) {
        if (!currentContentEl) return;
        currentContentEl.innerHTML = MarkdownRenderer.render(content);
        scrollToBottom();
    }

    function createMessageEl(type, labelHtml) {
        removeWelcome();
        var el = document.createElement('div');
        el.className = 'message ' + type;
        el.innerHTML =
            '<div class="message-label">' + labelHtml + '</div>' +
            '<div class="message-content"></div>';
        messagesArea.appendChild(el);
        return el;
    }

    function showThinking() {
        removeThinking();
        var el = document.createElement('div');
        el.className = 'thinking-indicator';
        el.id = 'thinkingIndicator';
        el.innerHTML =
            '<div class="thinking-dots"><span></span><span></span><span></span></div>' +
            '<span>thinking...</span>';
        messagesArea.appendChild(el);
        scrollToBottom();
    }

    function removeThinking() {
        var el = document.getElementById('thinkingIndicator');
        if (el) el.remove();
    }

    // 暴露给 Java 端调用
    window.showThinking = showThinking;
    window.removeThinking = removeThinking;

    function showRoundLoading() {
        removeRoundLoading();
        var el = document.createElement('div');
        el.className = 'round-loading';
        el.id = 'roundLoadingIndicator';
        el.innerHTML =
            '<div class="round-loading-spinner"></div>' +
            '<span>\u5904\u7406\u4e2d...</span>';
        messagesArea.appendChild(el);
        scrollToBottom();
    }

    function removeRoundLoading() {
        var el = document.getElementById('roundLoadingIndicator');
        if (el) el.remove();
    }

    function removeWelcome() {
        if (welcomeScreen && welcomeScreen.parentNode) {
            welcomeScreen.remove();
        }
    }

    function initTokenProgress() {
        if (!inputWrapper) return;
        var container = document.createElement('div');
        container.className = 'token-progress-container';
        container.innerHTML =
            '<svg class="token-progress-ring" width="24" height="24" viewBox="0 0 24 24">' +
                '<circle class="token-progress-bg" cx="12" cy="12" r="10" />' +
                '<circle class="token-progress-fg" cx="12" cy="12" r="10" />' +
            '</svg>' +
            '<div class="token-progress-tooltip"></div>';
        inputWrapper.appendChild(container);

        container.addEventListener('click', function () {
            callJava('manualCompress', {});
        });
    }

    function updateTokenProgressRing() {
        var ring = document.querySelector('.token-progress-fg');
        var tooltip = document.querySelector('.token-progress-tooltip');
        var container = document.querySelector('.token-progress-container');
        if (!ring || !tooltip || !container) return;

        var circumference = 2 * Math.PI * 10;
        var progress = Math.min(totalUsedTokens / TOKEN_BUDGET, 1);
        ring.style.strokeDasharray = circumference;
        ring.style.strokeDashoffset = circumference * (1 - progress);

        container.classList.remove('warning', 'danger');
        if (progress > 0.9) {
            container.classList.add('danger');
        } else if (progress > 0.8) {
            container.classList.add('warning');
        }

        container.classList.add('visible');
        var pct = (progress * 100).toFixed(1);
        tooltip.innerHTML = '\u5df2\u7528 ' + totalUsedTokens.toLocaleString() + ' / ' + TOKEN_BUDGET.toLocaleString() + ' tokens<br>(' + pct + '%)';
    }

    function clearMessages() {
        messagesArea.innerHTML = '';
        currentAssistantEl = null;
        currentContentEl = null;
        accumulatedContent = '';
        isProcessing = false;
        totalUsedTokens = 0;
        setButtonToSend();
        sendBtn.disabled = false;

        var progressContainer = document.querySelector('.token-progress-container');
        if (progressContainer) {
            progressContainer.classList.remove('visible', 'warning', 'danger');
        }

        // 清理进度条和运行按钮
        var progressEls = document.querySelectorAll('[id^="progress-"]');
        for (var pe = 0; pe < progressEls.length; pe++) { progressEls[pe].remove(); }
        var runbtnEls = document.querySelectorAll('[id^="runbtn-"]');
        for (var re = 0; re < runbtnEls.length; re++) { runbtnEls[re].remove(); }

        var ws = document.createElement('div');
        ws.className = 'welcome';
        ws.id = 'welcomeScreen';
        ws.innerHTML =
            '<div class="welcome-icon">&#x1f916;</div>' +
            '<h2>&#x592a;&#x5fae; AI &#x52a9;&#x624b;</h2>' +
            '<p>&#x4f60;&#x597d;&#xff0c;&#x6211;&#x662f;&#x4f60;&#x7684; AI &#x7f16;&#x7a0b;&#x52a9;&#x624b;&#x3002;&#x8f93;&#x5165;&#x95ee;&#x9898;&#x5f00;&#x59cb;&#x5bf9;&#x8bdd;&#xff0c;&#x6211;&#x53ef;&#x4ee5;&#x5e2e;&#x4f60;&#x7f16;&#x5199;&#x4ee3;&#x7801;&#x3001;&#x5206;&#x6790;&#x95ee;&#x9898;&#x3001;&#x6267;&#x884c;&#x547d;&#x4ee4;&#x3002;</p>';
        messagesArea.appendChild(ws);
        welcomeScreen = ws;
    }

    function scrollToBottom() {
        requestAnimationFrame(function () {
            messagesArea.scrollTop = messagesArea.scrollHeight;
        });
    }

    /* ===== Compress Notification ===== */
    window.showCompressNotification = function (data) {
        whenReady(function () {
            var parsed;
            if (typeof data === 'string') {
                try { parsed = JSON.parse(data); } catch (e) { return; }
            } else {
                parsed = data;
            }

            var el = document.createElement('div');
            el.className = 'compress-notification';
            el.textContent = '\ud83d\udce6 \u4e0a\u4e0b\u6587\u5df2\u538b\u7f29\uff08\u538b\u7f29\u524d '
                + parsed.before.toLocaleString() + ' tokens \u2192 \u538b\u7f29\u540e '
                + parsed.after.toLocaleString() + ' tokens\uff0c\u8282\u7701 '
                + parsed.percent + '%\uff09';
            messagesArea.appendChild(el);
            scrollToBottom();
        });
    };

    /* ===== Utility ===== */
    window.escapeHtml = function (text) {
        return MarkdownRenderer.escapeHtml(text);
    };

})();
