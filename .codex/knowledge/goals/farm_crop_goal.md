# FarmCropGoal

## Purpose

Runs crop work only after `PlayerNpcFarmPlan` reaches `READY`. Farm creation and infrastructure repair belong to `FarmSetupGoal`.

The phase flag is not enough by itself: `FarmAi.isReadyForCropWork(...)` also requires valid irrigation, complete fence/gate infrastructure, healthy path support, and farmland in every exposed crop-ground cell. `canUse`, continuation, and supply exploration all share this check. Occupied crops are preserved through harvest; afterward any exposed dirt/grass/repaired support blocks Crop work and lets FarmSetup rewind to `TILL`, equip or craft the hoe, and restore farmland before planting resumes.

## Operations

- Harvest mature crops in the owned farm first.
- Plant wheat seeds, carrots, potatoes, or beetroot seeds in empty owned farmland.
- Gather reachable mature crops in the nearby world, including village wheat, carrot, potato, and beetroot fields, after owned planting work.
- Break reachable grass/ferns through `BreakingBlockAi` when planting material is missing.
- Craft/use bone meal only when an immature owned crop has a bounded reachable interaction stand. One bone meal deterministically advances that still-immature crop; a changed/mature target aborts before consumption, and a failed mutation returns the item.
- Ask `ExploreAroundGoal` to move to a new local area when the farm has empty cells and no actionable nearby crop/grass target exists.

Presence is not enough for exploration yielding: the shared nearby-supply query checks at most twelve exact reachable interaction stands and briefly caches the result so an unreachable crop cannot deadlock exploration. It excludes protected home supplies that the crop goal itself would reject. A reachable grass or mature-crop target therefore stops exploration within the next throttled continuation check.

The seed/crop `ExploreAroundGoal` registration permits the shared bounded upward escape request. When a seedless farmer is below the surface with no directly reachable surface exploration target, exploration records its mode/target and hands off to the existing pillar-up recovery for a nearby supported surface within the ten-block budget. Failed-climb fallback remains owned by that same seed/crop exploration registration, so unrelated exploration modes cannot consume it.

Required log supply has precedence only when the ready owned farm has neither actionable harvest/plant/bone-meal work nor empty farmland awaiting planting supplies. The authoritative `GatherLogsGoal.hasLogSupplyDemand(...)` still covers the rolled general reserve, farm setup/lighting demand, fishing-rod support, and active Building demand under the same daily-job rules used by GatherLogs itself. Once a bounded crop action starts, a gather cooldown expiring mid-route cannot cancel it; this avoids converting a reachable planting trip into the long generic failure cooldown. An active GatherLogs episode remains latched from goal `start` through `stop`, so crop work or seed exploration cannot steal an already selected tree route. With no active log episode, a seedless ready farm may forage a reachable local grass/crop target or explore for one despite passive log demand and the crop-action cooldown. This prevents the two supply paths from mutually excluding each other: the exploration yield predicate can hand a nearby target to FarmCrop without FarmCrop rejecting it for an unstarted log demand. `FarmSetupGoal` retains its higher work priority, and Building/home gates are unchanged.

Interaction stands treat farmland's non-full collision surface as valid support instead of requiring a full solid-render block. Both the exploration yield probe and the real crop selector require an exact path endpoint, so harvesting/foraging and later planting agree about reachability. After forage produces a seed, supply exploration becomes ineligible; the higher-priority crop goal returns to the READY farm and plants into empty tilled cells after its short action cooldown.

Planting rotates a bounded exact-path search across every empty planned crop cell before falling back to entrance clearing. A permanently unreachable nearest cell therefore cannot starve other tilled cells. Failed planting retries after a short one-to-two-second cooldown; completed mutations keep the existing sub-second cooldown.

That completed-action cooldown is pacing, not permission for farming support to leave the plot. `GatherStoneGoal` yields before start and during continuation whenever this goal reports actionable owned READY-farm work, allowing consecutive planting activations after each short cooldown without a safe-stone egress interruption.

`FarmStrollGoal` is a lower-priority daytime-only consumer of movement. It can select exact reachable saved path cells or a bounded safe ring around the farm only after every planned crop cell contains a crop and no owned harvest/plant/bone-meal action is currently possible. Its continuation repeats that crop-work gate, so new actionable work preempts the stroll.

Harvest/forage revalidates crop maturity or plant state on every break tick; immature crops never enter the harvest action. `BreakingBlockAi` provides normal dropped item entities, progress, vanilla swings, optional Epic Fight digging, and hand restoration; the higher-priority pickup goal collects those planting resources after their normal pickup delay. Planting consumes its seed only after farmland/air/survival revalidation. Bone meal is crafted only for an actionable immature owned crop and is consumed only immediately before a revalidated growth mutation; the old random success gate no longer aborts the active goal. Planting and bone meal use a separate in-range delay and show the correct held item without swinging. Only the successful plant/growth mutation emits exactly one renderer-appropriate main-hand action, using either the normal NPC swing or the dedicated non-repeating Epic Fight use animation.

If navigation toward a selected farm action stops making progress, the goal checks the owned gate/path for a safe blocker and uses `ClearBlockAi`. Resolved clear targets are revalidated against entrance ownership, home/build protection, crops, block entities, and expected fence/gate blocks.

When a ready farm has empty farmland but the crop goal cannot select an action, idle trace now distinguishes missing planting supplies from an existing seed with no exact reachable crop stand. Daily job/supply rolls use the bounded idle-trace channel rather than leaving a persistent current-task detail that masks these later diagnostics.
