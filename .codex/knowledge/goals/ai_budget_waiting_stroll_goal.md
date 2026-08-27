# AI Budget Waiting Stroll Goal

`AiBudgetWaitingStrollGoal` is a priority-nine visual fallback for a Player NPC that was recently denied a routine AI worker turn. It must not become another expensive job.

The class name is historical: the “budget” is the CPU resource-sharing scheduler, not RAM or a memory percentage. A denied NPC owns no scheduler resource. An active worker waiting briefly for the separately shared search/path slice still owns its worker resource and must remain inside its real goal rather than starting this stroll.

- It is registered outside `StartupWorkGatedGoal`; otherwise a non-worker could never enter it.
- It never creates a navigation path. It chooses a loaded same-Y stand within two blocks, validates every intermediate step for replaceable dry feet/head and a solid floor, and uses `MoveControl` for the short step.
- It yields immediately to combat, healing, water/upward escape, or any higher-priority real work goal.
- It continues only while the scheduler reports a recent denial, for at most three seconds, then applies a retry cooldown.
- Its trace detail explains that the NPC is strolling while waiting for an AI worker turn. It does not claim that prerequisite-blocked NPCs have budget starvation unless the scheduler actually recorded a denial.
