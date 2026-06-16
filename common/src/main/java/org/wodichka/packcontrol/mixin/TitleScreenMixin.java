package org.wodichka.packcontrol.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wodichka.packcontrol.client.PackControlScreen;

@Environment(EnvType.CLIENT)
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    private static final int PACKCONTROL_BUTTON_WIDTH = 28;
    private static final int PACKCONTROL_BUTTON_HEIGHT = 20;
    private static final int PACKCONTROL_BUTTON_GAP = 4;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void packcontrol$addMainMenuButton(CallbackInfo ci) {
        int x = width / 2 + 104;
        int y = height / 4 + 48 + 72;

        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget widget && packcontrol$isModsButton(widget)) {
                x = widget.getX() + widget.getWidth() + PACKCONTROL_BUTTON_GAP;
                y = widget.getY();
                break;
            }
        }

        if (x + PACKCONTROL_BUTTON_WIDTH > width - 4) {
            x = width - PACKCONTROL_BUTTON_WIDTH - 4;
        }

        addRenderableWidget(Button.builder(Component.translatable("packcontrol.main_menu.button"), button ->
                Minecraft.getInstance().setScreen(new PackControlScreen((Screen) (Object) this))
        ).bounds(x, y, PACKCONTROL_BUTTON_WIDTH, PACKCONTROL_BUTTON_HEIGHT).build());
    }

    private static boolean packcontrol$isModsButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        if (message.getContents() instanceof TranslatableContents contents) {
            String key = contents.getKey();
            if ("fml.menu.mods".equals(key) || "menu.mods".equals(key) || "modmenu.title".equals(key)) {
                return true;
            }
        }

        String text = message.getString().trim();
        return "Mods".equalsIgnoreCase(text) || "Mod List".equalsIgnoreCase(text);
    }
}