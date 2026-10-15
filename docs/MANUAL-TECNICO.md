# BOTAI-RECIPE

Bot Manager server-side para **L2J Mobius CT 2.6 High Five**.

Convierte los demás personajes de **tu propia cuenta** en compañeros de party
reales, persistentes y server-side, que asisten, siguen y combaten a tu lado.

> **Fuente de verdad.** Este documento describe el runtime actual. La
> implementación viva es `AdminBotManager.java`. Si un texto y el código
> discrepan, **el código gana** y este README está desactualizado.

---

## 1. Identidad de los personajes

| Concepto | Qué es |
|---|---|
| **Orchestrator** | El Player normalmente conectado que abre `.bot` y controla los switches |
| **Controlled Player** | Un Player real y persistente de la **misma cuenta** y de la **misma party** |
| **Party Leader** | El destino dinámico de Assist. Cambia solo, sin tocar switches |

No hay Players virtuales. Cada bot es un `Player` real cargado desde la base de
datos, con `GameClient == null` (clientless).

El Orchestrator es un **Player normal**: nivel de acceso 0, sin GM. No hay
ninguna dependencia de rango de acceso, clan ni Command Channel.

### Alcance del control

Un Orchestrator controla únicamente Players que cumplen **las dos** condiciones:

```java
target.getAccountNamePlayer().equals(orchestrator.getAccountNamePlayer())
party.getMembers().contains(target)
```

Cada cuenta puede operar su propio `.bot` de forma simultánea e independiente,
incluso compartiendo clan o Command Channel. Nunca hay cruce entre cuentas.

---

## 2. Slots

```
slot 1   = Orchestrator (el Player conectado) — nunca es bot
slot 2..9 = Players controlables de la misma cuenta
```

Los slots `2..9` son **lógicos**, no posiciones absolutas: el slot 2 es siempre el
primer personaje no-orchestrator de la lista de la cuenta, y así hasta el 8º. La
resolución usa `getClient().getCharSelection(index)`, la lista real de la cuenta
conectada, y el Orchestrator se salta siempre.

Con 9 personajes por cuenta: 1 Orchestrator + 8 controlables.

---

## 3. Comandos

| Comando | Efecto |
|---|---|
| `.bot` | Abre la ventana del Bot Manager |
| `.bot 2` … `.bot 9` | Activa/desactiva ese slot lógico |
| `.bot all` | Activa los slots que falten |
| `.bot off` | Dismiss: apaga todos los bots (teardown limpio) |
| `.bot formation` | Toggle de **Formation**: mantiene a los bots en su anillo del líder |
| `.bot status` | Estado real de cada slot |
| `.bot assist` | Toggle de **Assist** del Orchestrator |
| `.bot partyautoplay` | Toggle de **Party AutoPlay** del Orchestrator |
| `.bot skills <charId>` | Select Skills de un Player controlado |

---

## 4. La ventana

```
Cardinal   [START/STOP]  [Skills]  [Config]

   [ ALL | Dismiss ]  [ Party AutoPlay OFF ]

    [ Assist OFF ]

          [ Formation OFF ]

[ bot 2 ]  Tank  - Lv. 40 S…  [ Skills ]  [Config]
[ bot 3 ]  ...
```

Cada fila de slot es `[slot] [Skills] [Config]` = 160+55+55 = **270px**, el ancho
histórico del panel: nada se desborda y los tres botones son clicables.

- **`START/STOP`** → AutoPlay individual del Orchestrator. No afecta a los bots.
- **`[Skills]`** → Select Skills nativo del personaje de esa fila.
- **`[Config]`** → el `.play` de ese personaje espejado en el panel (§29). En la
  cabecera abre el tuyo, así toda la configuración vive en `.bot`.
- **`ALL/Dismiss`** → un único botón dinámico según `BOT_REGISTRY` vacío o no.
- **`Party AutoPlay`**, **`Assist`** y **`Formation`** → switches
  del Orchestrator, independientes entre sí.

## 5. Party AutoPlay

**Estado temporal global del Orchestrator.** No es estado persistente del bot.

| Evento | Resultado |
|---|---|
| Nueva sesión | `OFF` |
| `OFF` + `ALL` | bots entran `OFF` |
| `OFF` + Dismiss + `ALL` | bots vuelven `OFF` |
| `ON` | estado global `ON` |
| `ON` + `ALL` | bots entran `ON` |
| `ON` + Dismiss + `ALL` | bots vuelven `ON` |

El estado vive en `PARTY_AUTOPLAY_ORCHESTRATORS` (runtime, por `objectId`) y se
limpia en `dropOrchestrator()`. **Nunca se persiste** y **no se restaura desde
`character_variables`**.

**No afecta** al Orchestrator, que conserva su `START/STOP` individual.

Usa exclusivamente `AutoPlayTaskManager.startAutoPlay()` y `stopAutoPlay()`. **No
toca** AutoUse, AutoPotion, AutoBuffs, AutoItems, Skills, Assist ni
Formation. Al apagar AutoPlay, AutoUse y AutoPotion quedan registrados y
configurados igual.

`prepareBot()` y `adoptBot()` consultan este estado global, de modo que un bot
nuevo entra siempre con el estado vigente del grupo.

---

## 6. Assist

```
Orchestrator  →  Assist ON/OFF  →  Party Leader actual  →  target de los bots
```

El Orchestrator controla el switch. **El Party Leader determina el objetivo.**

```text
Orchestrator = Cardinal
Party Leader = Tank

Assist ON → los bots asisten al Tank
```

Si el Party Leader cambia (`Tank → Archer`), Assist pasa a Archer en el siguiente
ciclo, **sin reactivar el switch**: `party.getLeader()` se relee cada ciclo y el
guard compara destinos.

`Assist OFF` → `AdminBotManager` deja de imponer target y el comportamiento
normal del Player queda disponible.

### Solo Monsters

`applyAssist()` acepta el target del Party Leader **únicamente** si es un Monster
válido para ese bot:

```java
isMonster() && !isRaid() && isAutoAttackable(bot)
```

| Target del Party Leader | Resultado |
|---|---|
| Monster válido | los bots lo toman y lo atacan |
| NPC / merchant / guard / teleporter | **ignorado**, el bot queda sin target |
| Player | **ignorado** |
| Raid | **ignorado** |
| Sin target | el bot queda sin target |

Cuando el Party Leader vuelve a seleccionar un Monster, el siguiente ciclo lo toma.
Assist nunca puede llevar a un bot a atacar algo que no sea contenido PvE.

### Prioridad sobre el AutoPlay

Mientras Assist está ON, el target del Party Leader es la **única** fuente. El motor
nativo sigue buscando objetivos, pero forzados a Monster (§8), de modo que el bot nunca
se desvía a un NPC ni caza por su cuenta.

---

## 7. Follow (eliminado en v2.1)

