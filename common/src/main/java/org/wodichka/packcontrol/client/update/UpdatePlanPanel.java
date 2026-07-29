package org.wodichka.packcontrol.client.update;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

public final class UpdatePlanPanel {
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
        graphics.drawString(font, "What will change", x + 14, y + 12, UpdatePanelSupport.TEXT, false);
        PackUpdateSummary summary = model.summary();
        if (summary == null) {
            graphics.drawString(
                    font,
                    "Run an update check to build a safe installation plan.",
                    x + 14,
                    y + 34,
                    UpdatePanelSupport.MUTED,
                    false
            );
            return;
        }

        String counts = "Add " + summary.added().size()
                + "   Update " + summary.updated().size()
                + "   Remove " + summary.removed().size()
                + "   Keep " + summary.kept().size();
        graphics.drawString(font, counts, x + 14, y + 30, UpdatePanelSupport.TEXT, false);
        graphics.drawString(
                font,
                "Download " + UpdatePanelSupport.readableSize(summary.downloadSize()),
                x + width - 150,
                y + 30,
                UpdatePanelSupport.GOOD,
                false
        );

        int columnGap = 8;
        int columnWidth = (width - 28 - columnGap * 3) / 4;
        drawColumn(graphics, font, "Added", summary.added(), x + 14, y + 51, columnWidth,
                UpdatePanelSupport.GOOD);
        drawColumn(graphics, font, "Updated", summary.updated(), x + 14 + columnWidth + columnGap,
                y + 51, columnWidth, UpdatePanelSupport.WARNING);
        drawColumn(graphics, font, "Removed", summary.removed(),
                x + 14 + (columnWidth + columnGap) * 2, y + 51, columnWidth, UpdatePanelSupport.ERROR);
        drawColumn(graphics, font, "Kept", summary.kept(),
                x + 14 + (columnWidth + columnGap) * 3, y + 51, columnWidth, UpdatePanelSupport.MUTED);

        int changelogY = y + 124;
        graphics.drawString(font, "Changelog", x + 14, changelogY, UpdatePanelSupport.TEXT, false);
        List<String> lines = UpdatePanelSupport.wrap(font, model.changelog(), width - 28, 6);
        int lineY = changelogY + 17;
        for (String line : lines) {
            graphics.drawString(font, line, x + 14, lineY, UpdatePanelSupport.MUTED, false);
            lineY += 12;
        }
    }

    private static void drawColumn(
            GuiGraphics graphics,
            Font font,
            String title,
            List<String> paths,
            int x,
            int y,
            int width,
            int color
    ) {
        graphics.fill(x, y, x + width, y + 62, UpdatePanelSupport.PANEL_DARK);
        graphics.drawString(font, title + " (" + paths.size() + ")", x + 7, y + 7, color, false);
        int rowY = y + 23;
        for (int index = 0; index < Math.min(3, paths.size()); index++) {
            graphics.drawString(
                    font,
                    UpdatePanelSupport.fit(font, paths.get(index), width - 14),
                    x + 7,
                    rowY,
                    UpdatePanelSupport.MUTED,
                    false
            );
            rowY += 11;
        }
        if (paths.size() > 3) {
            graphics.drawString(font, "+" + (paths.size() - 3) + " more", x + 7, rowY,
                    UpdatePanelSupport.MUTED, false);
        }
    }
}
