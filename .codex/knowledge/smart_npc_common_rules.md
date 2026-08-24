# Smart NPC Common Modding Rules

## Project Baseline

- Smart NPC targets Minecraft Forge `1.20.1` with Forge `47.4.4`, Parchment `2023.06.26-1.20.1`, and Java `17`.
- Production Java packages use `com.pla.smart_npc`. Some older knowledge notes still mention the pre-rename `annoyingvillagers` package; verify paths against the current source tree before editing.
- Epic Fight is an optional compatibility boundary. Generic Smart NPC code must call `EpicFightCompat`; only classes under `compat.epicfight` may directly link Epic Fight types.

## AI Design Pattern

- Keep goal selection, lifecycle, and goal-local plans in focused classes under `entity.goal`.
- Put reusable mechanics under the flat `entity.ai` package and suffix helper class names with `Ai`.
- Keep `PlayerNpcEntity` focused on entity-owned state, synchronized animation state, generic hand/inventory operations, persistence, cooldown ticking, and goal registration. Do not add large job state machines there.
- A goal must revalidate destructive work at execution time, not only in `canUse()`. Combat, healing, death, no-AI, passenger state, changed blocks, changed ownership, and invalid targets can occur after planning.
- Use goal flags deliberately. Work that navigates or turns the head normally owns `MOVE` and `LOOK`; priority alone does not prevent incompatible goals from running together.
- Work navigation speed must not exceed `1.0D`. Prefer reachable stand positions and bounded path checks; never use an unbounded random walk or destructive stuck fallback.

## World Ownership And Destructive Work

- Persist owned temporary world objects with enough identity to recover safely: position, dimension, and an owner marker where the block entity supports it.
- Never destroy a block merely because it is near an NPC. Validate the exact owned plan/reference immediately before breaking or replacing it.
- Treat homes, build footprints, farm irrigation, farm entrances, crop soil, and other job-owned infrastructure as protected volumes. Shared generic goals such as bucket filling and obstruction clearing must query the owning job utility rather than duplicating geometry.
- Never destroy an immature crop. Harvest logic must check the current block is an accepted crop and is at maximum age again on the action tick.

## Inventory, Tools, And Durability

- Use `ToolAi` for temporary work-tool swaps and restoration. Use guarded Player NPC hand setters so a work tool does not overwrite cached combat gear.
- Use `BreakingBlockAi` for ordinary timed breaking when its semantics fit; it owns tool selection, hardness timing, crack progress, sounds, vanilla swing, Epic Fight digging, durability, and optional work sneaking.
- When returning an item to a potentially full inventory, preserve the actual remainder. `InventoryUtils.addItem(...)` returns only success/failure and can partially mutate the inventory; do not drop the original stack after a partial insertion. Use an API that returns the remaining stack or simulate capacity first.
- Apply durability to the item actually used for the work. Restore temporary hands on every normal, failed, or preempted stop path.

## Animation Compatibility

- Route visible work through one renderer-appropriate action signal. Atomic actions must not start both the vanilla swing and a separate Epic Fight animation for the same commit.
- Distinguish timed continuous work from atomic commits. Breaking may keep the repeating Epic Fight digging motion alive across progress ticks and must clear it on abort. Block placement, bucket use, hoe tilling, planting, and bone meal keep their delay animation-free, then emit exactly one normal NPC use swing or one dedicated non-repeating Epic Fight use animation only after the world mutation succeeds.
- Optional compatibility must be a no-op when the external mod is absent and must not introduce external classes into generally loaded code.

## Persistence And Cooldowns

- Persistent AI cooldowns are integer fields on `PlayerNpcEntity`, saved/loaded with the entity and decremented in the centralized cooldown tick path. Do not add ad hoc persistent-data deadline tags for cooldowns.
- Persistent job plans belong in one shared utility per job. Readers, builders, operational goals, protection predicates, and migration logic must use that API instead of copying NBT keys or geometry.

## Collaboration And Verification

- Preserve unrelated dirty-worktree changes. Inspect the focused diff before and after editing shared files.
- When behavior changes, update its `.codex/knowledge` note. Add a goal note for a new goal.
- Use MineColonies, Structurize, Workers, and decompiled projects as behavior/design references only unless their license and the requested port explicitly permit code reuse.
- Run focused compilation or tests when the user requests verification or the coordinating task explicitly requires it; otherwise follow the active task's verification scope.