El botón **Follow** y toda su función (`FOLLOW_ARGUMENT`, `BOT_FOLLOW`,
`FOLLOW_ORCHESTRATORS`, `applyFollow()`, `releaseOrchestratorFollow()`) fueron
eliminados. El grupo se mueve con **Formation** y combate con **Assist**: Assist
espeja el target del Party Leader sin imponer nunca un follow implícito
(`setAssistWithoutFollow` permanente por bot).

> Las filas `BOT_FOLLOW` que pudieran existir en `character_variables` de una
> versión anterior quedan **inertes**: ya no se leen ni se escriben.

---

## 8. Monster-only targeting

Mientras Assist está ON, el motor nativo solo puede adquirir **Monsters**:

```java
OWN_TARGET_MODES.put(charId, bot.getAutoPlaySettings().getNextTargetMode());
bot.getAutoPlaySettings().setNextTargetMode(1);
```

El modo nativo `1` (Monsters) exige
`isMonster() && !isRaid() && isAutoAttackable()`. Su hermano `4`
(**Monster+Raidboss**) es la misma regla **sin** `!isRaid()`, así que acepta
también los raid y grand bosses. Los modos 2 y 3 aceptan respectivamente
Players y NPCs, y el caso por defecto (Any Target) también acepta NPCs fuera de zona de paz. Por eso
`1` es la única modalidad que nunca devuelve un merchant, un guard o un teleporter
**ni tampoco un raid**. Los raids además se saltan dos filtros del escaneo que a
veces los hacían invisibles: Respectful Hunting no los descarta (unirse a un raid
en pelea es lo esperado) y solo necesitan línea de vista, no path de navmesh.

Los bots **sí hacen hunt** con este modo: lo que cambia es el tipo de objetivo, no la
capacidad de buscarlo.

### Al desactivar el bot

```java
final Integer original = OWN_TARGET_MODES.remove(bot.getObjectId());
bot.getAutoPlaySettings().setNextTargetMode(original);
```

El modo original del Player se restaura **exactamente**, sea cual sea (`0`, `2`, `3`, …).
El valor forzado nunca se persiste: vive solo en memoria y `teardownBot()` lo devuelve
antes de `storeMe()`.

### Validación compartida

`isPveMonster(actor, candidate)` aplica la misma regla en `applyAssist()`, de modo que
Assist nunca impone un objetivo que la adquisición nativa descartaría.

---

## 9. Skills

```text
.bot → [Skills] → Select Skills → Back → .bot
```

El botón abre el **Select Skills nativo** (`data/html/mods/AutoPlay/Skills.htm`)
de un Player controlado y lo envía al Orchestrator. Funciona también para el propio
Orchestrator sin party.

**`Skills.htm` no se modifica.** El `Back` se reescribe en runtime para volver al
panel.

El render replica el filtro nativo completo (`playskills`): skills propias, summon,
pet, exclusión de passive/toggle, `DISABLED_AUTO_SKILLS`, eliminación de item
skills, enchant options y augmentation.

### Persistencia

`AUTO_USE_SKILLS` y `AUTO_USE_BUFFS` se escriben **inmediatamente** en
`PlayerVariables` tras cada cambio, no solo al logout. Motivo: un bot clientless no
tiene logout, así que el listener nativo nunca correría y la elección se perdería al
destruir el bot.

Consecuencia: la configuración sobrevive a un reinicio del servidor y aparece igual
cuando ese personaje entra normalmente y usa `.play`.

El aislamiento usa `getAccountNamePlayer()` + `party.getMembers()`. El `charId` del
bypass además pasa la validación nativa `validateHtmlAction`, que descarta
cualquier acción que no estuviera en el último HTML enviado al viewer.

---

## 10. Formation

**Toggle del Orchestrator**, no una acción puntual.

```java
formationBots()                       // el anillo se ordena una vez al activar
BotFormationMode.toggle()             // ON/OFF por orchestrator
BotFormationMode.maintain()           // se llama en cada ciclo mientras está ON
BotFormationMode.forget()             // se limpia en el teardown del bot
FORMATION_RADIUS = 100  RECALL_DISTANCE = 40
TARGET_SYNC_PERIOD = 700L
```

Mientras está ON, cada ciclo recalcula el anillo alrededor del líder y solo
reemite el `MOVE_TO` a los bots que se alejaron más de 40 unidades de su
ranura, para que no reinicien la caminata que ya están haciendo.

`BotFormationMode` es **solo movimiento**: nunca lee ni escribe un target, así
que no interfiere con Assist ni con el bucle nativo de AutoPlay.

---

## 11. Revive / Dismiss / ALL

`doRevive()` y `reviveAnswer()` cambian **solo memoria**: el listener nativo
`ON_PLAYER_LOGOUT` es el que persiste, y un bot clientless nunca hace logout.

`teardownBot()` persiste antes de descartar:

```java
bot.setOnlineStatus(false, false);
bot.storeMe();
bot.decayMe();
```

Sin ese `storeMe()` la tabla `characters` conservaba la fila muerta y el Player
volvía a cargar como muerto al ser adoptado de nuevo.

`ALL` y `Dismiss` **no** modifican el estado de Party AutoPlay.

---

## 12. Offline Play y `AutoPlayProfileBuilder`

`OfflinePlayDisconnectSameAccount = True` permite que los Players de una cuenta
permanezcan persistentes/offline mientras el Orchestrator sigue conectado.

El teardown **no** usa `Disconnection.storeAndDelete()`, porque invoca
`deleteMe()` y borraría la fila del personaje, inventario y almacén. Los bots son
Personajes reales y deben sobrevivir a su propio teardown.

`AutoPotionTaskManager` es la **única** clase del core recompilada por esta receta:
el gate original `isOnlineInt() == 0` expulsaba a los bots clientless en el
siguiente tick. Se sustituye por `!player.isOnline()`.

`AutoPlayProfileBuilder` se ejecuta al activar un bot, después de restaurar la
configuración `.play` persistida. Hace **solo dos cosas**:

1. **AutoLoot = OFF**, siempre, con `AutoPlaySettingsHolder.setPickup(false)`.
2. **Ataque básico automático** para clases no mágicas, añadiendo
   `AUTO_USE_ACTIONS = 2` solo si el personaje no tiene ya acciones configuradas.

**No genera** listas de `AUTO_USE_SKILLS` ni `AUTO_USE_BUFFS` y no destruye
configuración existente. Las skills se configuran desde el `[Skills]` de la ventana.

---

## 13. AutoShots

`configureBotAutoShots()` registra los shots nativos de un bot clientless a partir de
lo que hay **en su inventory**. Un bot depende exclusivamente de esta llamada: sin
client no hay otra vía que registre los shots por él.

Se ejecuta en `prepareBot()` y en `adoptBot()`.

### Beginner shots

Los shots para principiantes **no tienen grado de cristal**, así que no aparecen en
ninguna tabla de grados y se registran por su cuenta, **antes** de la lógica normal:

| Item | ID |
|---|---|
| Soulshot No Grade for Beginners | `5789` |
| Spiritshot No Grade for Beginners | `5790` |

