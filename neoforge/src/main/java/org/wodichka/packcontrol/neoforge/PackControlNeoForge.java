package org.wodichka.packcontrol.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;

@Mod(PackControl.MOD_ID)
public final class PackControlNeoForge {
    public PackControlNeoForge() {
        PackControl.init();
        PackControlConfig.load(FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get());
    }
}