/**
 * Lightweight Markdown Renderer for JCEF Chat UI
 * Handles: code blocks, inline code, headers, bold, italic, strikethrough,
 *          links, images, blockquotes, lists, horizontal rules, tables
 */
var MarkdownRenderer = (function () {

    function escapeHtml(text) {
        if (!text) return '';
        return text
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function render(src) {
        if (!src) return '';
        return parseBlocks(src);
    }

    /* ---- block-level parser ---- */
    function parseBlocks(text) {
        var lines = text.split('\n');
        var html = [];
        var i = 0;

        while (i < lines.length) {
            var line = lines[i];

            // fenced code block
            if (/^```/.test(line)) {
                var lang = line.slice(3).trim();
                var code = [];
                i++;
                while (i < lines.length && !/^```/.test(lines[i])) {
                    code.push(lines[i]);
                    i++;
                }
                if (i < lines.length) i++; // skip closing ```
                var cls = lang ? ' class="language-' + escapeHtml(lang) + '"' : '';
                html.push('<pre><code' + cls + '>' + escapeHtml(code.join('\n')) + '</code></pre>');
                continue;
            }

            // header
            var hm = line.match(/^(#{1,6})\s+(.+)/);
            if (hm) {
                var lvl = hm[1].length;
                html.push('<h' + lvl + '>' + processInline(hm[2]) + '</h' + lvl + '>');
                i++;
                continue;
            }

            // horizontal rule
            if (/^(\*{3,}|-{3,}|_{3,})\s*$/.test(line)) {
                html.push('<hr>');
                i++;
                continue;
            }

            // blockquote
            if (/^>\s?/.test(line)) {
                var q = [];
                while (i < lines.length && /^>\s?/.test(lines[i])) {
                    q.push(lines[i].replace(/^>\s?/, ''));
                    i++;
                }
                html.push('<blockquote>' + parseBlocks(q.join('\n')) + '</blockquote>');
                continue;
            }

            // unordered list
            if (/^[\-\*\+]\s/.test(line)) {
                var items = [];
                while (i < lines.length && /^[\-\*\+]\s/.test(lines[i])) {
                    items.push(lines[i].replace(/^[\-\*\+]\s/, ''));
                    i++;
                }
                html.push('<ul>' + items.map(function (t) {
                    return '<li>' + processInline(t) + '</li>';
                }).join('') + '</ul>');
                continue;
            }

            // ordered list
            if (/^\d+\.\s/.test(line)) {
                var oitems = [];
                while (i < lines.length && /^\d+\.\s/.test(lines[i])) {
                    oitems.push(lines[i].replace(/^\d+\.\s/, ''));
                    i++;
                }
                html.push('<ol>' + oitems.map(function (t) {
                    return '<li>' + processInline(t) + '</li>';
                }).join('') + '</ol>');
                continue;
            }

            // table (GFM)
            if (line.indexOf('|') !== -1 && i + 1 < lines.length &&
                /^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(lines[i + 1])) {
                var hdrCells = parseTR(line);
                i += 2; // skip header + separator
                var rows = [];
                while (i < lines.length && lines[i].indexOf('|') !== -1 && lines[i].trim() !== '') {
                    rows.push(parseTR(lines[i]));
                    i++;
                }
                var th = '<thead><tr>' + hdrCells.map(function (c) {
                    return '<th>' + processInline(c) + '</th>';
                }).join('') + '</tr></thead>';
                var tb = '<tbody>' + rows.map(function (r) {
                    return '<tr>' + r.map(function (c) {
                        return '<td>' + processInline(c) + '</td>';
                    }).join('') + '</tr>';
                }).join('') + '</tbody>';
                html.push('<table>' + th + tb + '</table>');
                continue;
            }

            // blank line
            if (line.trim() === '') { i++; continue; }

            // paragraph — collect consecutive non-special lines
            var p = [];
            while (i < lines.length &&
                lines[i].trim() !== '' &&
                !/^```/.test(lines[i]) &&
                !/^#{1,6}\s/.test(lines[i]) &&
                !/^(\*{3,}|-{3,}|_{3,})\s*$/.test(lines[i]) &&
                !/^>\s?/.test(lines[i]) &&
                !/^[\-\*\+]\s/.test(lines[i]) &&
                !/^\d+\.\s/.test(lines[i])) {
                p.push(lines[i]);
                i++;
            }
            if (p.length) html.push('<p>' + processInline(p.join('\n')) + '</p>');
        }

        return html.join('\n');
    }

    /* ---- table helpers ---- */
    function parseTR(line) {
        line = line.trim();
        if (line.startsWith('|')) line = line.slice(1);
        if (line.endsWith('|')) line = line.slice(0, -1);
        return line.split('|').map(function (c) { return c.trim(); });
    }

    /* ---- inline parser ---- */
    function processInline(text) {
        if (!text) return '';
        var codes = [];
        // protect inline code
        text = text.replace(/`([^`]+)`/g, function (_, c) {
            codes.push('<code>' + escapeHtml(c) + '</code>');
            return '\x00C' + (codes.length - 1) + '\x00';
        });

        text = escapeHtml(text);

        // images (before links)
        text = text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width:100%">');
        // links
        text = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');
        // bold
        text = text.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        text = text.replace(/__(.+?)__/g, '<strong>$1</strong>');
        // italic
        text = text.replace(/\*(.+?)\*/g, '<em>$1</em>');
        text = text.replace(/_(.+?)_/g, '<em>$1</em>');
        // strikethrough
        text = text.replace(/~~(.+?)~~/g, '<del>$1</del>');
        // line breaks
        text = text.replace(/\n/g, '<br>');

        // restore inline code
        for (var j = 0; j < codes.length; j++) {
            text = text.replace('\x00C' + j + '\x00', codes[j]);
        }
        return text;
    }

    return { render: render, escapeHtml: escapeHtml };
})();