Sin ese paso previo, un personaje bajo cuyo único shot es para principiantes quedaba
sin ninguno registrado, porque el recorrido de grados nunca alcanzaba un ID válido.

### Lógica por grado

Se mantiene intacta, en orden `S → A → B → C → D → NONE`:

- **Soulshot** del grado, si está en inventory.
- **Blessed Spiritshot** del grado, si está; si no, el **Spiritshot** normal del grado.

Los shots para principiantes **no sustituyen** a los de grado superior: se registran
además, y la lógica de grados sigue añadiendo lo que el personaje realmente posea.

Solo se mira el **inventory**, nunca el warehouse.



| Requisito | Valor | Cómo se comprueba |
|---|---|---|
| **Runtime** | L2J Mobius **CT 2.6 High Five** compilado, con `<runtime>/game` y `<runtime>/libs/GameServer.jar` | El instalador lo valida |
| **JDK** | **JDK 27** (exactamente). `java` y `javac` del mismo JDK de 27 | Se descubre por `JAVA_HOME` o `PATH`; se valida la versión |
| **Bytecode** | clase **major 71** (JDK 27), minor 0 | Se lee de la cabecera de cada clase compilada |
| **PowerShell** | 5.1 o 7.x (Windows) | Se reporta al inicio; ambos funcionan |
| **Servidor** | **Detenido** durante la instalación | Se comprueba por proceso |
| **Espacio** | ~10 MB libres (copia staged del JAR) | Se comprueba |
| **Source de Mobius** | **NO se necesita.** El source del core modificado viaja en el paquete | — |
| **Base de datos** | **No se toca.** No hay SQL, ni filas nuevas, ni personajes creados por el script | — |

El runtime debe ser la build **High Five / CT 2.6**. El instalador comprueba que
la clase `AutoPotionTaskManager.class` exista en el JAR y valida su tamaño, lo
que detecta un runtime incompatible.

---

## 14. Arquitectura

```
AdminBotManager            = orchestration layer  (capa de orquestacion del .bot)
Mobius AutoPlay / CreatureAI = execution layer     (protegido)
```

### Estados en runtime

| Campo | Responsabilidad |
|---|---|
| `BOT_REGISTRY` | Bots tracked. `World` sigue siendo la fuente de verdad |
| `BOT_SESSIONS` | Timestamp de sesión por bot |
| `BOT_OWNERS` | `orchestratorId → botIds`. Aísla las cuentas |
| `ASSIST_ORCHESTRATORS` | Assist ON por Orchestrator |
| `PARTY_AUTOPLAY_ORCHESTRATORS` | Party AutoPlay ON por Orchestrator (solo sesión) |
| `OWN_TARGET_MODES` | Modo de target nativo propio de cada Player mientras lo gobierna el manager |

### Flujo

```
showBotManagerWindow
    ↓
dispatchCommand   (BotManagerBypass quita el root y el token "ui")
    ↓
activateSlot → adoptBot | prepareBot
    ↓
BOT_OWNERS
    ↓
TARGET_SYNC_TASK   (cada 700 ms, una pasada por Orchestrator)
    ↓
processOrchestrator
    ↓
  syncBotsWithLeader           → libera el target
  applyOrchestratorBehaviours
      → mute/restore target mode
      → applyAssist  (target del Party Leader)
```

`processOrchestrator()` solo descarta el registro cuando el Orchestrator
**desconecta**. Estar muerto, no tener party o no tener bots son estados
transitorios: se omiten ese ciclo sin perder la identidad.

### Persistencia de los switches

| Switch | Dónde vive | Sobrevive logout |
|---|---|---|
| Assist | `PlayerVariables.BOT_ASSIST` + `ASSIST_ORCHESTRATORS` | Sí |
| Party AutoPlay | `PARTY_AUTOPLAY_ORCHESTRATORS` | **No**, por diseño |

Assist se reconstruye desde su variable al abrir el panel. Party
AutoPlay es deliberadamente efímero: una sesión nueva empieza en `OFF`.

---

## 15. Estructura del paquete

```
BOTAI-RECIPE/
├── README.md          este documento (canónico)
├── MANIFEST.json      contrato de ejecución: qué, dónde, con qué hash
├── install.ps1        instalador
├── verify.ps1         verificador (read-only)
├── rollback.ps1       reversor (dry-run por defecto)
├── .gitattributes     EOL determinista para el payload congelado
└── payload/           15 archivos congelados (byte a byte)
    ├── AdminBotManager.java          orquestación + ventana + Login/Config
    ├── AdminHandoffTest.java         HANDOFF con party (producción, vía Login)
    ├── AdminSwapTest.java            SWAP sin party (producción, vía Login)
    ├── AutoPlayProfileBuilder.java   AutoLoot OFF + ataque básico físico
    ├── AutoPlay.java                 .play nativo con la fila Spoil
    ├── BotFormationMode.java         Formation continuo (solo movimiento)
    ├── BotCommand.java               comando `.bot`
    ├── BotManagerBypass.java         botones de la ventana
    ├── Main.htm                      HTML nativo con `%spoil_row%`
    ├── MasterHandler.java            registro de handlers
    ├── Resurrection.java             resurrección clientless
    ├── AutoPotionTaskManager.java    source del core modificado
    ├── AutoPlayTaskManager.java      source del core modificado
    ├── AutoUseTaskManager.java       source del core modificado
    └── 10000-10099.xml               skills custom (REPLACE)
```

---

## 16. Instalación

### Antes de empezar

1. **Detén el servidor** si está corriendo. El instalador no lo detiene nunca.
2. Ten a mano la ruta de tu runtime, la carpeta que contiene `game` y `libs`.

### Ejecutar

```powershell
cd C:\ruta\a\BOTAI-RECIPE
.\install.ps1 -Runtime "C:\ruta\al\runtime"
```

Un solo comando. El instalador valida, respalda, instala, compila, parchea el
JAR, aplica la configuración y se autoverifica. Termina con:

```
RESULT: PASS
```

