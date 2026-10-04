package com.pla.smart_npc;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.config.SmartNpcNamesConfig;
import com.pla.smart_npc.event.SmartNpcEvents;
import com.pla.smart_npc.init.SmartNpcModCreativeTabs;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.init.SmartNpcModItems;
import com.pla.smart_npc.init.SmartNpcModMenus;
import com.pla.smart_npc.network.SmartNpcNetwork;
import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fabric entrypoint of Smart NPC.
 *
 * <p>This is a port of the Forge 1.20.1 edition. The optional Epic Fight and
 * Combat Evolution integrations of the Forge build are not present here because
 * those mods do not ship a Fabric edition; the Better Combat bridge is kept and
 * stays fully reflection-based, so it activates automatically when a Fabric port
 * of Better Combat is installed.</p>
 */
public class SmartNpc implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger(SmartNpc.class);
    public static final String MODID = "smart_npc";

    @Override
    public void onInitialize() {
        // Forge registered TOML specs during mod construction; Fabric loads the JSON
        // equivalents before any registration so biome spawn weights are available.
        SmartNpcConfig.load();
        SmartNpcNamesConfig.load();

        // Order matters: entity types first (the spawn egg references them).
        SmartNpcModEntities.register();
        SmartNpcModItems.register();
        SmartNpcModMenus.register();
        SmartNpcModCreativeTabs.register();

        SmartNpcNetwork.register();

        SmartNpcEvents.register();
    }
}
