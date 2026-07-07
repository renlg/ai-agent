/**
 * Chat UI Controller
 * Handles DOM manipulation, Java ↔ JS bridge, session management
 */
(function () {
    'use strict';

    /* ===== State ===== */
    var isProcessing = false;
    var currentAssistantEl = null;
    var currentContentEl = null;
    var accumulatedContent = '';
    var messageQueue = [];
    var ready = false;

    /* ===== DOM refs ===== */
    var messagesArea, welcomeScreen, messageInput, sendBtn;

    /* ===== Init ===== */
    document.addEventListener('DOMContentLoaded', function () {
        messagesArea = document.getElementById('messagesArea');
        welcomeScreen = document.getElementById('welcomeScreen');
        messageInput = document.getElementById('messageInput');
        sendBtn = document.getElementById('sendBtn');

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

        sendBtn.addEventListener('click', sendMessage);

        ready = true;
        flushQueue();
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
        sendBtn.disabled = true;

        appendUserMessage(text);
        showThinking();

        callJava('sendMessage', { content: text });
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

    /* ===== Java → JS API ===== */

    window.appendContent = function (content) {
        whenReady(function () {
            accumulatedContent += content;

            if (currentAssistantEl) {
                renderAssistantContent(accumulatedContent);
                return;
            }

            removeThinking();
            removeWelcome();

            var msg = createMessageEl('assistant', 'AI');
            currentAssistantEl = msg;
            currentContentEl = msg.querySelector('.message-content');
            renderAssistantContent(accumulatedContent);
        });
    };

    window.showToolCall = function (name, args) {
        whenReady(function () {
            removeThinking();
            currentAssistantEl = null;
            currentContentEl = null;
            accumulatedContent = '';

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
                        var display = result && result.length > 500 ? result.substring(0, 500) + '\n...' : (result || '');
                        resDiv.textContent = display;
                        card.appendChild(resDiv);
                    }
                    break;
                }
            }
            scrollToBottom();
        });
    };

    window.onComplete = function () {
        whenReady(function () {
            removeThinking();
            isProcessing = false;
            sendBtn.disabled = false;
            currentAssistantEl = null;
            currentContentEl = null;
            accumulatedContent = '';
        });
    };

    window.onError = function (error) {
        whenReady(function () {
            removeThinking();
            createMessageEl('error', '&#x274c; &#x9519;&#x8bef;').querySelector('.message-content').textContent = error;
            isProcessing = false;
            sendBtn.disabled = false;
            currentAssistantEl = null;
            currentContentEl = null;
            accumulatedContent = '';
            scrollToBottom();
        });
    };

    window.updateSessionList = function (sessionsJson, activeId) {
        whenReady(function () {
            /* sessions tab bar lives in Swing; this is a no-op placeholder */
        });
    };

    window.loadHistory = function (messagesJson) {
        whenReady(function () {
            clearMessages();
            try {
                var messages = JSON.parse(messagesJson);
                for (var i = 0; i < messages.length; i++) {
                    var m = messages[i];
                    if (m.role === 'user') {
                        appendUserMessage(m.content);
                    } else if (m.role === 'assistant' && m.content) {
                        var el = createMessageEl('assistant', 'AI');
                        el.querySelector('.message-content').innerHTML = MarkdownRenderer.render(m.content);
                    }
                }
            } catch (e) { /* ignore parse errors */ }
            scrollToBottom();
        });
    };

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
            '<span>&#x601d;&#x8003;&#x4e2d;...</span>';
        messagesArea.appendChild(el);
        scrollToBottom();
    }

    function removeThinking() {
        var el = document.getElementById('thinkingIndicator');
        if (el) el.remove();
    }

    function removeWelcome() {
        if (welcomeScreen && welcomeScreen.parentNode) {
            welcomeScreen.remove();
        }
    }

    function clearMessages() {
        messagesArea.innerHTML = '';
        currentAssistantEl = null;
        currentContentEl = null;
        accumulatedContent = '';
        isProcessing = false;
        sendBtn.disabled = false;

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

    /* ===== Utility ===== */
    window.escapeHtml = function (text) {
        return MarkdownRenderer.escapeHtml(text);
    };

})();
