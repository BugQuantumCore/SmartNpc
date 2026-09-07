# ThrowTrashItemsGoal

## Source And Registration

- `src/main/java/com/pla/smart_npc/entity/goal/ThrowTrashItemsGoal.java`
- Registered directly at priority 3 from `PlayerNpcEntity.registerGoals()` as bounded
  personal maintenance. It does not consume a routine worker slot.

## Admission

The goal starts when at least 25 inventory slots are occupied and an eligible trash stack
plus a loaded collision-safe disposal point are available. A completely full inventory gets
a cheap emergency eligibility pass every 20 ticks; other pressure checks retain the staggered
100-tick throttle. This keeps the full-inventory crafting/resource deadlock fix.

Eligibility always delegates to `PlayerNpcTrashUtil.isTrash`, which protects required job
materials, useful supplies, valuable items, equipped hands, and other reserved inventory.
Slot occupancy means non-empty slots, regardless of each stack's item count.

Selection is priority-ranked. Explicit junk is chosen before obsolete tiered tools, followed by
a narrow overflow policy for plain duplicate bows, water buckets beyond one, and ordinary arrows
that can be removed while leaving at least 64. Ender pearls are the final, lowest-priority overflow
tier and can only be removed while leaving at least 16. The policy recalculates after every throw,
prefers smaller removable arrow/pearl stacks, and retains another usable bow plus one water bucket.
Food, building stock, ores, named/enchanted items, and unknown/modded items never become overflow.
This lets the reported 27/27 inventory reach the fixed 20-slot release target without broadly
labeling useful resources as trash.

## Clearing Session

One activation is a continuous clearing session. It throws one revalidated trash stack every
10 ticks until at most 20 inventory slots are occupied (clamped to the container size). The
fixed stop point preserves the requested 25-slot pressure admission and 20-slot release target
even though the current Player NPC container has 27 slots. If fewer eligible trash stacks exist,
the session stops when only protected/required stacks remain.

Each ItemEntity is spawned before its inventory slot is cleared. A rejected spawn keeps the
original stack and ends the session, so cleanup cannot duplicate or lose inventory contents.
The disposal point and slot eligibility are recomputed for each throw because a session lasts
longer than the former single-stack action.

The `throw_trash` datapack event is emitted after the first successful throw only. Later items
in the same activation still receive their normal swing and delay without repeating chat.

Every successful throw refreshes a per-NPC item-pickup suppression to 30 seconds, so the full
window begins after the final drop. The transient counter is not persisted; the discarded-stack
marker is persisted on each spawned ItemStack and remains the permanent loop-prevention rule.
`PickupNearbyItemGoal` rejects admission and continuation before target scans/path work while the
counter is active, and Player NPC contact pickup plus combat ground-weapon recovery honor it too.
Marked stacks are permanently rejected by all pickup/equip commits.

`BurnNearbyItemGoal` treats marked stacks only as disposal targets: it may walk to them and place
fire using its normal safety checks, but it cannot reserve, equip, or insert them back into the
inventory. Ordinary collect/equip candidates are skipped during the suppression window. Burning
marked trash does not emit a second `burn_item` chat after the session's `throw_trash` message.

Trash selection builds one inventory reserve profile per pass (bucket, bow, arrow, and pearl
totals) instead of rescanning all slots for each candidate. Full-inventory emergency polling stays
at 20 ticks, but its normal reserve classification is now linear apart from the small obsolete-tool
comparison, avoiding the former repeated count scans.

After the final throw, the existing short landing delay and optional flint-and-steel burn pass
apply to the session's spawned entities. Interruptions restore idle state and clear only the
goal's references; they do not remove successfully spawned world items.
