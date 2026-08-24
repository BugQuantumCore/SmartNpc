# FarmStrollGoal

## Purpose

Provides optional low-priority daytime movement after a ready owned farm is completely planted.

The goal is registered at priority 7 and requires clear daytime, no combat/healing/escape state, strict farm readiness, a crop in every `PlayerNpcFarmPlan.cropPositions()` cell, and no currently actionable owned harvest, planting, or bone-meal work. The same crop-work predicate is checked during continuation, so tending work preempts a stroll as soon as it becomes actionable.

Targets are restricted to explicit saved entrance-path stands and a two-block exterior ring around the owned farm. Crop ground, fences, the gate block, home/build footprints, fluid or obstructed stands, and non-exact navigation endpoints are rejected. Selection and repathing are bounded, movement is walking speed, and completion/failure opens a randomized retry delay.
