package org.wodichka.packcontrol.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;

@Mod(PackControl.MOD_ID)
public final class PackControlForge {
    public PackControlForge() {
        PackControl.init();
        PackControlConfig.load(FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get());
    }
}