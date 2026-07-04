# Player NPC Chat

## Source

- `src/main/java/com/pla/player_npc/util/ChatUtil.java`
- `src/main/java/com/pla/player_npc/entity/PlayerNpcEntity.java`
- `src/main/resources/assets/player_npc/lang/en_us.json`
- `src/main/resources/assets/player_npc/lang/zh_cn.json`

## Ownership

`PlayerNpcEntity` owns death and kill chat through lifecycle callbacks:

- `awardKillScore` schedules `ChatUtil.scheduleKillerTaunt` only when the killer is a Player NPC and the victim is a player or Player NPC.
- `die` broadcasts the death summary, warning, delayed death reaction, and death alert only when the victim is a Player NPC and the killer is a player or Player NPC.

Do not re-add `PlayerNpcDeadEvent` for this behavior, or Player NPC versus Player NPC deaths can double-fire.
Do not put chat strings or `tellraw` command strings in death handling code.

## Localization

All Player NPC chat text must use language keys under `chat.player_npc.*`.

Current locales:

- `en_us.json`
- `zh_cn.json`

Keep both locale files updated when adding new Player NPC chat lines.
