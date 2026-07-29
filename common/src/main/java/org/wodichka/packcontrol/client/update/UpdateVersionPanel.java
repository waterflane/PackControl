package org.wodichka.packcontrol.client.update;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class UpdateVersionPanel {
    public void render(
            GuiGraphics graphics,
            Font font,
            PackUpdateViewModel model,
            int x,
            int y,
            int width,
            int height
    ) {
        UpdatePanelSupport.panel(graphics, x, y, width, height);
        int cardWidth = (width - 42) / 2;
        drawVersionCard(graphics, font, x + 14, y + 13, cardWidth, "Installed", model.installedVersion(), false);
        drawVersionCard(
                graphics,
                font,
                x + 28 + cardWidth,
                y + 13,
                cardWidth,
                "Available",
                model.availableVersion(),
                model.stage() == PackUpdateViewModel.Stage.UPDATE_AVAILABLE
                        || model.stage() == PackUpdateViewModel.Stage.READY_TO_RESTART
        );
        int messageColor = model.stage() == PackUpdateViewModel.Stage.ERROR
                ? UpdatePanelSupport.ERROR
                : model.stage() == PackUpdateViewModel.Stage.UP_TO_DATE
                ? UpdatePanelSupport.GOOD
                : UpdatePanelSupport.WARNING;
        graphics.drawCenteredString(
                font,
                UpdatePanelSupport.fit(font, model.message(), width - 30),
                x + width / 2,
                y + height - 19,
                messageColor
        );
        if (model.busy()) {
            int progressWidth = Math.max(20, (int) ((System.currentTimeMillis() / 8L) % (width - 28)));
            graphics.fill(x + 14, y + height - 7, x + width - 14, y + height - 4, 0xFF0B1020);
            graphics.fill(x + 14, y + height - 7, x + 14 + progressWidth, y + height - 4,
                    UpdatePanelSupport.WARNING);
        }
    }

    private static void drawVersionCard(
            GuiGraphics graphics,
            Font font,
            int x,
            int y,
            int width,
            String label,
            String version,
            boolean highlighted
    ) {
        graphics.fill(x, y, x + width, y + 38, UpdatePanelSupport.PANEL_DARK);
        graphics.fill(x, y, x + 3, y + 38,
                highlighted ? UpdatePanelSupport.GOOD : UpdatePanelSupport.BORDER);
        graphics.drawString(font, label, x + 10, y + 7, UpdatePanelSupport.MUTED, false);
        graphics.drawString(
                font,
                UpdatePanelSupport.fit(font, version, width - 20),
                x + 10,
                y + 21,
                highlighted ? UpdatePanelSupport.GOOD : UpdatePanelSupport.TEXT,
                false
        );
    }
}
