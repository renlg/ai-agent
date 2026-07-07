/**
 * 聊天消息渲染工具
 */

/**
 * 创建用户消息元素
 */
function appendUserMessage(text) {
    const chatArea = document.getElementById('chatArea');
    const msgEl = document.createElement('div');
    msgEl.className = 'message user';
    msgEl.innerHTML = `
        <div class="message-bubble">
            ${escapeHtml(text)}
        </div>
    `;
    chatArea.appendChild(msgEl);
    scrollToBottom();
}

/**
 * 创建助手消息元素并返回引用
 */
function createAssistantMessage() {
    const chatArea = document.getElementById('chatArea');
    const msgEl = document.createElement('div');
    msgEl.className = 'message assistant';
    
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble';
    msgEl.appendChild(bubble);
    
    chatArea.appendChild(msgEl);
    
    return {
        element: msgEl,
        bubble: bubble,
        rawContent: ''
    };
}

/**
 * 渲染历史助手消息（非流式，直接显示完整内容）
 */
function appendHistoryAssistantMessage(content) {
    const chatArea = document.getElementById('chatArea');
    const msgEl = document.createElement('div');
    msgEl.className = 'message assistant';

    const bubble = document.createElement('div');
    bubble.className = 'message-bubble';
    if (typeof renderMarkdownContent === 'function') {
        bubble.innerHTML = renderMarkdownContent(content);
    } else {
        bubble.textContent = content;
    }
    msgEl.appendChild(bubble);

    chatArea.appendChild(msgEl);
    scrollToBottom();
}

/**
 * 渲染 Markdown 内容到助手消息
 */
function renderMarkdown(msgRef) {
    if (!msgRef || !msgRef.bubble) return;
    
    // 如果有 markdown 渲染器，使用它
    if (typeof renderMarkdownContent === 'function') {
        msgRef.bubble.innerHTML = renderMarkdownContent(msgRef.rawContent);
    } else {
        // 简单转义显示
        msgRef.bubble.textContent = msgRef.rawContent;
    }
}

/**
 * 追加错误消息
 */
function appendErrorMessage(error) {
    const chatArea = document.getElementById('chatArea');
    const errorEl = document.createElement('div');
    errorEl.className = 'error-message';
    errorEl.textContent = '❌ ' + error;
    chatArea.appendChild(errorEl);
    scrollToBottom();
}

/**
 * 滚动到底部
 */
function scrollToBottom() {
    const chatArea = document.getElementById('chatArea');
    chatArea.scrollTop = chatArea.scrollHeight;
}

/**
 * HTML 转义
 */
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
