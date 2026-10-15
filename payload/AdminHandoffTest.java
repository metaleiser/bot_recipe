
package handlers.chat.commands.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.OfflinePlayConfig;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.sql.OfflinePlayTable;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.entity.instancezone.Instance;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.AntiFeedManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventDispatcher;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerSelect;
import org.l2jmobius.gameserver.mechanics.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.mechanics.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.ConnectionState;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.holders.CharacterInfoHolder;
import org.l2jmobius.gameserver.network.serverpackets.CharSelected;
import org.l2jmobius.gameserver.network.serverpackets.CharSelectionInfo;
import org.l2jmobius.gameserver.network.serverpackets.RestartResponse;
import org.l2jmobius.gameserver.network.serverpackets.SSQInfo;

/**
 * POC TEMPORAL GM-only: //handoff &lt;absSlot&gt; - Controller Handoff entre Player
 * vivos de la misma cuenta, sobre el MISMO GameClient. Borrar tras las pruebas.
 *
 * Reglas del POC: NO storeAndDelete, NO deleteMe, NO leaveParty, NO stopAutoPlay,
 * NO client.load() sobre el target, NO LeaveWorld, NO cerrar socket. Los 9 Player
 * persisten; solo cambia client.player / player.client.
 */
public class AdminHandoffTest implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger("HANDOFF");
	/** Blindaje de reentrada por cuenta: un solo handoff simultaneo por cuenta. */
	private static final ConcurrentMap<String, Boolean> BUSY = new ConcurrentHashMap<>();
	private static final String[] COMMANDS =
	{
		"admin_handoff"
	};

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if ((activeChar == null) || !activeChar.isGM())
		{
			return false;
		}
		GameClient client = activeChar.getClient();
		if ((client == null) || (client.getPlayer() != activeChar))
		{
			activeChar.sendMessage("[HANDOFF] ABORT: sin GameClient valido.");
			return false;
		}
		StringTokenizer st = new StringTokenizer(command);
		st.nextToken();
		if (!st.hasMoreTokens())
		{
			activeChar.sendMessage("[HANDOFF] Uso: //handoff list | //handoff <absSlot>");
			return false;
		}
		String arg = st.nextToken().trim();
		if ("list".equalsIgnoreCase(arg))
		{
			for (int i = 0; ; i++)
			{
				CharacterInfoHolder info = client.getCharSelection(i);
				if (info == null)
				{
					break;
				}
				Player w = World.getPlayer(info.getObjectId());
				String state = (w == null) ? "OFFLINE" : ((w.getClient() == null) ? "CLIENTLESS" : "INWORLD");
				activeChar.sendMessage(i + ": " + info.getName() + " oid=" + info.getObjectId() + " " + state);
			}
			return true;
		}
		int slot;
		try
		{
			slot = Integer.parseInt(arg);
		}
		catch (NumberFormatException e)
		{
			activeChar.sendMessage("[HANDOFF] ABORT: slot no numerico.");
			return false;
		}
		runHandoff(activeChar, slot);
		return true;
	}

	
	/**
	 * Plan precalculado: toda la validacion termina ANTES de mutar cualquier binding.
	 */
	private static final class Plan
	{
		CharSelectionInfo csi;
		int freshSlot;
		int targetOid;
		Player target;
		Party party;
	}

	/**
	 * Guards 1-14 sin mutar bindings. @return null = abortar (nada modificado todavia).
	 */
	static Plan validate(Player current, int absSlot, String tag)
	{
		GameClient client = current.getClient();
		String account = client.getAccountName();
		if ((account == null) || account.isEmpty())
		{
			current.sendMessage(tag + "ABORT: cuenta invalida.");
			return null;
		}
		if (client.getConnectionState() != ConnectionState.IN_GAME)
		{
			current.sendMessage(tag + "ABORT: estado de conexion no IN_GAME.");
			return null;
		}

		// Slot absoluto -> objectId via _charSlotMapping real de la cuenta.
		CharacterInfoHolder info = client.getCharSelection(absSlot);
		if (info == null)
		{
			current.sendMessage(tag + "ABORT: slot " + absSlot + " vacio o fuera de rango.");
			return null;
		}
		int targetOid = info.getObjectId();
		if (targetOid == current.getObjectId())
		{
			current.sendMessage(tag + "ABORT: el slot es el personaje actual.");
			return null;
		}

		Player target = World.getPlayer(targetOid);
		if (target == null)
		{
			current.sendMessage(tag + "ABORT: target no esta en World (este POC solo adopta vivos clientless).");
			return null;
		}
		if (target.getClient() != null)
		{
			current.sendMessage(tag + "ABORT: target tiene GameClient (no clientless).");
			return null;
		}
		if (!target.getAccountNamePlayer().equals(current.getAccountNamePlayer()))
		{
			current.sendMessage(tag + "ABORT: target de otra cuenta.");
			return null;
		}

		Party party = current.getParty();
		if (party == null)
		{
			current.sendMessage(tag + "ABORT: el personaje actual no tiene party.");
			return null;
		}
		if (target.getParty() != party)
		{
			current.sendMessage(tag + "ABORT: target no comparte la party del actual.");
			return null;
		}

		if (!current.isAutoPlaying())
		{
			current.sendMessage(tag + "ABORT: el personaje actual necesita .play ON.");
			return null;
		}
		if (current.isInStoreMode() || current.isInVehicle() || current.isRegisteredOnEvent() || OlympiadManager.getInstance().isRegistered(current))
		{
			current.sendMessage(tag + "ABORT: estado incompatible del actual (tienda/vehiculo/evento/olympiad).");
			return null;
		}
		if (target.isInStoreMode() || target.isInVehicle())
		{
			current.sendMessage(tag + "ABORT: estado incompatible del target (tienda/vehiculo).");
			return null;
		}

		// ON_PLAYER_SELECT veto ANTES de mutar nada.
		if (EventDispatcher.getInstance().hasListener(EventType.ON_PLAYER_SELECT, Containers.Players()))
		{
			TerminateReturn terminate = EventDispatcher.getInstance().notifyEvent(new OnPlayerSelect(target, target.getObjectId(), target.getName(), client), Containers.Players(), TerminateReturn.class);
			if ((terminate != null) && terminate.terminate())
			{
				current.sendMessage(tag + "ABORT: veto de ON_PLAYER_SELECT.");
				return null;
			}
		}

		// Lista fresca de seleccion + freshSlot por objectId, precalculada sin enviar nada.
		CharSelectionInfo csi = new CharSelectionInfo(account, client.getSessionId().playOkID1);
		int fresh = indexOf(csi.getCharInfo(), targetOid);
		if (fresh < 0)
		{
			current.sendMessage(tag + "ABORT: target ausente de la lista fresca.");
			return null;
		}

		Plan plan = new Plan();
		plan.csi = csi;
		plan.freshSlot = fresh;
		plan.targetOid = targetOid;
		plan.target = target;
		plan.party = party;
		return plan;
	}

	static void runHandoff(Player current, int absSlot)
	{
		String tag = "[HANDOFF] ";
		GameClient client = current.getClient();
		String account = client.getAccountName();

		// Blindaje de reentrada por cuenta: un solo handoff simultaneo.
		if (BUSY.putIfAbsent(account, Boolean.TRUE) != null)
		{
			current.sendMessage(tag + "ABORT: ya hay un handoff en curso en esta cuenta.");
			return;
		}
		boolean locked = false;
		try
		{
			// playerLock no bloqueante, patron de CharacterSelect.
			locked = client.getPlayerLock().tryLock();
			if (!locked)
			{
				current.sendMessage(tag + "ABORT: playerLock ocupado (seleccion en curso).");
				return;
			}
			Plan plan = validate(current, absSlot, tag);
			if (plan == null)
			{
				return;
			}
			perform(current, plan, tag);
		}
		finally
		{
			if (locked)
			{
				client.getPlayerLock().unlock();
			}
			BUSY.remove(account);
		}
	}
	
	/**
	 * FASE 1 (detach) -> FASE 2 (seleccion) -> FASE 3 (adopt) -> FASE 4 (CharSelected).
	 * Ningun punto ejecuta storeAndDelete / deleteMe / leaveParty / stopAutoPlay / client.load.
	 */
	static void perform(Player current, Plan plan, String tag)
	{
		GameClient client = current.getClient();
		Player target = plan.target;
		LOGGER.info(tag + current.getName() + " -> " + target.getName() + " freshSlot=" + plan.freshSlot);
		// PASO 1 del transfer: capturar el estado de orquestacion ANTES del detach (el task
		// periodico de 700 ms dropea al orquestador sin cliente via dropOrchestrator).
		final AdminBotManager.OrchestratorState orchState = AdminBotManager.captureOrchestratorState(current);

		// FASE 1a: durabilidad de la config AUTO_USE en character_variables antes de desprender
		// (espejo de AutoPlay.ON_PLAYER_LOGOUT; el listener no corre porque no hay logout).
		persistAutoUse(current);

		// FASE 1b: paridad con startOfflinePlay (Player.java:7951).
		AntiFeedManager.getInstance().removePlayer(AntiFeedManager.GAME_ID, current);

		// FASE 1c: estado OfflinePlay + persistencia nativa.
		current.setOfflinePlay(true);
		if (OfflinePlayConfig.RESTORE_AUTO_PLAY_OFFLINERS)
		{
			OfflinePlayTable.getInstance().storeOfflinePlay(current);
		}

		// FASE 1d: limpieza del cliente. DESVIACION CONTROLADA respecto al nativo:
		// en vez de setDetached(true) sobre el socket vivo se anulan las referencias
		// (estado equivalente al restore de arranque y a los bots clientless de BOTAI).
		client.setPlayer(null);
		current.setClient(null);

		
// RESTORE GUARD: si P_old queda dentro de una instancia (instanceId > 0), el restore
		
// lo recargaria "on" (World + party + autoplay) pero invisible, sin region valida.
		
drainInstanceState(current, tag);

		// Cinturon y tiradores: si un invariante post-detach cayera, revertir binds.
		if ((current.getClient() != null) || (client.getPlayer() != null) || (World.getPlayer(plan.targetOid) == null) || (current.getParty() == null) || !current.isAutoPlaying())
		{
			LOGGER.severe(tag + "Invariante post-detach rota. Rollback de binds.");
			client.setPlayer(current);
			current.setClient(client);
			current.setOfflinePlay(false);
			if (OfflinePlayConfig.RESTORE_AUTO_PLAY_OFFLINERS)
			{
				OfflinePlayTable.getInstance().removeOfflinePlay(current);
			}
			return;
		}
		LOGGER.info(tag + "oldPlayer=" + current.getObjectId() + " client=null offlinePlay=true world=true party=" + (current.getParty() != null) + " autoPlay=" + current.isAutoPlaying());

		// FASE 2: secuencia de seleccion demostrada por AdminSwapTest (CASO A).
		client.setConnectionState(ConnectionState.AUTHENTICATED);
		client.sendPacket(RestartResponse.valueOf(true));
		client.sendPacket(plan.csi);
		client.setCharSelection(plan.csi.getCharInfo());

		// FASE 3: adopcion del target VIVO. PROHIBIDO client.load() aqui.
		// Limpieza minima de holders para que ON_PLAYER_LOGIN (async, en EnterWorld)
		// re-rellene una sola vez desde character_variables (evita duplicados).
		clearAutoUseHolders(target);
		target.setClient(client);
		client.setPlayer(target);
		target.setOnlineStatus(true, true);
		target.setOfflinePlay(false);
		if (OfflinePlayConfig.RESTORE_AUTO_PLAY_OFFLINERS)
		{
			OfflinePlayTable.getInstance().removeOfflinePlay(target);
		}
		
		// GPF ExRotation: pausar AutoPlay/AutoUse de los OTROS miembros de la party durante
		// la transicion del target. El gate de AutoPlayTaskManager cubre al target (client
		// adjunto, !IN_GAME) pero NO a los bots clientless (client==null pasa el gate): siguen
		// emitiendo ExRotation/movimiento al cliente en ENTERING -> GPF ExRotation al entrar.
		// Se reactivan tras IN_GAME en el resync. Orden seguro: setOfflinePlay(false) ANTES de
		// stopAutoPlay para que setAutoPlaying(false) no borre character_offline_play.
		final List<Player> pausedBots = pausePartyBots(plan.party, target, tag);

		// FASE 3b: decay temporal del target. Diagnostico definitivo (log 04:30:43):
		// EnterWorld:456 -> spawnMe -> World.addObject:190 dispara "Duplicate character"
		// porque el target YA esta en _allPlayers y lo mata x2 (party/world fuera + NPE).
		// decayMe() lo saca de _allPlayers/_visibleObjects SIN deleteMe/leaveParty/
		// stopAutoPlay/stopAutoUse: el MISMO objeto conserva party, buffs, variables,
		// posicion y autoplay. EnterWorld:456 lo vuelve a registrar normalmente.
		LOGGER.info("[HANDOFF DECAY] BEFORE target=" + target.getName()
			+ " spawned=" + target.isSpawned()
			+ " region=" + target.getWorldRegion()
			+ " worldPlayer=" + (World.getPlayer(target.getObjectId()) == target)
			+ " party=" + (target.getParty() != null)
			+ " autoPlay=" + target.isAutoPlaying()
			+ " offlinePlay=" + target.isOfflinePlay()
			+ " client=" + (target.getClient() == null ? "null" : "attached"));
		target.decayMe();
		boolean decayOk = !target.isSpawned()
			&& (World.getPlayer(target.getObjectId()) == null)
			&& (target.getParty() != null)
			&& plan.party.getMembers().contains(target)
			&& target.isAutoPlaying()
			&& (target.getClient() == client);
		LOGGER.info("[HANDOFF DECAY] AFTER target=" + target.getName()
			+ " spawned=" + target.isSpawned()
			+ " worldPlayer=" + (World.getPlayer(target.getObjectId()) == null ? "null" : "PRESENT")
			+ " party=" + (target.getParty() != null)
			+ " autoPlay=" + target.isAutoPlaying()
			+ " offlinePlay=" + target.isOfflinePlay()
			+ " client=" + (target.getClient() == null ? "null" : "attached")
			+ " decayOk=" + decayOk);
		if (!decayOk)
		{
			// ABORT sin improvisar otro metodo: NO se destruye el target.
			// Se devuelve a clientless-offlinePlay (estado seguro: sobrevive al
			// gate de AutoPlay) y se suelta el client para que la pantalla de
			// seleccion ya enviada siga usable manualmente.
			// El viejo (current) ya quedo detached con su fila offline-play persistida
			// en FASE 1c: se reanexa como via segura de recuperacion (mismo patron del
			// ABORT pre-detach) para no dejarlo detached con fila huerfana.
			LOGGER.severe("[HANDOFF] target decay verification FAILED - abort sin destruir nada");
			target.setOfflinePlay(true);
			target.setClient(null);
			client.setPlayer(null);
			if (OfflinePlayConfig.RESTORE_AUTO_PLAY_OFFLINERS)
			{
				OfflinePlayTable.getInstance().removeOfflinePlay(current);
			}
			current.setClient(client);
			client.setPlayer(current);
			current.setOnlineStatus(true, true);
			current.setOfflinePlay(false);
			return;
		}
		LOGGER.info("[HANDOFF] target prepared for EnterWorld");

		// FASE 4: misma secuencia final que AdminSwapTest. El cliente oficial enviara
		// EnterWorld por su cuenta; este POC NUNCA lo invoca desde servidor.
		client.sendPacket(new SSQInfo());
		client.setConnectionState(ConnectionState.ENTERING);
		client.sendPacket(new CharSelected(target, client.getSessionId().playOkID1));

		LOGGER.info(tag + "targetPlayer=" + target.getObjectId() + " client=GAMECLIENT online=" + target.isOnline() + " world=" + (World.getPlayer(target.getObjectId()) == target) + " autoPlay=" + target.isAutoPlaying());
		LOGGER.info(tag + "partySize=" + plan.party.getMembers().size());
		LOGGER.info(tag + "clientState=" + client.getConnectionState() + " clientPlayer=" + (client.getPlayer() == null ? "null" : String.valueOf(client.getPlayer().getObjectId())));
		LOGGER.info(tag + "SUCCESS " + current.getName() + " -> " + target.getName() + " (esperar EnterWorld del cliente)");
		schedulePartyResync(target, tag, pausedBots);
		// PASO 2 del transfer: reasociar ASSIST/PARTY_AUTOPLAY/FORMATION/BOT_OWNERS al nuevo
		// controlador con su semantica individual (el grupo no cambia de valor, solo de clave).
		AdminBotManager.transferOrchestratorState(orchState, target);
		// El viejo ya no es offline-play nativo sino clientless de BOTAI: su fila
		// persistida en FASE 1c debe eliminarse para no restaurarlo en el arranque.
		if (OfflinePlayConfig.RESTORE_AUTO_PLAY_OFFLINERS)
		{
			OfflinePlayTable.getInstance().removeOfflinePlay(current);
		}
	}

	private static int indexOf(List<CharacterInfoHolder> list, int objectId)
	{
		for (int i = 0; i < list.size(); i++)
		{
			if (list.get(i).getObjectId() == objectId)
			{
				return i;
			}
		}
		return -1;
	}
	
	/**
	 * Durabilidad de la config AUTO_USE del personaje que se desprende.
	 * Espejo de AutoPlay.ON_PLAYER_LOGOUT (voiced/AutoPlay.java:123-155): ese listener
	 * solo corre en deleteMe, y aqui NO hay logout. La fuente de verdad sigue siendo
	 * character_variables; no se cambia la arquitectura de AutoUse.
	 */
	static void persistAutoUse(Player p)
	{
		try
		{
			p.getVariables().setIntegerList(PlayerVariables.AUTO_USE_ACTIONS, new ArrayList<>(p.getAutoUseSettings().getAutoActions()));
			p.getVariables().setIntegerList(PlayerVariables.AUTO_USE_BUFFS, new ArrayList<>(p.getAutoUseSettings().getAutoBuffs()));
			p.getVariables().setIntegerList(PlayerVariables.AUTO_USE_SKILLS, new ArrayList<>(p.getAutoUseSettings().getAutoSkills()));
			p.getVariables().setIntegerList(PlayerVariables.AUTO_USE_ITEMS, new ArrayList<>(p.getAutoUseSettings().getAutoSupplyItems()));
			int potionId = p.getAutoUseSettings().getAutoPotionItem();
			if (potionId < 1)
			{
				p.getVariables().remove(PlayerVariables.AUTO_USE_POTION);
			}
			else
			{
				p.getVariables().set(PlayerVariables.AUTO_USE_POTION, potionId);
			}
			List<Integer> settings = new ArrayList<>(7);
			settings.add(0, p.getAutoPlaySettings().getOptions());
			settings.add(1, p.isAutoPlaying() ? 1 : 0);
			settings.add(2, p.getAutoPlaySettings().doPickup() ? 1 : 0);
			settings.add(3, p.getAutoPlaySettings().getNextTargetMode());
			settings.add(4, p.getAutoPlaySettings().isShortRange() ? 1 : 0);
			settings.add(5, p.getAutoPlaySettings().getAutoPotionPercent());
			settings.add(6, p.getAutoPlaySettings().isRespectfulHunting() ? 1 : 0);
			p.getVariables().setIntegerList(PlayerVariables.AUTO_USE_SETTINGS, settings);
		}
		catch (Exception e)
		{
			LOGGER.warning("[HANDOFF] persistAutoUse fallo (no bloqueante): " + e);
		}
	}

	/**
	 * Limpieza minima y reversible de los holders en memoria del target ANTES de que
	 * AutoPlay.ON_PLAYER_LOGIN (async, disparado por EnterWorld -> onPlayerEnter)
	 * vuelva a agregar las listas desde character_variables: ese listener hace APPEND
	 * sin clear, asi que con listas ya pobladas duplicaria las entradas. Al limpiar
	 * aqui, el listener rellena exactamente una vez = semantica de login nativa.
	 * Si variables esta vacio (nunca configurado), las listas quedan vacias igual
	 * que en un login reciente. No se persiste nada: character_variables manda.
	 */
	static void clearAutoUseHolders(Player p)
	{
		p.getAutoUseSettings().getAutoActions().clear();
		p.getAutoUseSettings().getAutoBuffs().clear();
		p.getAutoUseSettings().getAutoSkills().clear();
		p.getAutoUseSettings().getAutoSupplyItems().clear();
	}

