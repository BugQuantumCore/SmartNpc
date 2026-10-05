package com.pla.smart_npc.client;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.client.gui.InventoryViewerScreen;
import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import com.pla.smart_npc.init.SmartNpcModEntityRenderers;
import com.pla.smart_npc.init.SmartNpcModMenus;
import com.pla.smart_npc.network.SmartNpcNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public class SmartNpcClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // FMLClientSetupEvent.enqueueWork(...) equivalent.
        SmartNpcModEntityRenderers.registerEntityRenderers();
        SmartNpcClientItemProperties.register();
        MenuScreens.register(SmartNpcModMenus.INVENTORY_VIEWER, InventoryViewerScreen::new);

        // Client-side packet receivers and HUD/overlay/key hooks.
        SmartNpcNetwork.registerClientReceivers();
        SmartNpcKeyBindings.register();
        SmartNpcInspectorOverlay.register();
    }
}
