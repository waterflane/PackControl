package org.wodichka.packcontrol.client.update;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

final class UpdatePanelSupport {
    static final int PANEL = 0xE0182033;
    static final int PANEL_DARK = 0xE0111725;
    static final int BORDER = 0xFF3B496B;
    static final int TEXT = 0xFFE7E9F3;
    static final int MUTED = 0xFF9EA7BC;
    static final int GOOD = 0xFF72E0A2;
    static final int WARNING = 0xFFFFD863;
    static final int ERROR = 0xFFFF7D7D;

    private UpdatePanelSupport() {
    }

    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL);
        graphics.fill(x, y, x + width, y + 1, BORDER);
        graphics.fill(x, y, x + 1, y + height, BORDER);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF101725);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF101725);
    }

    static String fit(Font font, String value, int width) {
        String text = value == null ? "" : value;
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    static List<String> wrap(Font font, String value, int width, int maximumLines) {
        List<String> lines = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return List.of();
        }
        for (String paragraph : value.replace("\r", "").split("\n")) {
            String remaining = paragraph.trim();
            if (remaining.isEmpty()) {
                continue;
            }
            while (!remaining.isEmpty() && lines.size() < maximumLines) {
                String line = font.plainSubstrByWidth(remaining, width);
                if (line.isEmpty()) {
                    break;
                }
                int split = line.length();
                if (split < remaining.length()) {
                    int space = line.lastIndexOf(' ');
                    if (space > 0) {
                        split = space;
                        line = line.substring(0, space);
                    }
                }
                lines.add(line.trim());
                remaining = remaining.substring(Math.min(remaining.length(), split)).trim();
            }
            if (lines.size() >= maximumLines) {
                break;
            }
        }
        return List.copyOf(lines);
    }

    static String readableSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        double kib = size / 1024.0;
        if (kib < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        if (mib < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }
}