Los backups quedan en `<runtime>\_botai-runs\<run-id>\` **dentro del runtime**.
No se borran automáticamente. Ese es tu punto de reversión.

### Qué hace, por etapas

| Etapa | Acción |
|---|---|
| 0 | Diagnóstico: PowerShell, JDK 25 (java/javac del mismo JDK), layout, espacio, permisos, servidor detenido |
| 1 | Clasifica el estado del runtime (§6) |
| 2 | Respalda los 10 destinos existentes y registra los valores de configuración originales |
| 3 | Copia los 10 archivos datapack y verifica su SHA-256 |
| 4 | Compila los sources del core (AutoPotion, AutoUse, AutoPlay) contra el JAR; valida tamaño, major y bytecode |
| 5 | Staging del JAR: reemplaza las entries objetivo y exige 0 añadidas / 0 eliminadas |
| 6 | Instala el JAR staged |
| 7 | Aplica las 4 claves de configuración (idempotente) |
| 8 | Ejecuta `verify.ps1` |

### Si algo falla

El instalador se detiene **antes de escribir nada** y muestra:

```
PROBLEMA : <qué pasó>
CAUSA    : <por qué>
ACCION   : <qué hacer>
```

Cada gate de las etapas 0 y 1 se evalúa antes de la primera escritura. Las
etapas 2-7, si fallan, dejan el backup intacto en el run folder y el runtime
recuperable con `rollback.ps1`.

---

## 17. Detección de estado

`install.ps1` nunca sobrescribe a ciegas. Reconoce cuatro estados:

| Estado | Qué detecta | Qué hace |
|---|---|---|
| **CLEAN** | Nada de lo mío instalado | Instala |
| **ALREADY-INSTALLED** | 3 datapack con hash correcto + JAR parcheado + bytecode válido + 4 configs | **No escribe nada.** Informa y sugiere `verify.ps1` o `rollback.ps1`. (Con `-Reinstall` fuerza la reinstalación) |
| **PARTIAL** | Instalación incompleta: algunos datapack COPY, JAR parcheado sin datapack, configs a medias | **Se detiene.** Nombra exactamente qué falta y no sobrescribe. Sugiere `rollback.ps1` |
| **PENDING-ROLLBACK** | Ya existe un run folder con backup | **Se detiene.** No crea un backup nuevo. Ejecuta `rollback.ps1` primero |

> **v2.5 antibloqueo.** Los `REPLACE` (ej. `10000-10099.xml`, que existe en
> stock) nunca cuentan como residuo: solo los 5 `COPY` deciden PARTIAL. Un
> stock con hash distinto se respalda (10 backups) y se sobrescribe en
> ETAPA 2/3 sin bloquear. `rollback.ps1` acepta runs v2.4 (9) y v2.5 (10).

---

## 18. Verificación

```powershell
.\verify.ps1 -Runtime "C:\ruta\al\runtime"
```

Read-only. No arranca el servidor, no escribe en el runtime, no depende de logs
de una instalación anterior. Devuelve `RESULT: PASS` o `RESULT: FAIL`.

| Check | Qué valida |
|---|---|
| C1 | Los **12** datapack de payload del runtime coinciden con `MANIFEST.json` (hash canónico) |
| C2 | **Cada** source de core COMPILE (`AutoPotionTaskManager`, `AutoUseTaskManager`, `AutoPlayTaskManager`) contiene su marcador requerido y no su marcador prohibido |
| C3 | El JAR solo tiene entries de core modificadas (máx. 5), **0** añadidas, **0** eliminadas. **SKIP** si no hay pre-estado |
| C4 | Cada entry del core: tamaño, `major 71`, **SHA-256** y marcadores de bytecode |
| C5 | Las clases `$SingletonHolder` protegidas siguen intactas. **SKIP** si no hay pre-estado |
| C6 | Las **14** claves de configuración |
| C7 | El payload no contiene tokens administrativos |
| C8 | Los backups están íntegros |
| C9 | El rollback está preparado |

---

## 19. Rollback

```powershell
.\rollback.ps1 -Runtime "C:\ruta\al\runtime"              # dry-run: solo muestra el plan
.\rollback.ps1 -Runtime "C:\ruta\al\runtime" -Force       # aplica
```

Seguro por diseño:

1. Exige que el servidor esté detenido.
2. **Valida primero que todos los backups existen y sus hashes coinciden. Si falta
   uno, no toca nada.**
3. Muestra el plan completo.
4. Es dry-run por defecto; solo `-Force` aplica.
5. Restaura los destinos byte a byte y verifica la restauración.

Los backups **nunca se sobrescriben ni se borran** al revertir.

---

## 20. Después de instalar

### Arrancar el servidor

```powershell
cd <runtime>\login
cscript //nologo LoginServer.vbs      # esperar: "is now listening on: 0.0.0.0:2106"

