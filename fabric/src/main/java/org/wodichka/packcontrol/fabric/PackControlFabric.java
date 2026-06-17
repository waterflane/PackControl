package org.wodichka.packcontrol.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;

public final class PackControlFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        PackControl.init();
        PackControlConfig.load(FabricLoader.getInstance().getConfigDir(), FabricLoader.getInstance().getGameDir());
    }
}