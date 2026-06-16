package org.wodichka.packcontrol.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public final class PackControlScreen extends Screen {
    private static final int BACKGROUND = 0xF0101320;
    private static final int PANEL = 0xCC182033;
    private static final int PANEL_DARK = 0xCC121827;
    private static final int BUTTON = 0xFF202A40;
    private static final int BUTTON_HOVER = 0xFF2A3655;
    private static final int BUTTON_BORDER = 0xFF3B496B;
    private static final int BUTTON_ACCENT = 0xFF6F64C9;
    private static final int BORDER = 0xFF34415F;
    private static final int BORDER_BRIGHT = 0xFF6F64C9;
    private static final int TEXT = 0xFFE7E9F3;
    private static final int MUTED = 0xFF9EA7BC;
    private static final int WARNING = 0xFFFFD863;
    private static final int GOOD = 0xFF72E0A2;

    private final Screen parent;
    private final PackControlUiState state;
    private final List<Renderable> widgets = new ArrayList<>();
    private Component notice = Component.translatable("packcontrol.notice.ready");

    public PackControlScreen(Screen parent) {
        super(Component.translatable("packcontrol.screen.title"));
        this.parent = parent;
        this.state = PackControlUiState.placeholder();
    }

    @Override
    protected void init() {
        widgets.clear();
        int left = contentLeft();
        int top = contentTop();
        int leftWidth = leftPanelWidth();
        int buttonWidth = leftWidth - 48;
        int x = left + 24;
        int y = top + 78;

        addActionButton("packcontrol.action.update", x, y, buttonWidth);
        addActionButton("packcontrol.action.reinstall", x, y + 25, buttonWidth);
        addActionButton("packcontrol.action.verify", x, y + 50, buttonWidth);
        addActionButton("packcontrol.action.repository", x, y + 83, buttonWidth);
        addActionButton("packcontrol.action.releases", x, y + 108, buttonWidth);
        addActionButton("packcontrol.action.changelog", x, y + 133, buttonWidth);

        addCustomButton(Component.translatable("packcontrol.action.back"), x, top + contentHeight() - 33, buttonWidth, 20,
                () -> minecraft.setScreen(parent));
    }

    private void addActionButton(String key, int x, int y, int width) {
        addCustomButton(Component.translatable(key), x, y, width, 20, () ->
                notice = Component.translatable("packcontrol.notice.coming_soon", Component.translatable(key)).withStyle(ChatFormatting.YELLOW));
    }

    private void addCustomButton(Component label, int x, int y, int width, int height, Runnable onPress) {
        PackControlButton button = new PackControlButton(x, y, width, height, label, onPress);
        widgets.add(addRenderableWidget(button));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.fill(0, 6, width, 9, BORDER_BRIGHT);
        graphics.fill(0, 10, width, 11, 0xFF2F376A);

        int left = contentLeft();
        int top = contentTop();
        int contentWidth = contentWidth();
        int contentHeight = contentHeight();
        int leftWidth = leftPanelWidth();
        int gap = 16;
        int rightLeft = left + leftWidth + gap;
        int rightWidth = contentWidth - leftWidth - gap;
        int panelTop = top + 38;
        int panelHeight = contentHeight - 48;

        drawTitle(graphics, left, top, contentWidth);
        drawPanel(graphics, left, panelTop, leftWidth, panelHeight, "packcontrol.panel.actions");
        drawPanel(graphics, rightLeft, panelTop, rightWidth, panelHeight, "packcontrol.panel.status");

        drawActionHints(graphics, left, top + 262, leftWidth);
        drawStatusPanel(graphics, rightLeft + 20, top + 78, rightWidth - 40);
        drawFuturePanels(graphics, rightLeft + 20, top + 174, rightWidth - 40);
        drawActivity(graphics, rightLeft + 20, top + 290, rightWidth - 40);

        for (Renderable renderable : widgets) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    public void renderTransparentBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
    }

    private void drawTitle(GuiGraphics graphics, int left, int top, int contentWidth) {
        graphics.drawCenteredString(font, title, left + contentWidth / 2, top + 8, TEXT);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.screen.subtitle"), left + contentWidth / 2, top + 24, MUTED);
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int w, int h, String titleKey) {
        graphics.fill(x, y, x + w, y + h, PANEL);
        graphics.fill(x, y, x + w, y + 1, BORDER);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF0B1020);
        graphics.fill(x, y, x + 1, y + h, BORDER);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF0B1020);
        graphics.fill(x, y + 38, x + w, y + 39, BORDER);
        graphics.drawCenteredString(font, Component.translatable(titleKey), x + w / 2, y + 15, TEXT);
    }

    private void drawActionHints(GuiGraphics graphics, int x, int y, int width) {
        drawMiniCard(graphics, x + 22, y, width - 44, 58, "packcontrol.card.sync.title", "packcontrol.card.sync.body", WARNING);
        drawMiniCard(graphics, x + 22, y + 68, width - 44, 58, "packcontrol.card.github.title", "packcontrol.card.github.body", GOOD);
    }

    private void drawMiniCard(GuiGraphics graphics, int x, int y, int w, int h, String titleKey, String bodyKey, int color) {
        graphics.fill(x, y, x + w, y + h, PANEL_DARK);
        graphics.fill(x, y, x + 3, y + h, color);
        graphics.drawString(font, Component.translatable(titleKey), x + 10, y + 9, TEXT, false);
        drawWrapped(graphics, Component.translatable(bodyKey).getString(), x + 10, y + 24, w - 18, MUTED, 2);
    }

    private void drawStatusPanel(GuiGraphics graphics, int x, int y, int width) {
        drawRow(graphics, x, y, width, "packcontrol.status.installed", state.installedVersion(), WARNING);
        drawRow(graphics, x, y + 15, width, "packcontrol.status.latest", state.latestVersion(), GOOD);
        drawRow(graphics, x, y + 30, width, "packcontrol.status.channel", state.channel(), TEXT);
        drawRow(graphics, x, y + 45, width, "packcontrol.status.branch", state.branch(), TEXT);
        drawRow(graphics, x, y + 60, width, "packcontrol.status.sync", state.syncStatus(), WARNING);
        drawRow(graphics, x, y + 75, width, "packcontrol.status.last_check", state.lastCheck(), MUTED);
    }

    private void drawFuturePanels(GuiGraphics graphics, int x, int y, int width) {
        int half = Math.max(150, (width - 12) / 2);
        drawFutureBox(graphics, x, y, half, "packcontrol.github.title", List.of(
                row("packcontrol.github.repository", state.repository()),
                row("packcontrol.github.releases", state.releases()),
                row("packcontrol.github.commits", state.commits()),
                row("packcontrol.github.issues", state.issues()),
                row("packcontrol.github.account", state.accountStatus())
        ));
        drawFutureBox(graphics, x + half + 12, y, width - half - 12, "packcontrol.packwiz.title", List.of(
                row("packcontrol.packwiz.manifest", state.manifestPath()),
                row("packcontrol.packwiz.index", state.indexStatus()),
                row("packcontrol.packwiz.mods", state.modCount()),
                row("packcontrol.packwiz.optional", state.optionalFiles()),
                row("packcontrol.packwiz.hashes", state.hashStatus())
        ));
    }

    private String row(String key, String value) {
        return Component.translatable(key).getString() + ": " + value;
    }

    private void drawFutureBox(GuiGraphics graphics, int x, int y, int w, String titleKey, List<String> rows) {
        graphics.fill(x, y, x + w, y + 92, PANEL_DARK);
        graphics.fill(x, y, x + w, y + 1, BORDER);
        graphics.drawString(font, Component.translatable(titleKey), x + 9, y + 8, TEXT, false);
        graphics.drawString(font, Component.translatable("packcontrol.badge.planned"), x + w - 52, y + 8, WARNING, false);

        int lineY = y + 25;
        for (String line : rows) {
            graphics.drawString(font, fit(line, w - 18), x + 9, lineY, MUTED, false);
            lineY += 12;
        }
    }

    private void drawActivity(GuiGraphics graphics, int x, int y, int width) {
        graphics.drawString(font, Component.translatable("packcontrol.panel.activity"), x, y, TEXT, false);
        int lineY = y + 18;
        for (String entry : state.activity()) {
            graphics.drawString(font, "- " + fit(entry, width), x, lineY, MUTED, false);
            lineY += 12;
        }
    }

    private void drawRow(GuiGraphics graphics, int x, int y, int width, String labelKey, String value, int valueColor) {
        String label = Component.translatable(labelKey).getString() + ":";
        graphics.drawString(font, label, x, y, MUTED, false);
        graphics.drawString(font, fit(value, width - font.width(label) - 8), x + font.width(label) + 8, y, valueColor, false);
    }

    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int width, int color, int maxLines) {
        String remaining = text;
        for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
            String part = fit(remaining, width);
            graphics.drawString(font, part, x, y + line * 11, color, false);
            if (part.length() >= remaining.length()) {
                return;
            }
            remaining = remaining.substring(part.endsWith("...") ? Math.max(0, part.length() - 3) : part.length()).trim();
        }
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }

        String suffix = "...";
        int suffixWidth = font.width(suffix);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (font.width(builder.toString() + c) + suffixWidth > maxWidth) {
                break;
            }
            builder.append(c);
        }
        return builder + suffix;
    }

    private int contentWidth() {
        return Math.min(width - 28, 1192);
    }

    private int contentHeight() {
        return Math.min(height - 24, 640);
    }

    private int contentLeft() {
        return (width - contentWidth()) / 2;
    }

    private int contentTop() {
        return Math.max(10, (height - contentHeight()) / 2);
    }

    private int leftPanelWidth() {
        return Math.max(250, Math.min(300, contentWidth() / 3));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private static final class PackControlButton extends AbstractWidget {
        private final Runnable onPress;

        private PackControlButton(int x, int y, int width, int height, Component message, Runnable onPress) {
            super(x, y, width, height, message);
            this.onPress = onPress;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (active) {
                onPress.run();
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHoveredOrFocused();
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int fill = hovered ? BUTTON_HOVER : BUTTON;
            int textColor = active ? TEXT : 0xFF697086;

            graphics.fill(x, y, x + w, y + h, fill);
            graphics.fill(x, y, x + w, y + 1, hovered ? BUTTON_ACCENT : BUTTON_BORDER);
            graphics.fill(x, y + h - 1, x + w, y + h, 0xFF101725);
            graphics.fill(x, y, x + 1, y + h, hovered ? BUTTON_ACCENT : BUTTON_BORDER);
            graphics.fill(x + w - 1, y, x + w, y + h, 0xFF101725);
            graphics.fill(x + 3, y + 4, x + 5, y + h - 4, hovered ? GOOD : BUTTON_ACCENT);
            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(), x + w / 2, y + (h - 8) / 2, textColor);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}