cd <runtime>\game
cscript //nologo GameServer.vbs       # esperar: "Handlers Loaded..." y "Server loaded in X seconds"
```

El script loader de Mobius compila los datapack en el arranque. Revisa
`<runtime>\game\log\java0.log`: no debe haber excepciones ni fallos de compilación
de scripts.

> Si arranques en modo headless, pon `EnableGUI = False` en
> `game\config\Interface.ini` y `login\config\Interface.ini`.

### Preparar la prueba

1. Crea hasta **9 personajes** en una cuenta de prueba. Con 9 personajes tienes
   1 Orchestrator + 8 controlables. La receta **no crea personajes** por ti.
2. Entra con uno de ellos, con un Player normal (acceso 0, sin GM, sin party).
3. Escribe `.bot` en el chat del cliente.

### Checklist de humo

| # | Prueba | PASS |
|---|---|---|
| 1 | `.bot` | La ventana se abre; la primera fila es el Orchestrator |
| 2 | `.bot 2` | El Player se activa, entra en tu party y sigue al líder |
| 3 | `.bot status` | Refleja el estado real de cada slot |
| 4 | `.bot all` | Activa los slots restantes |
| 5 | `.bot formation` | Los bots caminan a su posición del anillo |
| 6 | `Party AutoPlay ON` | Todos los bots entran en el pool de AutoPlay |
| 7 | `Party AutoPlay OFF` | Todos salen; AutoUse y AutoPotion siguen activos |
| 8 | Dismiss + `.bot all` con ON | Los bots vuelven `ON` |
| 9 | `Assist ON` | Los bots toman el target del Party Leader y no huntan solos |
| 10 | Cambiar el Party Leader | Assist cambia de destino sin pulsar nada |
| 11 | `[Skills]` en cualquier fila | Abre Select Skills y `Back` vuelve al panel |
| 12 | Reiniciar el servidor | La configuración de Skills persiste |
| 13 | Muerto → revivir → Dismiss → adoptar | Aparece **vivo** |
| 14 | Dos cuentas con `.bot` a la vez | Ninguna controla los Players de la otra |
| 15 | `Config` en tu fila | Abre tu `.play` espejo: Settings + Target Mode + Spoil + HP Percent + un solo Back |
| 16 | `Config` en un bot + cambiar un valor | Refleja su estado, persiste al reabre y al reinicio |
| 17 | **Spoil OFF** + matar | El corpse se suelta al instante y los bots vuelven al anillo (Formation ON) |
| 18 | `.bot off` | Limpia todo; la party queda intacta para el Orchestrator |
| 19 | Logs | Sin errores del BotAI en `game\log\java0.log` |
| 20 | Target Mode **Monster+Raidboss** | El bot caza mobs **y** raid bosses; NPCs y players siguen excluidos |

---

## 21. Configuración requerida

**Catorce claves, en cuatro archivos.** Son **requisitos funcionales**, no
preferencias. Las aplica `install.ps1` de forma idempotente:

| Archivo | Clave | Valor | Por qué |
|---|---|---|---|
| `AutoPlay.ini` | `EnableAutoPlay` | `True` | Sin esto el motor AutoPlay está apagado |
| `AutoPlay.ini` | `AssistLeader` | `True` | Habilita el motor nativo de assist que el BotAI gobierna |
| `AutoPotions.ini` | `AutoPotionsEnabled` | `true` | Sin esto no hay AutoPotion observable |
| `Server.ini` | `CharMaxNumber` | `9` | 1 Orchestrator + 8 controlables por cuenta |

`AssistLeader = True` es **deliberado**: el BotAI toma el target del Party Leader
a través del motor nativo de assist. El modo forzado a Monsters y el espejo de
target los impone `AdminBotManager` (ver §9 y §26).

### Autolearn — `game/config/Player.ini`

El instalador **sí aplica** estas cinco claves, de forma **idempotente**. Sin
ellas un slot recién activado se queda quieto por falta de skills de clase y no
puede combatir.

```ini
AutoLearnSkills = True
AutoLearnSkillsWithoutItems = True
AutoLearnForgottenScrollSkills = True
AutoLearnDivineInspiration = True
DivineInspirationSpBookNeeded = False
```

| Clave | Valor | Por qué |
|---|---|---|
| `AutoLearnSkills` | `True` | Entrega las skills al subir de nivel; sin esto el bot nunca gana su rotación |
| `AutoLearnSkillsWithoutItems` | `True` | Las entrega sin cobrar el ítem |
| `AutoLearnForgottenScrollSkills` | `True` | Restaura las de grimorios olvidados; requiere `AutoLearnSkills` |
| `AutoLearnDivineInspiration` | `True` | Entrega Divine Inspiration, que queda **excluida** de `AutoLearnSkills` |
| `DivineInspirationSpBookNeeded` | `False` | Permite aprenderla sin consumir el SP book |

**Cómo se aplican.** Solo se reescribe la línea de cada clave; el resto de
`Player.ini` se conserva byte a byte. Si una clave **no existe** o aparece
**más de una vez**, la instalación se detiene sin escribir ese archivo
(`Stop1: clave de config no unica`) en vez de duplicar o adivinar. Si ya tiene
el valor esperado, se informa y se sigue. `Player.ini` entra en el conjunto de
backups, de modo que `rollback.ps1` lo restaura íntegro.

### Perfiles de skills: fuera de alcance

La receta **no** congela listas de skills por clase ni ejecuta ningún analizador.

| Caso | Comportamiento |
|---|---|
| Clase física | ataque básico automático |
| Clase mágica | sin ataque básico automático |
| Skills | **no** autogeneradas |
| Buffs | **no** autogenerados |

Las skills se configuran a mano desde el `[Skills]` de la ventana (§9) y se
persisten.

---

## 22. El cambio en el core

Se recompilan **tres sources** del core, que producen **cinco entries** del JAR:
`AutoPotionTaskManager`, `AutoUseTaskManager` y `AutoPlayTaskManager` (con sus
clases internas `AutoUse` y `AutoPlay`).

El original de Mobius gatea así:

```java
(player.isOnlineInt() == 0)
```

`isOnlineInt()` devuelve 0 siempre que `_client == null`. Los bots son
clientless, así que la expresión es verdadera para cada bot y el task los
**expulsa en el siguiente tick**.

Este paquete usa:

```java
(!player.isOnline())
```

Combinado con `setOnlineStatus(true, false)` al activar un bot, el bot clientless
vivo permanece en el task mientras que un personaje realmente desconectado sigue
siendo eliminado.

Los otros dos sources añaden lo que el BotAI necesita del motor nativo:
`AutoPlayTaskManager` incorpora `setAssistWithoutFollow` / `clearAssistWithoutFollow`
/ `isAssistWithoutFollow` (contrato que consume `AdminBotManager`) y la pasada
prioritaria de *fight back*; `AutoUseTaskManager` incorpora la curación por
política del Cardinal con `canUseMagic` como puerta previa al casteo. Las nueve
curaciones (Heal `1011`, Battle Heal `1015`, Group Heal `1027`, Greater Heal
`1217`, Greater Battle Heal `1218`, Greater Group Heal `1219`, Major Heal `1401`,
Major Group Heal `1402` y Chain Heal `1553`) participan bajo el **mismo umbral
configurable** `getAutoPotionPercent()` del `.play`; `getKnownHeal` solo decide
participación, así que cada una conserva su `targetType` nativo (`ONE`, `PARTY`,
`AREA_FRIENDLY`) y su resolución de objetivos. Restore Life `1258`, Balance Life
`1335` y Self Heal (`1216`/`6847`) quedan fuera. Las curaciones se eligen por
escalones de gravedad y cada escalón **rota** un paso por pasada, para que ninguna
(hasta ahora `1401`, con 1 s de reuso) monopolice el turno y deje a las demás sin
lanzarse. `castOnSupportTarget` informa el resultado real de cada intento (no asume
éxito) y solo cuenta un flag de casteo que se encienda durante la llamada, de modo
que ni un candidato abortado por el motor ni un casteo simultáneo heredado se
confunden con este intento.

**Nada más del core se modifica.** El JAR nunca se reconstruye: se reemplazan
exactamente las cinco entries objetivo, y el gate exige 0 entradas añadidas y 0
eliminadas.

Las clases `AutoPotionTaskManager$SingletonHolder`,
`AutoUseTaskManager$SingletonHolder` y `AutoPlayTaskManager$SingletonHolder`
**no se tocan**: se comprueba que siguen byte-idénticas a las del runtime.

**Este paquete no incluye ningún `.patch`.** Los sources parcheados en `payload/`
ya contienen los cambios; son el artefacto reproducible.

---

## 23. Archivos protegidos

| Archivo | Razón |
|---|---|
| `voiced/AutoPlay.java` | Fuente de verdad del AutoPlay y de Select Skills |
| `AutoPlayTaskManager` | Motor de ejecución |
| `AutoUseTaskManager` | Motor de AutoUse |
| `MasterHandler.java` | Registro de handlers |
| `bypass/npc/VoiceCommand.java` | Dispatcher de voz |
| `bypass/BotManagerBypass.java` | Botones de la ventana |
| `html/mods/AutoPlay/Skills.htm` | Se **lee**, nunca se edita |
| `GameServer.jar` | Se parchea 1 entry; nunca se reconstruye |
| `skill/effects/Resurrection.java` | Resurrección clientless |
| `config/Custom/AutoPlay.ini` | Configuración, no código |

**Toda la funcionalidad de este sistema vive exclusivamente en
`AdminBotManager.java`.**

---

## 24. Para agentes de código

El paquete es legible sin contexto externo:

1. **`MANIFEST.json`** es el contrato. Cada entrada de `payload` declara `op`
   (`COPY` / `REPLACE` / `COMPILE`), `source`, `destination` y `sha256`. Cada
   entrada de `core_entries` declara además `expected_size`, `class_major` y
   `expected_class_sha256` de la clase compilada.
2. **`payload/`** contiene los 15 archivos con esos hashes exactos.
3. **No interpretes historia.** La secuencia está en `install.ps1` y sus gates son
   automáticos: ejecuta el script y lee su `RESULT`.
4. **El script nunca arranca ni detiene el servidor.**
5. **Cero rutas absolutas** en todo el paquete.
6. **Los hashes son canónicos.** Se calculan sobre los bytes con los finales de
   línea normalizados a LF, así que CRLF y LF dan el mismo resultado; el payload
   se empaqueta en CRLF puro y `.gitattributes` marca los archivos como binarios
   para que Git no reescriba bytes. **Nunca reformatees un payload.**
7. **La documentación describe el estado actual**, no una secuencia de versiones.

### Detalle por hash

> **Contrato vigente.** Los hashes que `install.ps1` y `verify.ps1` comprueban son
> los de **`MANIFEST.json`**. Esta tabla es documental: se actualiza solo la fila
> del archivo que cambia y las demás pueden estar en una revisión anterior.

| Archivo | SHA-256 |
|---|---|
| `AdminBotManager.java` | `B8EC1758187A35BA5E99336BB151B01F26905F2B0D56BB7152EB23CA045E9FC2` |
| `AdminHandoffTest.java` | `5B0B2F6D436EA0EF16B96FD465F3F86130F68E22D4B18B9E7B33C1F1F6BDED8E` |
| `AdminSwapTest.java` | `A119692CAC9F6F947A88928F195AAC65F6D8E4DB921DD949A0700157FB61CDFE` |
| `AutoPlayTaskManager.java` | `00631DC2563AA7F066D2274704E62460CA7D5F0A4A7796FA29F28B29A37F91CF` |
| `AutoPlay.java` | `C8B15D3CEADDBB46FA30F6EF0706053F8B7233CD0207C140D0A97E3A3811118B` |
| `Main.htm` | `F66EE0B6C0E64BC7EB49ADD9C3043D3C4C02DF5DAE42817E53CC650A46D0E1E3` |
| `AutoPlayProfileBuilder.java` | `365CD1E2678839E1CB98203E88407193E067B520A11F2E438547F90447093E7B` |
| `BotFormationMode.java` | `18D72663C98365B2C02DE65B941E9AD7936EECEF93126E2F2C0560EC739AA7FF` |
| `BotCommand.java` | `D0A76E0B131BA4E2BAC417C864A4E7F6F912D382CE58E9F353D1CDE715ED8A52` |
| `BotManagerBypass.java` | `492B1C43DF3054C0890075EBA652E4940B291B583D17D69B50AE6D9E880672E8` |
| `MasterHandler.java` | `B402FC71203A38B8E1DC8FB8424D5CC54A9BC1AD1E0AC48A899D36026D5FD17F` |
| `Resurrection.java` | `649ACCCA3AF205F963AD097E7E7490C0F150CB525100B84C14ECE677994C2998` |
| `AutoPotionTaskManager.java` | `656ECB118F3ABE53592A032602BD7BA5D663608F256507D6CD74FA6D05349776` |
| `AutoUseTaskManager.java` | `16D16EC5ED44FA9674DC77BA9B987590014111920C8EB741A6E1B55BFE316948` |
| `10000-10099.xml` | `3A98218D6F0265BDC528305390B6D3C82E81199A3387CE5CA9DD1B950A3E291B` |

### Clases compiladas (entradas del JAR)

Estos hashes **no** son de payload: son de la salida de `javac 27` con los flags
del instalador (`-g -encoding UTF-8`), y `install.ps1` los vuelve a comprobar
tras compilar y tras el staging del JAR. Son la referencia exacta de esta base.

| Entry | Tamaño | major | SHA-256 |
|---|---|---|---|
| `AutoPotionTaskManager.class` | 5051 | 71 | `4BD1EB1491763A405CD5C6061F58B4EA71BFAFB5F8247492FC4FB6B54C548E8D` |
| `AutoPotionTaskManager$SingletonHolder.class` | 687 | 71 | protegida: debe quedar byte-idéntica en el JAR |
| `AutoUseTaskManager$AutoUse.class` | 21657 | 71 | `29537E614FE48566868DF1926E90EB6A7AFD3C556E41E970C3EF318275C4482B` |
| `AutoPlayTaskManager$AutoPlay.class` | 14177 | 71 | `C18C2EF473DBBD4D38E74C8B16A051FAFB6A030A5A8CA71DAF4DD71E86B97CF6` |
| `AutoPlayTaskManager.class` | 3887 | 71 | `125EDB816B47F5DF114D4C64FE4BBFDB31EF79D6B60846B9EDD8000EA9D20804` |
| `AutoUseTaskManager.class` | 3592 | 71 | `2FACFB82B05DF91FAE83CBE105916B35A85CB09305FB7479901B6D751BA9808D` |

> **Revisión JDK 27 (major 71).** Adaptación a la base real del workspace: el
> toolchain declarado pasa de JDK 25 / `class_major 69` a **JDK 27 /
> `class_major 71`**, y `core_entries` incorpora el `expected_class_sha256`
> medido de una compilación real (`javac 27`) contra el `GameServer.jar`
> objetivo. Los `expected_size` no cambiaron: ya eran correctos para esta base
> (5051 / 19613 / 14177 / 3887 / 2913). Se resuelve la contradicción entre
> `reference_class.size_bytes` (4958) y el `expected_size` de la primera entry
> (5051): la clase de referencia pasa a ser una salida **real** de esta
> compilación y deja de usarse para clasificar el estado del runtime, con lo que
> el estado `ALREADY-INSTALLED` vuelve a ser alcanzable. `verify.ps1` C2 ahora
> recorre **todas** las entradas COMPILE (antes solo la última, dejando sin
> verificar `AutoPotionTaskManager` y `AutoUseTaskManager`), C3 y C5 devuelven
> **SKIP** cuando no hay pre-estado en lugar de PASS, y C4 comprueba el SHA-256
> de cada entry. El EOL del payload se normaliza a CRLF puro (dos ficheros tenían
> LF y LF mezclado) y `.gitattributes` pasa a marcar los archivos como binarios,
> de modo que un checkout de Git no invalida los hashes; además los tres scripts
> calculan los hashes sobre bytes canónicos (CR+LF → LF) y son inmunes al EOL.
>
> **v2.7 (consolidación del runtime).** La receta absorbe el trabajo acumulado
> del runtime: `AdminBotManager.java` pasa a la versión consolidada del `.bot`
> (fila `[Login][Config]`; `Login` ejecuta `AdminSwapTest.runSwap` sin party o
> `AdminHandoffTest.runHandoff` con party; `OrchestratorState` con captura
> antes del detach y transferencia tras adjuntar el nuevo controlador; guard de
> Party Leader solo en ADD/REMOVE) y `MasterHandler.java` registra
> `AdminHandoffTest` (`//handoff`) y `AdminSwapTest` (`//swaptest`), que pasan
> a payload de producción por ser dependencias del flujo Login del `.bot`
> (nuevas entradas COPY, cubiertas por backup/rollback como ausentes en runtime
> limpio). También se corrige el hash de `AutoPlayTaskManager.java`, obsoleto
> desde la modificación del payload del 8 de octubre: el fuente compila con
> `javac -g` (los flags de `install.ps1`) a los tamaños esperados del
> manifiesto (`$AutoPlay` 14177, `AutoPlayTaskManager.class` 3887). La revisión
> **v2.3** añade el target mode **Monster+Raidboss**
> (`case 4` en `isTargetModeValid`: misma regla que Monsters pero con raids
> incluidos, más los relajados de scan para raids — Respectful Hunting y navmesh
> no los filtran), con la fila `%mode4%` en `Main.htm`, el `case "mode4"` y su
> radio en `AutoPlay.java`, y el espejo `mode[0-4]` en `AdminBotManager.java`.
> Cambia el tamaño compilado de `AutoPlayTaskManager$AutoPlay.class` (esperado
> 14177 con `javac -g`); el resto de la revisión v2.2 queda intacto.
>
> **v2.6 (curacion del Cardinal).** `AutoUseTaskManager` pasa a seleccionar
> curaciones por politica, no por orden de lista: `Chain Heal (1553)` primero
> (1 o varios heridos), `Major Heal (1401)` como alternativa de objetivo unico
> y `Major Group Heal (1402)` como alternativa grupal; todas bajo el mismo
> umbral `getAutoPotionPercent()`, con el Cardinal contado y `Balance Life
> (1335)` excluida. Antes de cada intento, `canUseMagic(player, wounded,
> heal)` valida reutilizacion (via `isSkillDisabled`), MP, Spirit Ore y las
> condiciones reales del skill; si falla se evalua el siguiente candidato y
> nunca se lanzan dos skills por tick. Los skills `HEAL` quedan excluidos de
> la rama de canciones/danzas (`targetType PARTY`), que casteaba
> `Major Group Heal` a ciegas. Se retiran `selectSmartHeal` y
> `someoneNeedsHeal`. Cambian los tamanos compilados (build de receta con
> `-g`): `AutoUseTaskManager$AutoUse.class` (esperado 19613) y
> `AutoUseTaskManager.class` (esperado 2913); el resto de v2.5 queda intacto.
>
> **v2.7 (selección de runs del verificador).** `verify.ps1` y `rollback.ps1`
> resuelven `latest` solo a corridas de instalación reales (identificables por
> `log-install.txt` o `pre\jar-entries-pre.txt`), de modo que los directorios
> temporales de compilación, verificación y diagnóstico ya no se evalúan como
> instalaciones. Cuando no hay corrida válida, C8/C9 se reportan `SKIP` (nunca
> PASS ni FAIL) y el resultado global es `PASS_WITH_SKIP` (exit 2) en lugar de
> un PASS completo.
>
> **v2.9 (alcance de las curaciones del Cardinal).** v2.6 dejaba fuera seis de
> las nueve curaciones: `selectHealCandidates` solo ofrecía `1553` más `1402`
> (2+ heridos) o `1401` (1 herido), y como la rama `BUFFS` hace
> `continue` en todo skill con `EffectType.HEAL`, esas seis no tenían **ninguna**
> vía de casteo: `Heal (1011)`, `Battle Heal (1015)`, `Group Heal (1027)`,
> `Greater Heal (1217)`, `Greater Battle Heal (1218)` y `Greater Group Heal
> (1219)` quedaban inertes aunque estuvieran configuradas en el `.play`. Ahora
> las dos ramas devuelven **las nueve** y solo cambia el orden: con 2+ heridos
> primero las multiobjetivo (`1553`, `1402`, `1219`, `1027`) y luego las de
> objetivo único; con 1 herido primero las de objetivo único y las multiobjetivo
> como último recurso. `getKnownHeal` decide solo participación (configurada en
> `AUTO_USE_BUFFS`, conocida, `EffectType.HEAL`), así que cada id conserva su
> `targetType` nativo: `ONE` (`1011`, `1015`, `1217`, `1218`, `1401`), `PARTY`
> (`1027`, `1219`, `1402`, se expande sobre la party en el handler nativo) y
> `AREA_FRIENDLY` (`1553`, encadena nativamente desde el miembro al que se
> apunta). `Chain Heal (1553)` usa el efecto `HealPercent`, que en el runtime
> mapea a `EffectType.HEAL` igual que `Heal`, así que pasa el mismo filtro. El
> umbral sigue siendo el único `getAutoPotionPercent()` del `.play`, y
> Restore Life (`1258`), Balance Life (`1335`) y Self Heal (`1216`/`6847`) siguen
> fuera.
>
> **v2.9 (fall-through del candidato abortado).** `castOnSupportTarget` devolvía
> `true` en cuanto `checkCondition` pasaba, aunque `Player.doCast()` puede
> abortar **en silencio** dentro de `Creature.beginCast()`: con la lista de
> objetivos nativa vacía (`AreaFriendly` la vacía cuando el objetivo no pasa
> `checkTarget`, p. ej. sin línea de vista; `One` solo la vacía con objetivo nulo
> o muerto), con el caster mudo, o con el ítem ausente, `beginCast` hace
> `setCastingNow(false)` y **no castea nada**. Como el bucle de candidatos hacía
> `break BUFFS` con ese `true`, el primer candidato abortado (y `1553` es el
> primero en las dos ramas) impedía intentar los otros ocho en esa pasada. Ahora
> el método devuelve el resultado real de **ese** intento. `isCastingNow()` se lee
> directo: la pasada `BUFFS` solo corta con ese flag (`if (player.isCastingNow()) break
> BUFFS;`) y todo camino de aborto de `beginCast` lo limpia, así que solo puede estar
> en `true` porque este intento arrancó el casteo. El flag simultáneo **sí** se
> compara con el estado heredado, porque ese guard no lo mira: un casteo simultáneo
> ya en curso (skill de ítem, hierba, cúbic) sobrevive al guard y no es resultado de
> este intento, así que solo cuenta si se enciende durante la llamada. Sin esa
> distinción, un heal abortado con un casteo simultáneo activo se habría reportado
> como éxito y habría cerrado la pasada. No se añade ninguna validación propia: solo
> se **lee** el resultado del motor. Mismo efecto en la rama de resurrección (un
> intento abortado ya no corta los heals y buffs, que es la intención documentada de
> v2.6). Cambian los tamaños compilados: `AutoUseTaskManager$AutoUse.class`
> (esperado 19880) y `AutoUseTaskManager.class` (2913 bytes, hash distinto por
> desplazamiento de `LineNumberTable`); el resto de v2.8 queda intacto.
>
> **v2.9 (rotación de las curaciones).** Con el orden fijo anterior, el primer
> candidato disponible ganaba siempre el turno: `1553` encabezaba las dos ramas y
> `1401` (reuso de 1 s) era el siguiente en la rama de un solo herido, así que en
> juego **solo se veían Chain Heal y Major Heal** y las otras siete curaciones
> configuradas no se lanzaban nunca. Ahora la selección es por **escalones de
> gravedad** y cada escalón **rota un paso por pasada** (`HEAL_TURNS`, un contador
> por personaje): `1401` grave, `1218`/`1217` medias, `1015`/`1011` leves,
> `1402`/`1219`/`1027` grupales y `1553` cadena. El orden de los escalones depende
> del HP del miembro más herido (`CRITICAL_HP_PERCENT` 30, `MODERATE_HP_PERCENT`
> 60, política interna y no configuración) y las grupales siguen yendo primero
> cuando hay 2 o más heridos. Con la rotación, cualquier cura configurada acaba
> teniendo su turno, sin que ninguna monopolice el casteo. Cambian los tamaños
> compilados: `AutoUseTaskManager$AutoUse.class` (esperado 21657) y
> `AutoUseTaskManager.class` (3592 bytes, crece por los escalones y el contador);
> el resto de v2.8 queda intacto.

