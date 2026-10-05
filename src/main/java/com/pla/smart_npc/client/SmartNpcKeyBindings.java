package com.pla.smart_npc.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side key bindings registered through the vanilla controls menu,
 * so players can rebind them in Options -> Controls (Fabric port addition).
 *
 * <p>The inspectator ("NPC view") toggle was previously hard-wired to the
 * physical Left/Right Alt keys. It is now a regular {@link KeyMapping}
 * (default: Left Alt) that shows up in the key binding screen.</p>
 */
public final class SmartNpcKeyBindings {
    /** Translation id of the toggle key binding; also used by {@code Component.keybind(...)} references. */
    public static final String TOGGLE_INSPECTATOR_ID = "key.smart_npc.toggle_inspectator";
    /** Translation id of the "Smart NPC" category shown in the controls screen. */
    public static final String CATEGORY_ID = "key.categories.smart_npc";

    private static KeyMapping toggleInspectator;

    private SmartNpcKeyBindings() {
    }

    /** Registers the key bindings; called from the client entrypoint. */
    public static void register() {
        toggleInspectator = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                TOGGLE_INSPECTATOR_ID,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                CATEGORY_ID));
    }

    /** @return the registered toggle mapping, or {@code null} before {@link #register()}. */
    public static KeyMapping toggleInspectator() {
        return toggleInspectator;
    }

    /** @return whether the configurable toggle key is currently held down. */
    public static boolean isToggleInspectatorDown() {
        return toggleInspectator != null && toggleInspectator.isDown();
    }

    /**
     * @return display name of the currently bound toggle key (e.g. "Left Alt"),
     *         or {@code null} when the mapping has not been registered yet.
     */
    public static String toggleInspectatorKeyName() {
        if (toggleInspectator == null) {
            return null;
        }
        Component name = toggleInspectator.getTranslatedKeyMessage();
        return name != null ? name.getString() : null;
    }
}