/**
 * RESTORE GUARD: vacia el estado de instancia de P_old tras el detach del handoff, clonando
 * AdminSwapTest:171-184 (mismo orden: setInstanceId(0) -> set(RESTORE_LOCATION) -> removePlayer).
 * NO hace teleport, NO saca de la party, NO apaga autoplay: el jugador sigue junto a su party en
 * la World 0. Sin esto, un P_old que queda en instanceId > 0 reapareceria tras el restore
 * "on pero invisible" (World + party + autoplay, region invalida). Nombre y firma propios de este POC.
 */
static void drainInstanceState(Player oldPlayer, String tag)
{
	if (!GeneralConfig.RESTORE_PLAYER_INSTANCE && (oldPlayer.getInstanceId() > 0))
	{
		Instance w = InstanceManager.getInstance().getInstance(oldPlayer.getInstanceId());
		if (w != null)
		{
			oldPlayer.setInstanceId(0);
			Location loc = w.getExitLoc();
			if (loc == null)
			{
				loc = MapRegionData.getInstance().getTeleToLocation(oldPlayer, TeleportWhereType.TOWN);
			}
			oldPlayer.getVariables().set(PlayerVariables.RESTORE_LOCATION, loc.getX() + ";" + loc.getY() + ";" + loc.getZ());
			w.removePlayer(oldPlayer.getObjectId());
			LOGGER.info(tag + "RESTORE GUARD: instancia vaciada para " + oldPlayer.getName() + ".");
		}
	}
}