Clase compilada de referencia: **21657 bytes**, class major **71**,
`AutoUseTaskManager$AutoUse.class`, SHA-256
`29537E614FE48566868DF1926E90EB6A7AFD3C556E41E970C3EF318275C4482B`
(salida real de `javac 27` con los flags del instalador contra el
`GameServer.jar` objetivo).

> La equivalencia es **funcional y además reproducible byte a byte**: con el
> mismo JDK 27 y los mismos flags, el `javac` del instalador emite exactamente
> las cinco clases cuyos tamaño, `major` y SHA-256 declara `core_entries`. Los
> hashes son un gate real (`install.ps1` aborta si no coinciden), no una
> referencia informativa. Un JDK distinto cambia el orden del constant pool y
> por tanto el hash: si se cambia de toolchain hay que recalibrar el manifiesto
> compilando de nuevo, nunca relajando el gate.

---

## 25. Autonomía

Este paquete **no depende de ningún repositorio externo**. No necesita ningún otro
proyecto, framework, índice o documento. Puede copiarse a cualquier carpeta y
ejecutarse.

## 26. Limitaciones conocidas

- Como máximo **8 Players controlables** (slots 2..9). Con más personajes en la
  cuenta, los primeros 8 son gestionables y el resto no es alcanzable. Es un
  límite de diseño, **no** del core.
