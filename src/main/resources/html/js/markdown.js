/**
 * 简易 Markdown 渲染器
 * 支持代码块、行内代码、粗体、斜体等基础格式
 */

function renderMarkdownContent(text) {
    if (!text) return '';
    
    // 先处理代码块（避免内部被其他规则处理）
    let html = '';
    let parts = text.split(/(```[\s\S]*?```)/g);
    
    for (let i = 0; i < parts.length; i++) {
        if (parts[i].startsWith('```') && parts[i].endsWith('```')) {
            // 代码块
            let code = parts[i].slice(3, -3);
            let lang = '';
            
            // 提取语言标识
            let firstNewline = code.indexOf('\n');
            if (firstNewline > 0) {
                lang = code.substring(0, firstNewline).trim();
                code = code.substring(firstNewline + 1);
            } else if (!code.includes('\n')) {
                // 没有换行，可能是空代码块
                code = '';
            }
            
            html += '<pre><code class="language-' + escapeHtml(lang) + '">' + escapeHtml(code) + '</code></pre>';
        } else {
            // 普通文本
            html += renderInlineMarkdown(parts[i]);
        }
    }
    
    return html;
}

/**
 * 处理行内 Markdown 格式
 */
function renderInlineMarkdown(text) {
    let result = escapeHtml(text);
    
    // 行内代码
    result = result.replace(/`([^`]+)`/g, '<code>$1</code>');
    
    // 粗体
    result = result.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    
    // 斜体
    result = result.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    
    // 链接
    result = result.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');
    
    // 换行
    result = result.replace(/\n/g, '<br>');
    
    return result;
}
