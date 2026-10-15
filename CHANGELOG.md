# CHANGELOG - Bot Recipe

## v2.9 (actual)
- Curación del Cardinal: las 9 curas participan bajo el mismo umbral HP del `.play`: `1011 Heal, 1015 Battle Heal, 1027 Group Heal, 1217 Greater Heal, 1218 Greater Battle Heal, 1219 Greater Group Heal, 1401 Major Heal, 1402 Major Group Heal, 1553 Chain Heal`.
- Orden por escalones de gravedad + rotación `HEAL_TURNS` para que `1553/1401` no monopolicen.
- Cada id conserva su `targetType` nativo (`ONE`, `PARTY`, `AREA_FRIENDLY`).
- `castOnSupportTarget` devuelve el resultado real de cada intento (lee `isCastingNow()` + flag simultáneo solo si se enciende en la llamada).
- Fuera: `1258 Restore Life, 1335 Balance Life, 1216/6847 Self Heal`.
- Clases: `AutoUseTaskManager$AutoUse.class` 21657 bytes, `AutoUseTaskManager.class` 3592 bytes, major 71 (JDK 27).

## v2.8
- `SkillRules` / `BotSkillRules` / `SkillRuleEvaluator` / `BotSkillRuleContext` inertes (modelo F1 sin referencias).

## v2.7
- `.bot` consolidado: fila `[Login][Config]`, `swapMode` sin party (`AdminSwapTest.runSwap`) y con party (`AdminHandoffTest.runHandoff`).
- Transferencia `OrchestratorState` (`BOT_OWNERS`, `ASSIST`, `PARTY_AUTOPLAY`, `BotFormationMode`).
- `AdminHandoffTest` + `AdminSwapTest` promovidos a payload de producción.

## v2.6
- `1553 Chain Heal` primero, gate `canUseMagic` pre-cast, `HEAL` excluido de canciones/danzas.
- Resurrección clientless no corta heals/buffs.

## v2.4
- `BOT_FIGHTBACK` / `BOT_TSEARCH`, filas Fight Back / Target Search en `.play` y `Main.htm`.
- `BotFormationMode` con target primero, `AutoPotionTaskManager` muerte/revive con `continue`, `AutoPlayProfileBuilder`.

Detalle completo en `docs/MANUAL-TECNICO.md`.