- Una cuenta de un solo personaje **no tiene slots de bot**: todos se ven vacíos
  y `ALL` no activa nada.
- Un slot lógico sin personaje es un estado operativo válido.
- `BOT_REGISTRY`, `BOT_OWNERS` y `PARTY_AUTOPLAY_ORCHESTRATORS` son estado en
  memoria: **no persisten** entre reinicios.
- Con el servidor apagado, ejecuta `.bot off` antes de detenerlo.
- **Command Channel no está soportado como scope.** Varias cuentas pueden
  coexistir, pero cada una se gobierna por su propia party.
- La receta no aprovisiona personajes, clases, niveles, habilidades ni equipo.
- La ventana muestra nombre, nivel, clase, estado y `[Skills]`. Nunca muestra
  charId, objectId ni nivel de acceso.
- Mientras Assist está ON, el target del Party Leader es la fuente
  prioritaria y la adquisición nativa queda forzada a **solo Monsters**. Al apagar
  el switch, cada Player recupera exactamente su modo de target original.
- El modo forzado a Monsters excluye también Players: con Assist ON no hay
  asistencia PvP. Es el comportamiento deseado para un sistema PvE.
- **Los buffs automáticos no se castean en zona PEACE (ciudad).** El motor los
  salta con `break BUFFS` (comentario original: *"Fixes start area issue"*), y lo
  mismo hacen AutoItems y AutoPotion en peace zone. En campo, con AutoPlay ON,
  buffean normalmente. Es comportamiento del core, no del BotAI.