/** Ticks del resync de party: 100 x 100 ms = 10 s max. Condicion real (IN_GAME), no delay fijo. */
private static final int PARTY_RESYNC_MAX_ATTEMPTS = 100;
private static final long PARTY_RESYNC_INTERVAL_MS = 100L;

/**
 * Resync UNICO de la ventana de Party tras el handoff (SERVER PARTY = OK,
 * CLIENT PARTY = sin ventana): el nativo solo emite PartySmallWindowAll al
 * invitar (Party.addPartyMember:297) y el handoff es el unico flujo que entra
 * al mundo ya en party. Reutiliza el mecanismo nativo
 * Party.broadcastToPartyMembersNewLeader() (Party.java:244-258 verificado:
 * NO cambia lider, NO add/remove miembros). Solo se emite con estado real
 * IN_GAME y target re-registrado en World (gates anti-GPF). Sin timers
 * permanentes: la cadena termina al primer exito, aborto o 10 s.
 */
static void schedulePartyResync(Player target, String tag, List<Player> pausedBots)
{
	ThreadPool.schedule(() -> attemptPartyResync(target, 0, tag, pausedBots), PARTY_RESYNC_INTERVAL_MS);
}

static void attemptPartyResync(Player target, int attempt, String tag, List<Player> pausedBots)
{
	GameClient client = target.getClient();
	if ((client == null) || (client.getPlayer() != target))
	{
		resumePartyBots(pausedBots, tag);
		LOGGER.info(tag + "PARTY resync cancelado: el cliente ya no gobierna al target.");
		return;
	}
	ConnectionState state = client.getConnectionState();
	if ((state == ConnectionState.DISCONNECTED) || (state == ConnectionState.CLOSING))
	{
		resumePartyBots(pausedBots, tag);
		LOGGER.info(tag + "PARTY resync cancelado: conexion terminada (" + state + ").");
		return;
	}
	if ((state != ConnectionState.IN_GAME) || (World.getPlayer(target.getObjectId()) != target))
	{
		if (attempt >= PARTY_RESYNC_MAX_ATTEMPTS)
		{
			resumePartyBots(pausedBots, tag);
			LOGGER.warning(tag + "PARTY resync NO enviado: sin IN_GAME+World tras " + (PARTY_RESYNC_MAX_ATTEMPTS * PARTY_RESYNC_INTERVAL_MS) + " ms.");
			return;
		}
		ThreadPool.schedule(() -> attemptPartyResync(target, attempt + 1, tag, pausedBots), PARTY_RESYNC_INTERVAL_MS);
		return;
	}
	Party party = target.getParty();
	if ((party == null) || !party.getMembers().contains(target) || !target.isOnline())
	{
		resumePartyBots(pausedBots, tag);
		LOGGER.warning(tag + "PARTY resync NO enviado: party=" + (party != null) + " online=" + target.isOnline());
		return;
	}
	party.broadcastToPartyMembersNewLeader();
	LOGGER.info(tag + "PARTY resync ENVIADO a " + party.getMembers().size() + " miembros (IN_GAME, una sola vez).");
	resumePartyBots(pausedBots, tag);
}


