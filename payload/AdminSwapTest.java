package handlers.chat.commands.admin;

import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.custom.FactionSystemConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.sql.OfflineTraderTable;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.SecondaryAuthData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.entity.instancezone.Instance;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.PunishmentManager;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventDispatcher;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerSelect;
import org.l2jmobius.gameserver.mechanics.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.mechanics.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.mechanics.punishment.PunishmentAffect;
import org.l2jmobius.gameserver.mechanics.punishment.PunishmentType;
import org.l2jmobius.gameserver.mechanics.sevensigns.SevenSignsFestival;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.ConnectionState;
import org.l2jmobius.gameserver.network.Disconnection;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.CharacterInfoHolder;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.CharSelected;
import org.l2jmobius.gameserver.network.serverpackets.CharSelectionInfo;
import org.l2jmobius.gameserver.network.serverpackets.LeaveWorld;
import org.l2jmobius.gameserver.network.serverpackets.RestartResponse;
import org.l2jmobius.gameserver.network.serverpackets.SSQInfo;
import org.l2jmobius.gameserver.network.serverpackets.ServerClose;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;

public class AdminSwapTest implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger("SWAPTEST");
	private static final ConcurrentMap<String, Long> BUSY = new ConcurrentHashMap<>();
	private static final String[] COMMANDS =
	{
		"admin_swaptest"
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
			activeChar.sendMessage("[SWAPTEST] ABORT: sin GameClient.");
			return false;
		}
		StringTokenizer st = new StringTokenizer(command);
		st.nextToken();
		if (!st.hasMoreTokens())
		{
			activeChar.sendMessage("[SWAPTEST] Uso: //swaptest list | //swaptest <slot>");
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
			activeChar.sendMessage("[SWAPTEST] ABORT: slot no numerico.");
			return false;
		}
		runSwap(activeChar, slot);
		return true;
	}

	static void runSwap(Player gm, int absSlot)
	{
		String tag = "[SWAPTEST] ";
		GameClient client = gm.getClient();
		CharacterInfoHolder pre = client.getCharSelection(absSlot);
		if (pre == null)
		{
			gm.sendMessage(tag + "ABORT: slot vacio.");
			return;
		}
		int targetId = pre.getObjectId();
		LOGGER.info(tag + "START current=" + gm.getName() + " target=" + pre.getName() + " slot=" + absSlot);
		// PASO 1 del transfer: captura ANTES de cualquier mutacion (storeAndDelete/leaveParty).
		final AdminBotManager.OrchestratorState orchState = AdminBotManager.captureOrchestratorState(gm);
		if (targetId == gm.getObjectId())
		{
			gm.sendMessage(tag + "ABORT: es el actual.");
			return;
		}
		String account = client.getAccountName();
		if (BUSY.putIfAbsent(account, System.currentTimeMillis()) != null)
		{
			gm.sendMessage(tag + "ABORT: swap en progreso.");
			return;
		}
		boolean locked = false;
		try
		{
			Player dw = World.getPlayer(targetId);
			if (dw != null)
			{
				gm.sendMessage(tag + "ABORT: destino en mundo. Haz .bot off primero.");
				LOGGER.info(tag + "ABORT target-in-world oid=" + targetId);
				return;
			}
			if (!restartGuards(gm, tag))
			{
				return;
			}
			if (!preGuards(client, pre, gm, tag))
			{
				return;
			}
			locked = client.getPlayerLock().tryLock();
			if (!locked)
			{
				gm.sendMessage(tag + "ABORT: playerLock ocupado.");
				return;
			}
			LOGGER.info(tag + "PlayerLock acquired");
			if (gm.getParty() != null)
			{
				gm.leaveParty();
			}
			gm.removeFromBossZone();
			if (OlympiadManager.getInstance().isRegistered(gm))
			{
				OlympiadManager.getInstance().unRegisterNoble(gm);
			}
			if (!GeneralConfig.RESTORE_PLAYER_INSTANCE && (gm.getInstanceId() > 0))
			{
				Instance w = InstanceManager.getInstance().getInstance(gm.getInstanceId());
				if (w != null)
				{
					gm.setInstanceId(0);
					Location loc = w.getExitLoc();
					if (loc == null)
					{
						loc = MapRegionData.getInstance().getTeleToLocation(gm, TeleportWhereType.TOWN);
					}
					gm.getVariables().set(PlayerVariables.RESTORE_LOCATION, loc.getX() + ";" + loc.getY() + ";" + loc.getZ());
					w.removePlayer(gm.getObjectId());
				}
			}
			if (OfflineTraderTable.getInstance().enteredOfflineMode(gm))
			{
				gm.sendMessage(tag + "ABORT: offline trade.");
				return;
			}
			Disconnection.of(client, gm).storeAndDelete();
			LOGGER.info(tag + "Current disconnected");
			client.setConnectionState(ConnectionState.AUTHENTICATED);
			client.sendPacket(RestartResponse.valueOf(true));
			CharSelectionInfo csi = new CharSelectionInfo(client.getAccountName(), client.getSessionId().playOkID1);
			client.sendPacket(csi);
			client.setCharSelection(csi.getCharInfo());
			LOGGER.info(tag + "CharSelectionInfo refreshed n=" + csi.getCharInfo().size());
			int fresh = -1;
			List<CharacterInfoHolder> list = csi.getCharInfo();
			for (int i = 0; i < list.size(); i++)
			{
				if (list.get(i).getObjectId() == targetId)
				{
					fresh = i;
					break;
				}
			}
			if (fresh < 0)
			{
				gm.sendMessage(tag + "ABORT: destino fuera de lista fresca.");
				return;
			}
			LOGGER.info(tag + "freshSlot=" + fresh);
			if (client.getPlayer() != null)
			{
				LOGGER.info(tag + "ABORT getPlayer!=null");
				return;
			}
			doSelect(client, fresh, targetId, tag);
			// PASO 2 del transfer: el nuevo controlador hereda el estado de sesion del grupo.
			// doSelect puede abortar (load null / veto / ban) dejando el client sin Player: en
			// ese caso no hay controlador nuevo y no se transfiere nada.
			if (client.getPlayer() != null)
			{
				AdminBotManager.transferOrchestratorState(orchState, client.getPlayer());
			}
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

	static boolean restartGuards(Player gm, String tag)
	{
		if ((gm.getActiveEnchantItemId() != Player.ID_NONE) || (gm.getActiveEnchantAttrItemId() != Player.ID_NONE))
		{
			gm.sendMessage(tag + "ABORT: enchant.");
			return false;
		}
		if (gm.isChangingClass() || gm.isInStoreMode())
		{
			gm.sendMessage(tag + "ABORT: clase/tienda.");
			return false;
		}
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(gm) && !(gm.isGM() && GeneralConfig.GM_RESTART_FIGHTING))
		{
			gm.sendPacket(SystemMessageId.YOU_CANNOT_RESTART_WHILE_IN_COMBAT);
			gm.sendMessage(tag + "ABORT: combate.");
			return false;
		}
		if (gm.isFestivalParticipant() && SevenSignsFestival.getInstance().isFestivalInitialized())
		{
			gm.sendMessage(tag + "ABORT: festival.");
			return false;
		}
		if (!gm.canLogout())
		{
			gm.sendPacket(ActionFailed.STATIC_PACKET);
			gm.sendMessage(tag + "ABORT: canLogout=false.");
			return false;
		}
		if (gm.isInOlympiadMode() || OlympiadManager.getInstance().isRegistered(gm) || gm.isRegisteredOnEvent() || gm.isJailed() || gm.isTeleporting() || gm.inObserverMode())
		{
			gm.sendMessage(tag + "ABORT: oly/evento/jail/teleport/observer.");
			return false;
		}
		LOGGER.info(tag + "Guards PASS");
		return true;
	}

	static boolean preGuards(GameClient client, CharacterInfoHolder info, Player gm, String tag)
	{
		if (SecondaryAuthData.getInstance().isEnabled() && !client.getSecondaryAuth().isAuthed())
		{
			gm.sendMessage(tag + "ABORT: SecondaryAuth.");
			return false;
		}
		if (PunishmentManager.getInstance().hasPunishment(info.getObjectId(), PunishmentAffect.CHARACTER, PunishmentType.BAN) || PunishmentManager.getInstance().hasPunishment(client.getAccountName(), PunishmentAffect.ACCOUNT, PunishmentType.BAN) || PunishmentManager.getInstance().hasPunishment(client.getIp(), PunishmentAffect.IP, PunishmentType.BAN) || (info.getAccessLevel() < 0))
		{
			gm.sendMessage(tag + "ABORT: ban.");
			return false;
		}
		// Dualbox: el swap reusa el mismo GameClient, no suma conexion nueva. Sin check aqui.
		if (FactionSystemConfig.FACTION_SYSTEM_ENABLED && FactionSystemConfig.FACTION_BALANCE_ONLINE_PLAYERS)
		{
			if (info.isGood() && (World.getAllGoodPlayers().size() >= (World.getAllEvilPlayers().size() + FactionSystemConfig.FACTION_BALANCE_PLAYER_EXCEED_LIMIT)))
			{
				gm.sendMessage(tag + "ABORT: faccion.");
				return false;
			}
			if (info.isEvil() && (World.getAllEvilPlayers().size() >= (World.getAllGoodPlayers().size() + FactionSystemConfig.FACTION_BALANCE_PLAYER_EXCEED_LIMIT)))
			{
				gm.sendMessage(tag + "ABORT: faccion.");
				return false;
			}
		}
		return true;
	}

	static void doSelect(GameClient client, int fresh, int targetId, String tag)
	{
		CharacterInfoHolder info = client.getCharSelection(fresh);
		if ((info == null) || (info.getObjectId() != targetId))
		{
			LOGGER.info(tag + "ABORT info mismatch");
			return;
		}
		if (PunishmentManager.getInstance().hasPunishment(info.getObjectId(), PunishmentAffect.CHARACTER, PunishmentType.BAN) || PunishmentManager.getInstance().hasPunishment(client.getAccountName(), PunishmentAffect.ACCOUNT, PunishmentType.BAN) || PunishmentManager.getInstance().hasPunishment(client.getIp(), PunishmentAffect.IP, PunishmentType.BAN) || (info.getAccessLevel() < 0))
		{
			client.close(ServerClose.STATIC_PACKET);
			return;
		}
		Player cha = client.load(fresh);
		if (cha == null)
		{
			LOGGER.info(tag + "load()=null ABORT sin reintento");
			return;
		}
		LOGGER.info(tag + "load() OK " + cha.getName());
		CharInfoTable.getInstance().addName(cha);
		PlayerVariables vars = cha.getVariables();
		String restore = vars.getString(PlayerVariables.RESTORE_LOCATION, "");
		if (!restore.isEmpty())
		{
			String[] s = restore.split(";");
			cha.setXYZ(Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2]));
			vars.remove(PlayerVariables.RESTORE_LOCATION);
		}
		cha.setClient(client);
		client.setPlayer(cha);
		cha.setOnlineStatus(true, true);
		if (EventDispatcher.getInstance().hasListener(EventType.ON_PLAYER_SELECT, Containers.Players()))
		{
			TerminateReturn t = EventDispatcher.getInstance().notifyEvent(new OnPlayerSelect(cha, cha.getObjectId(), cha.getName(), client), Containers.Players(), TerminateReturn.class);
			if ((t != null) && t.terminate())
			{
				Disconnection.of(cha).storeAndDeleteWith(LeaveWorld.STATIC_PACKET);
				LOGGER.info(tag + "ABORT veto ON_PLAYER_SELECT");
				return;
			}
		}
		client.sendPacket(new SSQInfo());
		client.setConnectionState(ConnectionState.ENTERING);
		client.sendPacket(new CharSelected(cha, client.getSessionId().playOkID1));
		LOGGER.info(tag + "CharSelected enviado - WAITING FOR CLIENT ENTERWORLD");
	}
}