> `CharMaxNumber` controla la **creación** de personajes. La lista de selección
> se lee sin límite, así que un cliente puede mostrar menos slots de los que el
> servidor permite.

---

## 27. Fuera de alcance

- **SQL**: no hay filas nuevas ni personajes creados por el script.
- **Autogeneración de skills y buffs**: no se congelan listas por clase. Un Player
  usa lo que tenga configurado, o lo que se configure desde `[Skills]`.
- **Clan y Command Channel**: nunca se consultan para decidir el alcance.
- **Niveles de acceso**: no se usa GM para nada funcional.
- **Botón `[T]` / To Village**: **funcionalidad futura, no implementada en v2.0.**
  Este runtime no expone ninguna acción nativa de "To Village" para un Player
  clientless, así que queda reservada para una investigación independiente. No hay
  implementación parcial ni coordenadas fijadas en esta versión.

---

## 28. Switch Spoil

Política de cadáver **por personaje**, persistida en `character_variables` con la
clave `BOT_SPOIL` (**default ON**). Es una bandera pura de política: **ninguna
skill se lee ni se escribe**, así que el switch pertenece a cualquier personaje
tenga o no la skill Spoil.

| Estado | Comportamiento |
|---|---|
| **ON** (default) | Exactamente como siempre: el manager retiene el cadáver como target (el loot) y el motor nativo barre si el personaje tiene Spoil configurado. |
| **OFF** | El manager suelta target + intención sobre cualquier target muerto en su próximo ciclo (≤700 ms), sin interrumpir casteos. Los bots vuelven al anillo con Formation ON; también se aplica a tu propio personaje. |

- Se alterna desde la fila **Spoil** del `.play` nativo o de la ventana **Config** (§29).
- Persiste entre reinicios (una sesión nueva lo lee de `character_variables`).
- Con OFF, un cadáver ya spoileado pendiente se suelta y ese loot se pierde al
  despawn: es el comportamiento esperado de "OFF = el cadáver no me interesa".

## 29. Ventana Config (.play espejo)

El botón **Config** (cabecera y cada fila de slot) abre el `.play` del personaje
objetivo **espejado dentro del panel `.bot`**: se lee el HTML nativo `Main.htm`
sin modificarlo y se reescriben los bypass en runtime, de modo que cada casilla
actúa sobre el personaje objetivo y nunca sobre el que hizo click.

- **Contenido (v1, mínimo):** Settings (Auto Attack / Auto Loot / Respectful
  Hunting / Long Range Targeting / **Spoil**) + Target Mode (5 radios) +
  **HP Percent (n) … Apply** + **Back**.
- **Fuera en v1:** Select Skills (usa el `[Skills]` de la propia fila), Select
  Supply Items y Select Healing Potion (siguen disponibles en el `.play` nativo
  si hicieran falta).
- **Aislamiento:** el mismo circuito que Select Skills — misma cuenta y misma
  party (`resolveSkillTarget`), nunca GM: otro personaje es inalcanzable aunque
  se falsifique el charId.
- **Persistencia inmediata:** cada acción escribe `AUTO_USE_ACTIONS/BUFFS/SKILLS/
  ITEMS/POTION` y las 7 posiciones de `AUTO_USE_SETTINGS` en
  `character_variables` al momento, porque un bot clientless nunca hace logout.
- Con esto, toda la configuración de AutoPlay vive en `.bot`; el `.play` nativo
  queda intacto pero ya no hace falta usarlo.