/**
 * GPF ExRotation: pausa AutoPlay/AutoUse de los miembros de la party distintos del target,
 * que el gate de AutoPlayTaskManager no cubre (son clientless). Orden seguro, igual que
 * teardownBot: setOfflinePlay(false) ANTES de stopAutoPlay para que setAutoPlaying(false) no
 * borre la fila character_offline_play del bot. Se reactivan en el resync tras IN_GAME.
 */
static List<Player> pausePartyBots(Party party, Player target, String tag)
{
	final List<Player> paused = new ArrayList<>();
	if (party == null)
	{
		return paused;
	}
	for (Player member : party.getMembers())
	{
		if ((member == null) || (member == target) || !member.isAutoPlaying())
		{
			continue;
		}
		member.setOfflinePlay(false);
		AutoUseTaskManager.getInstance().stopAutoUseTask(member);
		AutoPlayTaskManager.getInstance().stopAutoPlay(member);
		paused.add(member);
	}
	if (!paused.isEmpty())
	{
		LOGGER.info(tag + "GPF-guard: pausados " + paused.size() + " bots de la party durante la transicion.");
	}
	return paused;
}

/**
 * Reactiva los bots pausados por pausePartyBots tras IN_GAME (o al abortar en cualquier
 * rama). Vuelve a poner offlinePlay (necesario para sobrevivir al gate) y rearma AutoPlay/AutoUse.
 */
static void resumePartyBots(List<Player> paused, String tag)
{
	if (paused == null)
	{
		return;
	}
	int n = 0;
	for (Player member : paused)
	{
		if ((member == null) || (member.getClient() != null))
		{
			continue;
		}
		member.setOfflinePlay(true);
		AutoPlayTaskManager.getInstance().startAutoPlay(member);
		AutoUseTaskManager.getInstance().startAutoUseTask(member);
		n++;
	}
	LOGGER.info(tag + "GPF-guard: reactivados " + n + " bots tras la transicion.");
}



}
