/**
 * 太微 前端主逻辑
 * 处理页面初始化和全局状态
 */

// 全局状态
let isProcessing = false;
let currentAssistantMessage = null;

/**
 * 页面初始化
 */
document.addEventListener('DOMContentLoaded', function() {
    console.log('太微 前端已加载');
    
    // 设置输入框快捷键
    const input = document.getElementById('messageInput');
    input.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // 自动调整输入框高度
    input.addEventListener('input', function() {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 120) + 'px';
    });
});

/**
 * JS Bridge 就绪回调（由 Java 端注入后调用）
 */
function onBridgeReady() {
    console.log('太微 Bridge 已就绪');
}

/**
 * 发送消息
 */
function sendMessage() {
    const input = document.getElementById('messageInput');
    const message = input.value.trim();
    
    if (!message || isProcessing) return;
    
    // 清除欢迎消息
    clearWelcome();
    
    // 显示用户消息
    appendUserMessage(message);
    
    // 清空输入框
    input.value = '';
    input.style.height = 'auto';
    
    // 设置处理状态
    isProcessing = true;
    updateSendButton();
    
    // 调用 Java Bridge 发送给 Agent
    if (window.aiAgent) {
        window.aiAgent.sendMessage(message);
    } else {
        console.error('太微 Bridge 未加载');
        appendErrorMessage('太微 Bridge 未加载，请刷新页面重试');
        isProcessing = false;
        updateSendButton();
    }
}

/**
 * 清空对话
 */
function clearChat() {
    const chatArea = document.getElementById('chatArea');
    chatArea.innerHTML = `
        <div class="welcome-message">
            <svg class="welcome-icon" width="64" height="64" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg">
                <defs><linearGradient id="cg" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" style="stop-color:#6366f1"/><stop offset="100%" style="stop-color:#8b5cf6"/></linearGradient></defs>
                <line x1="8" y1="1" x2="8" y2="3" stroke="url(#cg)" stroke-width="1" stroke-linecap="round"/>
                <circle cx="8" cy="1" r="0.7" fill="url(#cg)"/>
                <rect x="3" y="3" width="10" height="7" rx="2" fill="url(#cg)"/>
                <circle cx="5.8" cy="6.2" r="1.2" fill="white"/><circle cx="10.2" cy="6.2" r="1.2" fill="white"/>
                <circle cx="6" cy="6.2" r="0.5" fill="#1e1b4b"/><circle cx="10.4" cy="6.2" r="0.5" fill="#1e1b4b"/>
                <rect x="6" y="8" width="4" height="0.8" rx="0.4" fill="white" opacity="0.8"/>
                <rect x="4" y="11" width="8" height="4" rx="1.5" fill="url(#cg)" opacity="0.85"/>
                <circle cx="8" cy="13" r="0.8" fill="white" opacity="0.5"/>
            </svg>
            <h2>欢迎使用太微</h2>
            <p>我是你的 AI 编程助手，可以帮你：</p>
            <ul>
                <li>📖 阅读和分析代码</li>
                <li>✏️ 创建和修改文件</li>
                <li>🔍 搜索项目代码</li>
                <li>⚡ 执行终端命令</li>
            </ul>
            <p class="hint">在下方输入你的需求开始对话</p>
        </div>
    `;
    
    currentAssistantMessage = null;
    
    // 通知 Java 端清空对话历史
    if (window.aiAgent) {
        window.aiAgent.clearChat();
    }
}

/**
 * 清除欢迎消息
 */
function clearWelcome() {
    const welcome = document.querySelector('.welcome-message');
    if (welcome) {
        welcome.remove();
    }
}

/**
 * 更新发送按钮状态
 */
function updateSendButton() {
    const btn = document.getElementById('sendBtn');
    btn.disabled = isProcessing;
}

// ========== Java Bridge 回调函数 ==========

/**
 * Agent 开始思考
 */
function onThinking() {
    const chatArea = document.getElementById('chatArea');
    const thinkingEl = document.createElement('div');
    thinkingEl.className = 'message assistant';
    thinkingEl.id = 'thinkingIndicator';
    thinkingEl.innerHTML = `
        <div class="thinking">
            <div class="thinking-dot"></div>
            <div class="thinking-dot"></div>
            <div class="thinking-dot"></div>
        </div>
    `;
    chatArea.appendChild(thinkingEl);
    scrollToBottom();
}

/**
 * 收到内容片段
 */
function onContent(content) {
    // 移除思考指示器
    const thinking = document.getElementById('thinkingIndicator');
    if (thinking) {
        thinking.remove();
    }
    
    // 如果没有当前助手消息，创建一个
    if (!currentAssistantMessage) {
        currentAssistantMessage = createAssistantMessage();
    }
    
    // 追加内容并渲染 Markdown
    currentAssistantMessage.rawContent += content;
    renderMarkdown(currentAssistantMessage);
    scrollToBottom();
}

/**
 * 工具调用开始
 */
function onToolCallStart(toolName, arguments) {
    // 移除思考指示器
    const thinking = document.getElementById('thinkingIndicator');
    if (thinking) {
        thinking.remove();
    }
    
    const chatArea = document.getElementById('chatArea');
    const toolEl = document.createElement('div');
    toolEl.className = 'tool-call';
    toolEl.innerHTML = `
        <div class="tool-call-header">
            <span class="tool-icon">🔧</span>
            <span>调用工具: ${escapeHtml(toolName)}</span>
            <span class="tool-call-status running">执行中...</span>
        </div>
        <div class="tool-call-body">${escapeHtml(arguments)}</div>
    `;
    chatArea.appendChild(toolEl);
    scrollToBottom();
}

/**
 * 工具调用完成
 */
function onToolCallEnd(toolName, result) {
    const chatArea = document.getElementById('chatArea');
    const toolCalls = chatArea.querySelectorAll('.tool-call');
    const lastToolCall = toolCalls[toolCalls.length - 1];
    
    if (lastToolCall) {
        const status = lastToolCall.querySelector('.tool-call-status');
        if (status) {
            status.textContent = '完成';
            status.className = 'tool-call-status done';
        }
        
        const body = lastToolCall.querySelector('.tool-call-body');
        if (body) {
            body.textContent = result;
        }
    }
    scrollToBottom();
}

/**
 * Agent 完成回答
 */
function onComplete(fullResponse) {
    isProcessing = false;
    currentAssistantMessage = null;
    updateSendButton();
    scrollToBottom();
}

/**
 * 发生错误
 */
function onError(error) {
    // 移除思考指示器
    const thinking = document.getElementById('thinkingIndicator');
    if (thinking) {
        thinking.remove();
    }
    
    appendErrorMessage(error);
    isProcessing = false;
    currentAssistantMessage = null;
    updateSendButton();
}
