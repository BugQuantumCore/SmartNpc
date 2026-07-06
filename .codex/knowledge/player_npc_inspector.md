# Player NPC Inspector Item

## Source Files

- `src/main/java/com/pla/player_npc/item/InventoryViewerItem.java`
- `src/main/java/com/pla/player_npc/client/gui/PlayerNpcInspectorOverlay.java`
- `src/main/java/com/pla/player_npc/network/PlayerNpcInspectorData.java`
- `src/main/java/com/pla/player_npc/network/PlayerNpcInspectorPacket.java`
- `src/main/java/com/pla/player_npc/network/PlayerNpcInspectorRequestPacket.java`
- `src/main/java/com/pla/player_npc/network/PlayerNpcInspectatorModePacket.java`
- `src/main/java/com/pla/player_npc/event/PlayerNpcInspectatorEvent.java`
- `src/main/java/com/pla/player_npc/network/PlayerNpcNetwork.java`
- `src/main/resources/assets/player_npc/lang/en_us.json`

## Item Behavior

The inspector item is implemented by `InventoryViewerItem`. It stacks to one item.

Right-clicking a `PlayerNpcEntity` sends a server-authoritative `PlayerNpcInspectorPacket` to the interacting `ServerPlayer`. The packet contains the target entity id, an inventory snapshot from `PlayerNpcInspectorData.createSnapshot(...)`, and server-generated build status text from `PlayerNpcInspectorData.createBuildStatusText(...)`.

Right-clicking a non-Player NPC shows `message.player_npc.inspector.unsupported`. Right-clicking air with the item sends `PlayerNpcInspectorPacket.clear()`, which closes the overlay and also exits inspectator mode if it is active.

The snapshot order is stable:

- main hand
- offhand
- head
- chest
- legs
- feet
- all 27 custom inventory slots

Do not read the custom inventory directly on the client. The overlay should display the latest snapshot sent by the server.

## AI Inspection Overlay

`PlayerNpcInspectorOverlay` is a HUD overlay, not a normal screen. It renders from `RenderGuiEvent.Post`, so the player can keep normal world context while inspecting the NPC.

The overlay displays:

- inspected NPC name
- health and max health
- synced AI state from `PlayerNpcEntity.getCurrentAiState()`
- interest profile from `PlayerNpcEntity.getInterestsDisplayText()`
- build/home status text from the latest inspector packet
- synced task detail from `PlayerNpcEntity.getCurrentAiDetail()`
- main-hand item
- equipment slots
- custom inventory slots

If the detail string is empty, the overlay falls back to `ai.player_npc.looking_for_work` while the state is idle, otherwise it displays the translated AI state as the task text.

Inventory snapshots refresh through `PlayerNpcInspectorRequestPacket` every 10 game ticks. The server validates that the entity is still a live `PlayerNpcEntity` and that the player is within 64 blocks before sending a fresh snapshot; otherwise it sends a clear packet.

Formatted display text is cached for 500 ms. Keep this throttling for FPS-sensitive UI, similar to Minecraft's F3 debug screen pattern.

## Inspectator View

While the inspector overlay is open, holding E for the configured short hold threshold toggles inspectator view. The hold only triggers the toggle; the player does not need to keep holding E after mode changes. While the inspector overlay is active and E is being held for inspectator, vanilla inventory opening is canceled/drained so the hold can complete.

Entering inspectator view does all of these:

- stores the previous client camera type and camera entity
- sends `PlayerNpcInspectatorModePacket(true, entityId)` to the server
- sets the client camera entity to the local riding player
- sets the initial camera type to `CameraType.THIRD_PERSON_FRONT`
- keeps/grabs the mouse so normal camera look rotates the player's head while riding the NPC

While active, the client tick keeps the camera attached to the riding player, which is server-forced to ride the inspected NPC, and suppresses movement/use/attack/drop/pick/jump/inventory inputs. Mouse look is not suppressed in third person, so the player can spin their head/camera. It does not force the camera type after entry; F5 remains free so the player can cycle first person, third-person back, and third-person front while following the NPC. A/D or left/right arrows cycle to another nearby Player NPC within 64 blocks.

The client allows `ChatScreen` to stay open during inspectator mode, so pressing T can open chat without exiting the mode. While chat is open, inspectator hotkeys are ignored so typing does not cycle NPCs or adjust zoom, but the camera remains attached and server-side riding/spectator state stays active.

Third-person front/back camera distance is adjustable with the physical up/down arrow keys while inspectator mode is active. `CameraMixin` patches the vanilla third-person camera setup distance by calling `PlayerNpcInspectorOverlay.getInspectatorCameraDistance(...)`. First-person mode returns vanilla distance and the client tick locks the local riding player's yaw/pitch back to the inspected NPC so first-person does not become a free-spinning orbit camera.

Because the camera entity is the local riding player, vanilla third-person rendering would normally draw the local player's own head/body in front of the inspectator camera. `PlayerRendererMixin` cancels rendering only for `minecraft.player` while inspectator mode is active. This preserves the inspected `PlayerNpcEntity` render and avoids showing the observer's head/body.

Exiting inspectator view restores the previous camera entity and camera type, grabs the mouse again when no screen is open, and sends `PlayerNpcInspectatorModePacket(false, -1)`.

## Server-Side Mode Patch

`PlayerNpcInspectatorModePacket` makes inspectator mode server-authoritative.

When starting, the server validates that the requested entity is a live `PlayerNpcEntity` within 96 blocks. It stores the player's original game mode in persistent data, sets the player to spectator, and forces the player to ride the NPC.

When restoring, it removes the inspectator active/original-game-mode persistent keys before calling `stopRiding()`. This ordering matters: `PlayerNpcInspectatorEvent` cancels normal dismounts while inspectator is active, so the active flag must be cleared before the intentional exit dismount.

`PlayerNpcInspectatorEvent.onEntityMount(...)` cancels dismount attempts when:

- the event is a dismount,
- the mounting entity is a `ServerPlayer`,
- the mounted entity is a `PlayerNpcEntity`,
- `PlayerNpcInspectatorModePacket.isInspectatorActive(serverPlayer)` is true.

This blocks vanilla Shift dismount while inspectator mode is active. `MovementInputUpdateEvent` and `suppressPlayerInput(...)` also clear client-side shift/movement state so the player cannot drive or dismount the NPC locally.

`RenderGuiOverlayEvent.Pre` cancels the vanilla jump bar overlay while inspectator mode is active. `GuiMixin` also cancels the vanilla `mount.onboard` overlay message only while inspectator mode is active, hiding the "Press LSHIFT to dismount" pop-up for this mode.

Logout calls `PlayerNpcInspectatorModePacket.restorePlayer(...)` so a player should not remain stuck in spectator after disconnecting during inspectator mode.

## Maintenance Notes

Keep `PlayerNpcInspectorPacket.clear()` connected to `stopInspectator(...)`; clearing the inspector must also leave inspectator view.

Do not convert inspectator view into a normal GUI screen unless the riding-player camera attachment, grabbed-mouse camera control, chat-screen allowance, F5 camera freedom, third-person zoom, input suppression, and server dismount guard are preserved.

Do not re-add a per-tick `CameraType.THIRD_PERSON_FRONT` force in active mode. The camera may start in third-person front, but F5 must remain user-controlled after activation.

If cycling between NPCs changes, keep the server refresh request and local `inspectedEntityId` update together so the overlay and camera target do not desync.
