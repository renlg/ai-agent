/**
 * 太微 前端主逻辑
 * 处理页面初始化和全局状态
 */

// 全局状态
let isProcessing = false;
let currentAssistantMessage = null;
let currentModelIndex = 0;
let currentSessionId = null;
let sidebarVisible = true;

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

    // 等待 Bridge 就绪后加载模型列表（带重试）
    waitForBridge();
});

/**
 * 等待 JS Bridge 就绪（带重试机制）
 */
function waitForBridge(retries) {
    if (retries === undefined) retries = 0;
    
    if (window.aiAgent && window.aiAgent.getModels) {
        console.log('Bridge 已就绪，加载模型列表');
        loadModelList();
    } else if (retries < 20) {
        setTimeout(function() { waitForBridge(retries + 1); }, 100);
    } else {
        console.error('Bridge 加载超时（2秒），请刷新页面');
    }
}

/**
 * 切换模型下拉框的展开/收起
 */
function toggleModelDropdown() {
    const selector = document.getElementById('modelSelector');
    if (!selector) return;
    
    if (selector.classList.contains('open')) {
        selector.classList.remove('open');
    } else {
        loadModelList(function() {
            selector.classList.add('open');
        });
    }
}

// 点击下拉框外部时自动关闭
document.addEventListener('click', function(e) {
    const selector = document.getElementById('modelSelector');
    if (selector && !selector.contains(e.target)) {
        selector.classList.remove('open');
    }
});

/**
 * 加载模型列表到自定义下拉框
 */
function loadModelList(callback) {
    if (!window.aiAgent || !window.aiAgent.getModels) {
        console.warn('Bridge 未就绪，无法加载模型列表');
        if (callback) callback();
        return;
    }

    window.aiAgent.getModels(function(response) {
        try {
            const data = JSON.parse(response);
            const menu = document.getElementById('modelDropdownMenu');
            const label = document.getElementById('modelSelectLabel');
            if (!menu || !label) return;

            const models = data.models || [];

            // 清空并重新填充
            menu.innerHTML = '';
            let activeName = '默认模型';

            models.forEach(function(m) {
                const item = document.createElement('div');
                item.className = 'model-dropdown-item' + (m.active ? ' active' : '');
                item.innerHTML = '<span class="item-name">' + escapeHtml(m.name) + '</span>'
                    + '<span class="item-model">' + escapeHtml(m.model) + '</span>';
                item.onclick = function(e) {
                    e.stopPropagation();
                    switchModel(m.index);
                    document.getElementById('modelSelector').classList.remove('open');
                };
                menu.appendChild(item);
                if (m.active) {
                    activeName = m.name;
                    currentModelIndex = m.index;
                }
            });

            label.textContent = activeName;
            console.log('模型列表已更新，共 ' + models.length + ' 个模型');
        } catch (e) {
            console.error('解析模型列表失败:', e);
        }
        if (callback) callback();
    });
}

/**
 * 切换模型
 */
function switchModel(modelIndex) {
    const idx = parseInt(modelIndex);
    if (idx === currentModelIndex) return;

    if (window.aiAgent && window.aiAgent.switchModel) {
        window.aiAgent.switchModel(idx, function(response) {
            try {
                const data = JSON.parse(response);
                if (data.ok) {
                    currentModelIndex = idx;
                    console.log('已切换模型到: ' + data.name);
                    // 更新按钮显示文本
                    const label = document.getElementById('modelSelectLabel');
                    if (label) label.textContent = data.name;
                    // 刷新下拉列表中的 active 状态
                    loadModelList();
                } else {
                    console.error('切换模型失败:', data.error);
                    appendErrorMessage('切换模型失败: ' + data.error);
                    loadModelList();
                }
            } catch (e) {
                console.error('解析切换模型响应失败:', e);
                loadModelList();
            }
        });
    } else {
        console.error('Bridge 不支持模型切换');
    }
}

// ========== 会话管理 ==========

/**
 * 加载会话列表
 */
function loadSessionList(callback) {
    if (!window.aiAgent || !window.aiAgent.listSessions) {
        if (callback) callback();
        return;
    }

    window.aiAgent.listSessions(function(response) {
        try {
            const data = JSON.parse(response);
            const sessions = data.sessions || [];
            const activeSessionId = data.activeSessionId;

            if (activeSessionId) {
                currentSessionId = activeSessionId;
            }

            renderSessionList(sessions);
        } catch (e) {
            console.error('解析会话列表失败:', e);
        }
        if (callback) callback();
    });
}

/**
 * 渲染会话列表到侧边栏
 */
function renderSessionList(sessions) {
    const list = document.getElementById('sessionList');
    if (!list) return;

    list.innerHTML = '';

    // 按创建时间倒序排列（最新的在上面）
    const sorted = sessions.slice().sort(function(a, b) {
        return b.createdAt - a.createdAt;
    });

    sorted.forEach(function(session) {
        const item = document.createElement('div');
        item.className = 'session-item' + (session.active ? ' active' : '');
        item.setAttribute('data-session-id', session.id);

        const content = document.createElement('div');
        content.className = 'session-item-content';

        const title = document.createElement('div');
        title.className = 'session-item-title';
        title.textContent = session.title || '新会话';

        const time = document.createElement('div');
        time.className = 'session-item-time';
        time.textContent = formatTime(session.createdAt);

        content.appendChild(title);
        content.appendChild(time);
        item.appendChild(content);

        // 删除按钮
        const deleteBtn = document.createElement('button');
        deleteBtn.className = 'session-item-delete';
        deleteBtn.title = '删除会话';
        deleteBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>';
        deleteBtn.onclick = function(e) {
            e.stopPropagation();
            deleteSessionById(session.id);
        };
        item.appendChild(deleteBtn);

        // 点击切换会话
        item.onclick = function() {
            if (session.id !== currentSessionId) {
                switchToSession(session.id);
            }
        };

        list.appendChild(item);
    });
}

