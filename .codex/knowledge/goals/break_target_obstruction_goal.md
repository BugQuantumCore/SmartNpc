# BreakTargetObstructionGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/BreakTargetObstructionGoal.java`
- Registered directly at priority 5 from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets a combatant break a local block obstructing its current living target. This is combat recovery, not routine job work, and must remain available without `StartupWorkGatedGoal` ownership.

Epic Fight's Player NPC attack/chase goals run at priority 6 and yield LOOK/MOVE to
this priority-5 recovery. Attack preemption clears its combo and owned animation;
digging remains attack-locked until the recovery releases its controls/motion.
Mining uses `BreakingBlockAi` and `ToolAi` for hardness timing, tool selection, cracks,
DIG_MAINHAND, drops/durability and guarded hand restoration. The shared motion stops on
movement, invalidation, break and goal stop. Missing tools no longer veto an otherwise valid
obstruction: `CombatToolCraftAi` first gets a bounded carried-material/local-table opportunity
before mining starts. If that cannot supply the tool, normal slow empty-hand breaking applies
without tool-only drops. The goal retains MOVE/LOOK during crafting, so attacks remain paused.
This goal ticks every server tick with a single-block validation/mining fast path, so ordinary
200-tick hand-breaking of cobblestone takes about ten seconds at 20 TPS instead of twice that
under reduced-rate goal updates. Discovery/path/approach and crafting discovery retain their
separate >=20-tick gates. The finite 800-server-tick (40-second) limit includes approach/crafting.

The 23:31-23:33 Philza trace repeatedly reports target=none/idle while obstruction/melee remains
in the selector snapshot. This identifies target lifecycle loss rather than a competing utility;
the entity's non-hunter cleanup must preserve the explicit active retaliation target after
vanilla's brief last-hurt pointer expires. The obstruction goal still stops on a genuinely dead,
invalid, allied, replaced or cleared target; it never resurrects targets itself.

Admitted ray reselection prefers the currently reachable selected block whenever any body ray
still validates it, rather than resetting progress when a different hit becomes slightly nearer.
A miss of the remembered sample pauses mining until the next admitted ray pass; changed block
identity or protections still abort immediately. `BreakingBlockAi.pause()` clears visual motion
while retaining progress, so brief aim movement or lost reach does not restart a long break.
Successful breaking uses the existing discovery deadline instead of adding another 20-tick wait.

The entity's `isClearingCombatObstruction()` queries the running goal, including its crafting
sub-action, independently of the inspector string. Offensive utility activation must yield to
this ownership rather than preempting it just because its selector priority is higher. Healing,
fleeing and physical escape still retain their safety priorities; the goal is interruptible.
`PlayerNpcSmartTargetGoal` keeps the current valid target through lost line of sight while this
action is running, using the same combat conditions with only visibility relaxed. It must not
clear the target precisely because the selected wall still exists. Opportunistic closer-threat
retargeting also waits; actual damage retaliation and invalid/dead/allied-target checks remain.
The running goal republishes its action state after damage callbacks change the inspector text.
Newly hidden valid enemies also receive eight seconds of sight-loss memory before clearing
starts, so the target selector cannot erase them before the throttled obstruction goal gets
its first activation. Once clearing owns the action, its finite lifecycle retains the enemy
beyond that visibility grace. New-target acquisition still requires the normal sight checks.
The separate priority-2 `ShieldGuardGoal` also waits for clearing to finish; repeated discretionary
shield poses must not reset a long block break. This does not alter AdvancedMobPatch weapon guard
damage handling or emergency survival goals.

The 21:56 SkySom trace broke the glass pane at (-189,-59,-86), then returned to melee with
an incomplete path ending outside at (-189,-60,-87). Eye visibility had opened while the
lower sill still blocked the NPC body. Eligibility and continuation now inspect physical entry,
so an already-open window is recoverable after goal restart without retaining stale breach blocks.

## Performance Contract

Activation is throttled to a randomized 20-30 tick cadence. A loaded-corridor preflight and
shared path admission precede the scoped `0.15F` target-path probe; a reachable route always
vetoes mining. An incomplete/null admitted route alone is insufficient: selection uses the
first physical collision of one eye ray and at most nine lower/middle/upper body rays inside
the NPC's width. Body rays apply only within 0.25 feet-height difference, remain horizontal
above both feet, and do not select descending terrain/floors. There is no nearby-volume or
arbitrary adjacent-wall fallback. Eye visibility can coexist with a proved body obstruction.
Among those exact collision hits, a block currently reachable with a break ray takes priority
over one requiring approach, so inaccessible lower sills cannot hide reachable glass in front.

Running path/candidate revalidation and post-break reselection run at >=20 server ticks;
approach selection is also >=20 ticks. One admitted slice permits at most two scoped paths
(target plus one approach), and discovery clips at most ten rays. Denied discovery admission
preserves progress on an already selected, still-valid block. The selected exact ray, current
target identity/height, loaded corridor, reach, collision and home/build/farm protections are
checked before mining. Losing the remembered ray pauses work immediately; the next bounded pass
either validates another sample of the same block or replaces/abandons the selection.
The NPC's supporting block, fluid, block entities and protected job infrastructure never qualify.

Combat recovery uses the target's actual feet height: a target more than two blocks above yields
to `EscapeHoleWithBlockGoal`, including during an already running break. Same-height or lower
targets stay on this obstruction-clearing route when the ray and reachability checks justify it;
being surrounded by walls does not itself authorize pillaring. The escape goal performs its own
loaded, bounded route admission, so a retained upward request cannot bypass its safety checks.

Approach stand discovery creates at most one scoped path per 20-tick repath and shares the
same slice allowance as target validation. A failed stand advances a retained cursor through
nine fixed candidates on later intervals. A successful path is passed directly to navigation
and reused while live; a current usable stand uses `MoveControl` centering without pathfinding.
