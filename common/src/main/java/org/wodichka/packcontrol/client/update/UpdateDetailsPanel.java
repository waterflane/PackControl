package org.wodichka.packcontrol.client.update;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

public final class UpdateDetailsPanel {
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
        graphics.drawString(font, "Advanced details", x + 14, y + 12, UpdatePanelSupport.TEXT, false);
        List<String> rows = new ArrayList<>();
        if (model.summary() != null && !model.summary().locallyModified().isEmpty()) {
            rows.add("Locally changed managed files:");
            model.summary().locallyModified().forEach(path -> rows.add("  " + path));
        }
        rows.addAll(model.details());
        if (rows.isEmpty()) {
            rows.add("No warnings or local modifications were detected.");
        }
        int rowY = y + 33;
        for (String row : rows) {
            if (rowY > y + height - 16) {
                graphics.drawString(font, "More details omitted.", x + 14, rowY,
                        UpdatePanelSupport.MUTED, false);
                break;
            }
            int color = row.startsWith("Error:")
                    ? UpdatePanelSupport.ERROR
                    : row.startsWith("Warning:") || row.startsWith("Locally")
                    ? UpdatePanelSupport.WARNING
                    : UpdatePanelSupport.MUTED;
            graphics.drawString(
                    font,
                    UpdatePanelSupport.fit(font, row, width - 28),
                    x + 14,
                    rowY,
                    color,
                    false
            );
            rowY += 12;
        }
    }
}