/**
 * 创建新会话
 */
function createNewSession() {
    if (!window.aiAgent || !window.aiAgent.createSession) return;

    window.aiAgent.createSession(function(response) {
        try {
            const data = JSON.parse(response);
            const newSessionId = data.sessionId;
            console.log('新会话已创建: ' + newSessionId);
            currentSessionId = newSessionId;

            // 清空聊天区域显示欢迎页
            clearChatDisplay();

            // 刷新会话列表
            loadSessionList();
        } catch (e) {
            console.error('创建会话失败:', e);
        }
    });
}

/**
 * 切换到指定会话
 */
function switchToSession(sessionId) {
    if (!window.aiAgent || !window.aiAgent.switchSession) return;

    window.aiAgent.switchSession(sessionId, function(response) {
        try {
            const data = JSON.parse(response);
            if (data.ok) {
                currentSessionId = sessionId;
                console.log('已切换到会话: ' + sessionId);

                // 刷新会话列表高亮
                loadSessionList(function() {
                    // 加载该会话的历史消息
                    loadSessionHistory(sessionId);
                });
            }
        } catch (e) {
            console.error('切换会话失败:', e);
        }
    });
}

/**
 * 删除会话
 */
function deleteSessionById(sessionId) {
    if (!window.aiAgent || !window.aiAgent.deleteSession) return;

    window.aiAgent.deleteSession(sessionId, function(response) {
        try {
            const data = JSON.parse(response);
            if (data.ok) {
                console.log('会话已删除，新活跃会话: ' + data.newActiveSessionId);
                currentSessionId = data.newActiveSessionId;

                // 刷新会话列表并加载新活跃会话的历史
                loadSessionList(function() {
                    loadSessionHistory(currentSessionId);
                });
            }
        } catch (e) {
            console.error('删除会话失败:', e);
        }
    });
}

/**
 * 加载会话历史消息
 */
function loadSessionHistory(sessionId) {
    if (!window.aiAgent || !window.aiAgent.getSessionHistory) return;

    window.aiAgent.getSessionHistory(sessionId, function(response) {
        try {
            const data = JSON.parse(response);
            const messages = data.messages || [];

            clearChatDisplay();

            if (messages.length === 0) {
                showWelcomeMessage();
            } else {
                messages.forEach(function(msg) {
                    if (msg.role === 'user') {
                        appendUserMessage(msg.content);
                    } else if (msg.role === 'assistant' && msg.content) {
                        appendHistoryAssistantMessage(msg.content);
                    }
                });
            }
        } catch (e) {
            console.error('加载会话历史失败:', e);
        }
    });
}

/**
 * 清空聊天区域显示
 */
function clearChatDisplay() {
    const chatArea = document.getElementById('chatArea');
    chatArea.innerHTML = '';
    currentAssistantMessage = null;
}

/**
 * 显示欢迎消息
 */
function showWelcomeMessage() {
    const chatArea = document.getElementById('chatArea');
    chatArea.innerHTML = `
        <div class="welcome-message">
            <svg class="welcome-icon" width="64" height="64" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg">
                <defs><linearGradient id="wg2" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" style="stop-color:#6366f1"/><stop offset="100%" style="stop-color:#8b5cf6"/></linearGradient></defs>
                <line x1="8" y1="1" x2="8" y2="3" stroke="url(#wg2)" stroke-width="1" stroke-linecap="round"/>
                <circle cx="8" cy="1" r="0.7" fill="url(#wg2)"/>
                <rect x="3" y="3" width="10" height="7" rx="2" fill="url(#wg2)"/>
                <circle cx="5.8" cy="6.2" r="1.2" fill="white"/><circle cx="10.2" cy="6.2" r="1.2" fill="white"/>
                <circle cx="6" cy="6.2" r="0.5" fill="#1e1b4b"/><circle cx="10.4" cy="6.2" r="0.5" fill="#1e1b4b"/>
                <rect x="6" y="8" width="4" height="0.8" rx="0.4" fill="white" opacity="0.8"/>
                <rect x="4" y="11" width="8" height="4" rx="1.5" fill="url(#wg2)" opacity="0.85"/>
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
}

/**
 * 切换侧边栏显示/隐藏
 */
function toggleSidebar() {
    const sidebar = document.getElementById('sessionSidebar');
    if (!sidebar) return;
    sidebarVisible = !sidebarVisible;
    if (sidebarVisible) {
        sidebar.classList.remove('collapsed');
    } else {
        sidebar.classList.add('collapsed');
    }
}

/**
 * 格式化时间戳
 */
function formatTime(timestamp) {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();

    if (isToday) {
        return date.getHours().toString().padStart(2, '0') + ':' +
               date.getMinutes().toString().padStart(2, '0');
    }

    return (date.getMonth() + 1) + '/' + date.getDate();
}

/**
 * JS Bridge 就绪回调（由 Java 端注入后调用）
 */
function onBridgeReady() {
    console.log('太微 onBridgeReady 回调触发');
    // Bridge 就绪后加载模型列表和会话列表
    loadModelList();
    loadSessionList(function() {
        if (currentSessionId) {
            loadSessionHistory(currentSessionId);
        }
    });
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
    
    // 调用 Java Bridge 发送给 Agent（带 sessionId）
    if (window.aiAgent) {
        const payload = JSON.stringify({
            sessionId: currentSessionId || '',
            message: message
        });
        window.aiAgent.sendMessage(payload);
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
    clearChatDisplay();
    showWelcomeMessage();
    
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

    // 刷新会话列表（标题可能已更新）
    loadSessionList();
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
