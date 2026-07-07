package com.taiwei.aiagent.ui;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.Arrays;

public class MarkdownRenderer {

    private static final MutableDataSet OPTIONS = new MutableDataSet();
    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        OPTIONS.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
                TaskListExtension.create()
        ));
        OPTIONS.set(HtmlRenderer.SOFT_BREAK, "<br/>\n");

        PARSER = Parser.builder(OPTIONS).build();
        RENDERER = HtmlRenderer.builder(OPTIONS).build();
    }

    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        Node document = PARSER.parse(markdown);
        String html = RENDERER.render(document);
        return sanitizeForEditorPane(html);
    }

    private static String sanitizeForEditorPane(String html) {
        html = html.replace("<del>", "<s>").replace("</del>", "</s>");

        html = html.replaceAll("<input\\s+type=\"checkbox\"\\s+checked[^>]*/>", "\u2611 ");
        html = html.replaceAll("<input\\s+type=\"checkbox\"[^>]*/>", "\u2610 ");

        html = html.replace("<thead>", "").replace("</thead>", "");
        html = html.replace("<tbody>", "").replace("</tbody>", "");

        html = html.replaceAll(" class=\"task-list\"", "");
        html = html.replaceAll(" class=\"task-list-item\"", "");

        return html;
    }
}
