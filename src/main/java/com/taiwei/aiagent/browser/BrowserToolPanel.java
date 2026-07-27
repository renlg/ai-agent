package com.taiwei.aiagent.browser;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class BrowserToolPanel extends JPanel implements Disposable {

    private final BrowserToolService service;
    private final JBTextField urlField;
    private final JButton backButton;
    private final JButton forwardButton;
    private final JButton refreshButton;

    public BrowserToolPanel(Project project) {
        this.service = BrowserToolService.getInstance(project);
        setLayout(new BorderLayout());

        JPanel toolbar = new JPanel(new BorderLayout(4, 4));
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        backButton = createButton("\u25C0", "后退");
        forwardButton = createButton("\u25B6", "前进");
        refreshButton = createButton("\u21BB", "刷新");
        navButtons.add(backButton);
        navButtons.add(forwardButton);
        navButtons.add(refreshButton);

        urlField = new JBTextField();
        urlField.setText("about:blank");

        JButton goButton = createButton("Go", "访问");

        toolbar.add(navButtons, BorderLayout.WEST);
        toolbar.add(urlField, BorderLayout.CENTER);
        toolbar.add(goButton, BorderLayout.EAST);

        add(toolbar, BorderLayout.NORTH);

        JComponent browserComponent = service.getBrowserComponent();
        if (browserComponent.getParent() != null) {
            browserComponent.getParent().remove(browserComponent);
        }
        add(browserComponent, BorderLayout.CENTER);

        backButton.addActionListener(e -> service.getOrCreateBrowser().getCefBrowser().goBack());
        forwardButton.addActionListener(e -> service.getOrCreateBrowser().getCefBrowser().goForward());
        refreshButton.addActionListener(e -> service.getOrCreateBrowser().getCefBrowser().reload());
        goButton.addActionListener(e -> navigateFromUrlField());
        urlField.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {}

            @Override
            public void keyPressed(KeyEvent e) {}

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    navigateFromUrlField();
                }
            }
        });
    }

    private void navigateFromUrlField() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        service.openUrl(url);
    }

    private static JButton createButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(2, 6, 2, 6));
        button.setFocusable(false);
        return button;
    }

    @Override
    public void dispose() {
    }
}
