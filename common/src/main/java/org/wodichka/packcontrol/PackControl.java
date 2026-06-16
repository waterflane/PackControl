package org.wodichka.packcontrol;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class PackControl {
    public static final String MOD_ID = "packcontrol";
    public static final String MOD_NAME = "PackControl";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized;

    private PackControl() {
    }

    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;
        LOGGER.info("PackControl UI scaffold initialized");
    }
}
