/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package handlers.chat.commands.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.StringUtil;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.OptionData;
import org.l2jmobius.gameserver.data.xml.PetSkillData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.CrystalType;
import org.l2jmobius.gameserver.mechanics.options.OptionSkillHolder;
import org.l2jmobius.gameserver.mechanics.options.Options;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.holders.CharacterInfoHolder;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoPotionTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;
import org.l2jmobius.gameserver.util.HtmlUtil;

import handlers.custom.bot.AutoPlayProfileBuilder;
import handlers.custom.bot.BotFormationMode;

public class AdminBotManager
{
	private static final Logger LOGGER = Logger.getLogger(AdminBotManager.class.getName());
	
	/**
	 * Logical bot slots exposed by the Bot Manager: {@code 2..9} are Bot Manager slot numbers, not absolute
	 * account selection indexes. Logical slot 2 is the first managed bot, slot 9 the eighth.
	 */
	private static final int FIRST_BOT_SLOT = 2;
	private static final int LAST_BOT_SLOT = 9;
	/** Maximum number of managed bots: one controller plus up to eight bots. */
	private static final int BOT_SLOT_COUNT = LAST_BOT_SLOT - FIRST_BOT_SLOT + 1;
	private static final int FORMATION_RADIUS = 100;
	/** Root token of the Bot Manager window buttons, resolved by {@code BypassHandler}. Not an admin command. */
	public static final String BYPASS_ROOT = "bot";
	/** Trailing token appended by the Bot Manager window buttons so only in-window clicks rebuild the panel. */
	private static final String UI_MARKER = "ui";
	/**
	 * Argument of the window button that switches the native AutoPlay of the player who opened the panel.
	 * It is a presentation argument of this manager only: it never touches a bot, a slot or the registry.
	 */
	private static final String SELF_AUTOPLAY_ARGUMENT = "autoplay";
	/**
	 * Argument of the window button that drives the native AutoPlay of every controlled player at once.<br>
	 * <b>Why a dedicated token.</b> {@link #SELF_AUTOPLAY_ARGUMENT} is already {@code autoplay} and it is matched
	 * first, so reusing that literal made the party branch unreachable: the connected player was toggled instead.
	 * The two behaviours are unrelated, so they must never share a root token again.
	 */
	private static final String PARTY_AUTOPLAY_ARGUMENT = "partyautoplay";
	/**
	 * Argument of the window button that opens the native Select Skills of one character. The optional trailing
	 * tokens are the target charId, then the page and the toggled skill identifier, all emitted by the window.
	 */
	private static final String SKILLS_ARGUMENT = "skills";
	/**
	 * Argument of the window button that toggles target mirroring for the player who opened the panel.
	 * It is per orchestrator: it never touches a bot, a slot, the registry or the native AutoPlay config.
	 */
	private static final String ASSIST_ARGUMENT = "assist";
	private static final String FORMATION_ARGUMENT = "formation";
	/**
	 * Argument of the window button that opens the native AutoPlay (.play) window of one character, mirrored
	 * onto the viewer. The optional trailing token is the target charId, emitted by the window.
	 */
	private static final String PLAY_ARGUMENT = "play";
	/**
	 * Argument of one action of that mirrored .play window. The trailing tokens are the target charId, the
	 * native action and its optional value, all emitted by the mirrored window.
	 */
	private static final String PLAYACT_ARGUMENT = "playact";

	/** Argumento [Swap]: cambio oficial de personaje. Solo con owner sin Party. */
	private static final String SWAP_ARGUMENT = "swap";
	/** Argumento [Handoff]: transferencia del cliente a otro vivo de la misma Party. Solo con owner en Party. */
	private static final String HANDOFF_ARGUMENT = "handoff";
	/** Page size of the skill window, identical to the native AutoPlay page limit. */
	private static final int SKILL_PAGE_LIMIT = 7;
	/**
	 * The native Auto Attack action identifier of the .play window. Declared Integer, never int, so
	 * {@code remove()} drops the value and never a list index.
	 */
	private static final Integer AUTO_ATTACK_ACTION = 2;
	/** Persistent key of the orchestrator target mirroring choice, stored in {@code character_variables}. */
	private static final String BOT_ASSIST_VARIABLE = "BOT_ASSIST";
	/**
	 * Persistent key of the corpse retention policy (the .play Spoil switch), stored in {@code character_variables}.
	 * True (the default) keeps the pre-existing behaviour: the corpse stays as target. False asks the manager to
	 * drop dead targets right away on every pass. It is a pure policy flag: no skill is ever read or written,
	 * so the switch belongs to every character whether it knows Spoil or not.
	 */
	private static final String BOT_SPOIL_VARIABLE = "BOT_SPOIL";
	/**
	 * Persistent key of the party fight back policy of .play (the Fight Back switch).
	 * False (the default) keeps the stock target acquisition; true tells the native AutoPlay search to
	 * target first the monster hitting a party member, so the whole group answers back at once.
	 */
	private static final String BOT_FIGHTBACK_VARIABLE = "BOT_FIGHTBACK";
	/**
	 * Persistent key of the manual target policy of .play (the Target Search switch).
	 * True (the default) keeps the native acquisition of today; false skips only the target assignment,
	 * so the character walks freely and the skills keep casting on the target the player picks by hand.
	 */
	private static final String BOT_TSEARCH_VARIABLE = "BOT_TSEARCH";
	
	/** Tracking only. {@link World#getPlayer(int)} stays the source of truth for the real state. */
	private static final Set<Integer> BOT_REGISTRY = ConcurrentHashMap.newKeySet();
	private static final Map<Integer, Long> BOT_SESSIONS = new ConcurrentHashMap<>();
	
	/**
	 * Owner of every tracked bot: orchestrator object identifier to the set of bot object identifiers it owns.<br>
	 * <b>Why this exists.</b> {@link #BOT_REGISTRY} and {@link #BOT_SESSIONS} are flat and account-less, so a single
	 * global iteration would let the party of one account decide for the bots of another. Indexing by owner is what
	 * makes several accounts run their own Bot Manager at the same time without ever crossing: account A only ever
	 * reads and writes the bots of account A.
	 */
	private static final Map<Integer, Set<Integer>> BOT_OWNERS = new ConcurrentHashMap<>();
	
	/**
	 * Runtime state, per connected orchestrator object identifier: the bots mirror the current party leader target.
	 * Empty means the manager imposes nothing. The durable copy lives in {@link #BOT_ASSIST_VARIABLE}.
	 */
	private static final Set<Integer> ASSIST_ORCHESTRATORS = ConcurrentHashMap.newKeySet();
	/**
	 * Runtime state, per connected orchestrator object identifier: the whole controlled group runs AutoPlay.<br>
	 * <b>Deliberately not persisted.</b> This is a temporary session state, never restored from the database and
	 * never written to {@code character_variables}, so every new orchestrator session starts with it off. It is
	 * the source of truth; the individual {@code isAutoPlaying()} of each bot is only its consequence.
	 */
	private static final Set<Integer> PARTY_AUTOPLAY_ORCHESTRATORS = ConcurrentHashMap.newKeySet();
	/**
	 * Native target mode owned by the player, kept aside while this manager drives the character.<br>
	 * <b>Why.</b> Assist must stay the authority over the target, and the only native lever is the
	 * target mode, which the engine reads on every cycle. The player's own value is remembered here, in memory
	 * only, so it can be handed back untouched when both switches go off. Nothing is written to the database and
	 * a character that already used the forced value is restored to that very same value.
	 */
	private static final Map<Integer, Integer> OWN_TARGET_MODES = new ConcurrentHashMap<>();
	/** The one native target mode that means "monsters only": a monster, not a raid, and attackable. */
	private static final int MONSTER_ONLY_TARGET_MODE = 1;
	/**
	 * Soulshot No Grade for Beginners, item 5789. It carries no crystal grade, so it is not reachable through the
	 * grade tables of {@link #getSoulShotId} and is registered on its own.
	 */
	private static final int SOULSHOT_NO_GRADE_FOR_BEGINNERS = 5789;
	/** Spiritshot No Grade for Beginners, item 5790. Same reason: it carries no crystal grade. */
	private static final int SPIRITSHOT_NO_GRADE_FOR_BEGINNERS = 5790;

	/**
	 * Period of the leader target synchronisation, in milliseconds. It matches the native AutoPlay task period
	 * ({@code AutoPlayTaskManager#TASK_DELAY}) so the release of a bot target can never lag one whole AutoPlay
	 * cycle behind the leader decision.
	 */
	private static final long TARGET_SYNC_PERIOD = 700L;

	/** Guards the single scheduling of {@link #TARGET_SYNC_TASK}, since {@link #registerBot(Player)} runs per bot. */
	private static final AtomicBoolean TARGET_SYNC_STARTED = new AtomicBoolean();
	
	/**
	 * Single entry point of the Bot Manager for every non-administrative surface.<br>
	 * This is the method {@code BotCommand} (voiced {@code .bot}) and {@code BotManagerBypass}
	 * (window buttons) both delegate to.<br>
	 * <br>
	 * The class intentionally does not register any {@code admin_} command, so no Bot Manager
	 * operation can ever cross {@code AdminCommandHandler}, consult {@code AdminData} or raise an
	 * administrative confirmation dialog.<br>
	 * <br>
	 * No authorization on access level or GM status happens here: the only gate is the pre-existing
	 * party-leader gameplay rule, which lives in {@link #activateSlotAt}. A player without a party
	 * satisfies it automatically, because the first activation creates the party and the player
	 * becomes its leader.
	 * @param owner the player issuing the command; every connected player may use the Bot Manager
	 * @param params the argument, with no {@code bot} prefix and no trailing {@code ui} token
	 * @param fromUi true when the call originated from a window button, so the panel is rebuilt after the operation
	 * @return true when the requested operation produced its effect
	 */
	public static boolean dispatchCommand(Player owner, String params, boolean fromUi)
	{
		if (owner == null)
		{
			return false;
		}
		
		final String[] parts = ((params == null) ? "" : params).trim().split(" ");
		if ((parts.length == 0) || parts[0].isEmpty())
		{
			// Bare ".bot" / "bot": open the window without rebuilding it afterwards.
			return showBotManagerWindow(owner);
		}
		
		final String argument = parts[0];
		if ("off".equalsIgnoreCase(argument))
		{
			final boolean result = deactivateAllBots(owner);
			return fromUi ? refreshAfterUi(owner, result) : result;
		}
		
		if ("status".equalsIgnoreCase(argument))
		{
			return statusBots(owner);
		}
		
		if (SELF_AUTOPLAY_ARGUMENT.equalsIgnoreCase(argument))
		{
			toggleSelfAutoPlay(owner);
			return fromUi ? refreshAfterUi(owner, true) : true;
		}
		
		if (ASSIST_ARGUMENT.equalsIgnoreCase(argument))
		{
			ensureOrchestratorRegistration(owner);
			restoreOrchestratorBehaviour(owner);
			final boolean assistOn = toggleOrchestratorFlag(ASSIST_ORCHESTRATORS, BOT_ASSIST_VARIABLE, owner);
			// The native AssistLeader branch is the one that actually mirrors the leader target: it decides that
			// inside its own thread, so nothing can race with it. The flag is a plain public static read on every
			// cycle, so the switch applies on the next pass with no restart. The branch only ever runs for a
			// player that is in a party, which keeps everyone outside this account untouched.
			AutoPlayConfig.ENABLE_AUTO_ASSIST = assistOn;
			return fromUi ? refreshAfterUi(owner, true) : true;
		}
		
		if (FORMATION_ARGUMENT.equalsIgnoreCase(argument))
		{
			ensureOrchestratorRegistration(owner);
			restoreOrchestratorBehaviour(owner);
			// One button, one state: pressing it keeps the ring closed around the leader from now on, so the
			// bots walk with the group instead of being ordered once and then drifting apart. Pressing it again
			// hands the movement back to the plain one shot Formation order.
			final boolean on = BotFormationMode.toggle(owner);
			if (on)
			{
				formationBots(owner);
			}
			return fromUi ? refreshAfterUi(owner, true) : true;
		}
		
		if (SKILLS_ARGUMENT.equalsIgnoreCase(argument))
		{
			// The skill window replaces the panel, so the panel is deliberately not rebuilt here.
			return openSkillsWindow(owner, parts);
		}
		
		if (PLAY_ARGUMENT.equalsIgnoreCase(argument))
		{
			// The mirrored .play window replaces the panel, so the panel is deliberately not rebuilt here.
			return openPlayWindow(owner, parts);
		}
		
		if (PLAYACT_ARGUMENT.equalsIgnoreCase(argument))
		{
			// The action is applied first and the same mirrored window is rebuilt afterwards, so every
			// checkbox always shows the state the server just stored.
			applyPlayAction(owner, parts);
			return openPlayWindow(owner, parts);
		}

		if (PARTY_AUTOPLAY_ARGUMENT.equalsIgnoreCase(argument))
		{
			final boolean result = togglePartyAutoPlay(owner);
			return fromUi ? refreshAfterUi(owner, result) : result;
		}
		
		if ("all".equalsIgnoreCase(argument))
		{
			final boolean result = activateAllSlots(owner);
			return fromUi ? refreshAfterUi(owner, result) : result;
		}

		if (SWAP_ARGUMENT.equalsIgnoreCase(argument))
		{
			final Integer absIndex = resolveActionSlot(owner, parts, "SWAP", true);
			if (absIndex == null)
			{
				return false;
			}
			AdminSwapTest.runSwap(owner, absIndex);
			return fromUi ? refreshAfterUi(owner, true) : true;
		}

		if (HANDOFF_ARGUMENT.equalsIgnoreCase(argument))
		{
			final Integer absIndex = resolveActionSlot(owner, parts, "HANDOFF", false);
			if (absIndex == null)
			{
				return false;
			}
			AdminHandoffTest.runHandoff(owner, absIndex);
			return fromUi ? refreshAfterUi(owner, true) : true;
		}

		final int slot;
		try
		{
			slot = Integer.parseInt(argument);
		}
		catch (NumberFormatException e)
		{
			sendUsage(owner);
			return false;
		}
		
		if ((slot < FIRST_BOT_SLOT) || (slot > LAST_BOT_SLOT))
		{
			sendUsage(owner);
			return false;
		}
		
		final boolean result = toggleSlot(owner, slot);
		return fromUi ? refreshAfterUi(owner, result) : result;
	}
	
	/**
	 * Rebuilds the Bot Manager window after an in-window click, keeping the panel in sync with the real server state.<br>
	 * The HTML is always rebuilt from scratch out of {@link #BOT_REGISTRY} and {@link World}, never from artificial UI state.
	 * @param gm the GM leader
	 * @param operationResult the result of the operation that was executed
	 * @return the operation result, unchanged
	 */
	private static boolean refreshAfterUi(Player gm, boolean operationResult)
	{
		showBotManagerWindow(gm);
		return operationResult;
	}
	
	/**
 * Resuelve y valida el slot logico de la accion [Swap]/[Handoff] de una fila.
 * Conversion obligatoria: slot logico 2..9 -> indice absoluto 0..8 via
 * resolveBotAccountIndex (nunca pasar el logico a getCharSelection).
 * @param requireNoParty true en SWAP (owner sin Party), false en HANDOFF (owner con Party)
 * @return indice absoluto de cuenta o null; el mensaje de rechazo ya fue enviado
 */
private static Integer resolveActionSlot(Player owner, String[] parts, String actionName, boolean requireNoParty)
{
if (requireNoParty && (owner.getParty() != null))
{
owner.sendMessage("[BotManager] " + actionName + " no disponible: estas en Party (usa HANDOFF).");
return null;
}
if ((!requireNoParty) && (owner.getParty() == null))
{
owner.sendMessage("[BotManager] " + actionName + " no disponible: no estas en Party (usa SWAP).");
return null;
}
if (parts.length < 2)
{
owner.sendMessage("[BotManager] Uso: .bot " + actionName.toLowerCase() + " <slot " + FIRST_BOT_SLOT + ".." + LAST_BOT_SLOT + ">");
return null;
}
final int slot;
try
{
slot = Integer.parseInt(parts[1]);
}
catch (NumberFormatException e)
{
owner.sendMessage("[BotManager] " + actionName + ": slot no numerico.");
return null;
}
if ((slot < FIRST_BOT_SLOT) || (slot > LAST_BOT_SLOT))
{
owner.sendMessage("[BotManager] " + actionName + ": slot fuera de rango (" + FIRST_BOT_SLOT + ".." + LAST_BOT_SLOT + ").");
return null;
}
final int absIndex = resolveBotAccountIndex(owner, slot);
if (absIndex < 0)
{
owner.sendMessage("[BotManager] " + actionName + ": ese slot no tiene personaje.");
return null;
}
return absIndex;
}

private static void sendUsage(Player gm)
	{
		gm.sendMessage("[BotManager] Uso: .bot (abre la ventana) | .bot " + FIRST_BOT_SLOT + ".." + LAST_BOT_SLOT + " | .bot all | .bot formation | .bot off | .bot status");
	}
	
	/**
	 * Renders the minimalist Bot Manager window using the native Mobius admin HTML pattern
	 * ({@link NpcHtmlMessage} + {@code setHtml} + {@link Player#sendPacket}), the same mechanism already
	 * used by the admin menu handlers of this runtime.<br>
	 * This class is a pure presentation layer: every button issues the very same {@code bot} bypass subcommand
	 * that the chat commands use, so no activation, deactivation or formation logic is duplicated here.<br>
	 * The panel shows the connected character as name, level and class, then one row per bot slot with only
	 * name, level, class and the real {@code [ON]}/{@code [OFF]} state, then a single dynamic {@code ALL}/{@code OFF}
	 * switch and the formation button. It renders no header title and no leader label: no slot number, objectId,
	 * access level or any other technical data is displayed, and empty slots become plain non-clickable text.<br>
	 * The window also declares an explicit {@code <title>} so the client stops falling back to an unrelated
	 * cached name in the dialog title bar.
	 * @param gm the GM leader
	 * @return always true, the window was delivered to the client
	 */
	private static boolean showBotManagerWindow(Player gm)
	{
		// The periodic task only visits registered orchestrators, so opening the panel guarantees the entry exists.
		ensureOrchestratorRegistration(gm);
		final StringBuilder sb = new StringBuilder(1024);
		sb.append("<html><title>Bots</title><body><center>");
		
		// Connected character AutoPlay state: the label and the action are the real AutoPlay
		// state of the player, the very same start/stop pair the native .play command runs.
		final boolean selfAutoPlaying = gm.isAutoPlaying();
		final String selfAutoPlayLabel = selfAutoPlaying ? "STOP" : "START";
		// LEADER 280: info 140 + botones 140 (START 115 + fila Skills 55 + Config 55 + gaps).
		sb.append("<table width=280 bgcolor=\"111111\"><tr>");
		sb.append("<td width=140 align=center>");
		sb.append("<font color=\"00FF00\">").append(gm.getName()).append("</font><br1>");
		sb.append("<font color=\"B0B0B0\">Lv. ").append(gm.getLevel()).append(' ').append(getClassName(gm.getPlayerClass().getId())).append("</font>");
		sb.append("</td><td width=140 align=center>");
		sb.append("<button value=\"").append(selfAutoPlayLabel).append("\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(SELF_AUTOPLAY_ARGUMENT).append(' ').append(UI_MARKER);
		sb.append("\" width=115 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("<br1>");
		// Select Skills of the connected character. It uses the very same window as every other character, only
		// with its own charId, so the skill page never depends on which character is the orchestrator. The full
		// AutoPlay settings are reached through the mirrored .play button right next to it.
		// Skills y Config con texto en la misma linea (55+55=110 dentro de 115).
		sb.append("<table width=115 cellpadding=0 cellspacing=0><tr>");
		sb.append("<td width=55><button value=\"Skills\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(SKILLS_ARGUMENT).append(' ').append(gm.getObjectId());
		sb.append("\" width=55 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
		sb.append("<td width=55 align=right>");
		// Config: con texto, misma textura Button_DF de siempre.
		sb.append("<button value=\"Config\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(PLAY_ARGUMENT).append(' ').append(gm.getObjectId());
		sb.append("\" width=55 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td></tr></table>");
		sb.append("</td></tr></table>");
		sb.append("<table width=280><tr><td align=center><img src=\"L2UI.SquareGray\" width=260 height=1></td></tr><tr><td height=4></td></tr></table>");

		// PARTY: fila Autofarm/Assist/Formation con punto al costado + boton Group largo abajo.
		final boolean anyBotActive = !BOT_REGISTRY.isEmpty();
		// Party AutoPlay: the label reads the group state itself, never a per player flag, so it stays truthful
		// even with no bot active. The orchestrator keeps its own individual START/STOP above.
		final boolean partyAutoPlaying = PARTY_AUTOPLAY_ORCHESTRATORS.contains(gm.getObjectId());
		restoreOrchestratorBehaviour(gm);
		final boolean assistOn = ASSIST_ORCHESTRATORS.contains(gm.getObjectId());
		final boolean formationOn = BotFormationMode.isOn(gm);
		final String autoDot = partyAutoPlaying ? "00FF00" : "808080";
		final String assistDot = assistOn ? "00FF00" : "808080";
		final String formationDot = formationOn ? "00FF00" : "808080";
		sb.append("<table width=280 cellpadding=0 cellspacing=0><tr>");
		// Cada celda: boton + punto en la MISMA linea via tabla interna (80+15=95 / 75+15=90 / 80+15=95).
		sb.append("<td width=95><table width=95 cellpadding=0 cellspacing=0><tr>");
		sb.append("<td width=80><button value=\"Autofarm\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(PARTY_AUTOPLAY_ARGUMENT).append(' ').append(UI_MARKER);
		sb.append("\" width=80 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
		sb.append("<td width=15 align=left><font color=\"").append(autoDot).append("\">●</font></td></tr></table></td>");
		sb.append("<td width=90><table width=90 cellpadding=0 cellspacing=0><tr>");
		sb.append("<td width=75>");
		// Targeting behaviour, the switch that decides what the bots do with their target. Its label is
		// the real runtime state of this player only.
		sb.append("<button value=\"Assist\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(ASSIST_ARGUMENT).append(' ').append(UI_MARKER);
		sb.append("\" width=75 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
		sb.append("<td width=15 align=left><font color=\"").append(assistDot).append("\">●</font></td></tr></table></td>");
		sb.append("<td width=95><table width=95 cellpadding=0 cellspacing=0><tr>");
		sb.append("<td width=80>");
		// Formation keeps its name: continuous ring around the party leader, not a one shot order.
		sb.append("<button value=\"Formation\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(FORMATION_ARGUMENT).append(' ').append(UI_MARKER);
		sb.append("\" width=80 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
		sb.append("<td width=15 align=left><font color=\"").append(formationDot).append("\">●</font></td></tr></table></td>");
		sb.append("</tr></table>");
		sb.append("<table width=280><tr><td height=8></td></tr></table>");
		sb.append("<table width=280><tr><td align=center>");
		if (anyBotActive)
		{
			sb.append("<button value=\"Dismiss All\" action=\"bypass -h ").append(BYPASS_ROOT).append(" off ").append(UI_MARKER);
			sb.append("\" width=270 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		else
		{
			sb.append("<button value=\"Full Party\" action=\"bypass -h ").append(BYPASS_ROOT).append(" all ").append(UI_MARKER);
			sb.append("\" width=270 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		sb.append("</td></tr></table>");
		sb.append("<table width=280><tr><td align=center><img src=\"L2UI.SquareGray\" width=260 height=1></td></tr><tr><td height=4></td></tr></table>");
		
		// Slots 280 en UNA linea: punto 10 + nivel 14 + nombre+clase 181 + Skills 50 + cuadrado 25.
		// Bot slots: one row per slot, in slot order, with no slot number exposed.
			final boolean swapMode = (gm.getParty() == null);

for (int slot = FIRST_BOT_SLOT; slot <= LAST_BOT_SLOT; slot++)
		{
			final CharacterInfoHolder info = resolveSlot(gm, slot);
			if (info == null)
			{
				sb.append("<table width=280><tr><td align=center><font color=\"555555\">VACIO</font></td></tr></table>");
				continue;
			}
			
			final boolean active = isSlotActive(gm, slot);
			final String statusColor = active ? "00FF00" : "808080";
			sb.append("<table width=280 cellpadding=0 cellspacing=0><tr>");
			sb.append("<td width=10 align=center valign=top><font color=\"").append(statusColor).append("\">●</font></td>");
			sb.append("<td width=14 align=right valign=top><font name=\"hs8\" color=\"B0B0B0\">").append(info.getLevel()).append("</font></td>");
			sb.append("<td width=131 valign=top>&nbsp;<a action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(slot).append(' ').append(UI_MARKER).append("\">").append(info.getName()).append("</a>&nbsp;<font name=\"hs8\" color=\"B0B0B0\">").append(getClassName(info.getClassId()).replace(" ", "&nbsp;")).append("</font></td>");
				sb.append("<td width=50 align=center valign=top>");
					if (swapMode)
					{
						sb.append("<button value=\"Login\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(SWAP_ARGUMENT).append(' ').append(slot).append(' ').append(UI_MARKER).append("\" width=50 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
					}
					else
					{
						sb.append("<button value=\"Login\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(HANDOFF_ARGUMENT).append(' ').append(slot).append(' ').append(UI_MARKER).append("\" width=50 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
					}
					sb.append("</td>");
					if (World.getPlayer(info.getObjectId()) != null)
					{
						sb.append("<td width=75 align=center>");
						sb.append("<button value=\"Config\" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(PLAY_ARGUMENT).append(' ').append(info.getObjectId());
						sb.append("\" width=75 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
						sb.append("</td>");
					}

			else
			{
				sb.append("<td width=75 align=center valign=top><font color=\"555555\">Offline</font></td>");
			}
			sb.append("</tr></table>");
			sb.append("<table width=280><tr><td align=center><img src=\"L2UI.SquareGray\" width=260 height=1></td></tr></table>");
		}
		
		sb.append("</center></body></html>");
		
		final NpcHtmlMessage html = new NpcHtmlMessage();
		html.setHtml(sb.toString());
		gm.sendPacket(html);
		return true;
	}
	
	/**
	 * @param classId the numeric class identifier
	 * @return the localized class name, or an empty string when the class is unknown to the class list data
	 */
	private static String getClassName(int classId)
	{
		final ClassInfoHolder classInfo = ClassListData.getInstance().getClass(classId);
		return (classInfo == null) ? "" : classInfo.getClassName();
	}
	
	/**
	 * Resolves the real account selection index of the currently connected character.<br>
	 * The controller is the connected {@link Player} itself: it is identified by its own objectId, never by a
	 * fixed slot number, name, GM status or access level, so it is found whatever position it occupies in the
	 * account character list.
	 * @param owner the connected player acting as Bot Manager controller
	 * @return the 0 based index inside {@code getCharSelection()}, or {@code -1} when it cannot be determined
	 */
	private static int findControllerIndex(Player owner)
	{
		if ((owner == null) || (owner.getClient() == null))
		{
			return -1;
		}
		
		final int controllerObjectId = owner.getObjectId();
		for (int index = 0; ; index++)
		{
			// getCharSelection returns null as soon as the index leaves the account list, which ends the scan.
			final CharacterInfoHolder info = owner.getClient().getCharSelection(index);
			if (info == null)
			{
				return -1;
			}
			
			if (info.getObjectId() == controllerObjectId)
			{
				return index;
			}
		}
	}
	
	/**
	 * Resolves the real account selection index of a logical Bot Manager bot slot.<br>
	 * Logical slots {@code 2..9} are Bot Manager slot numbers, not account indexes: slot 2 is the first
	 * non-controller character of the account in {@code getCharSelection()} order, slot 3 the second, and so on
	 * up to the eighth. The controller is always skipped, so it can never be managed as a bot, and no character
	 * beyond the eighth is ever reachable because the logical range stops at {@link #LAST_BOT_SLOT}.
	 * @param owner the connected player acting as Bot Manager controller
	 * @param slot the logical 1 based Bot Manager slot
	 * @return the 0 based account index of that bot, or {@code -1} when the slot is invalid or the account has
	 *         no such non-controller character
	 */
	private static int resolveBotAccountIndex(Player owner, int slot)
	{
		if ((owner == null) || (owner.getClient() == null) || (slot < FIRST_BOT_SLOT) || (slot > LAST_BOT_SLOT))
		{
			return -1;
		}
		
		final int controllerIndex = findControllerIndex(owner);
		if (controllerIndex < 0)
		{
			return -1;
		}
		
		final int wantedBot = slot - FIRST_BOT_SLOT;
		int seen = 0;
		for (int index = 0; ; index++)
		{
			final CharacterInfoHolder info = owner.getClient().getCharSelection(index);
			if (info == null)
			{
				return -1;
			}
			
			if (index == controllerIndex)
			{
				continue;
			}
			
			if (seen == wantedBot)
			{
				return index;
			}
			
			seen++;
		}
	}
	
	/**
	 * Resolves the character a logical Bot Manager slot points to.<br>
	 * The account ownership is still enforced by the core: {@code getCharSelection()} only ever exposes the
	 * characters of the connected account. This method only maps a logical slot onto the right account index.
	 * @param owner the connected player acting as Bot Manager controller
	 * @param slot the logical 1 based Bot Manager slot
	 * @return the character info holder or {@code null} when the slot is invalid or empty
	 */
	private static CharacterInfoHolder resolveSlot(Player owner, int slot)
	{
		final int index = resolveBotAccountIndex(owner, slot);
		return (index < 0) ? null : owner.getClient().getCharSelection(index);
	}
	
	/**
	 * @param owner the connected player acting as Bot Manager controller
	 * @param slot the logical 1 based Bot Manager slot
	 * @return the character objectId of the slot or -1 when it cannot be resolved
	 */
	private static int resolveSlotCharId(Player owner, int slot)
	{
		final CharacterInfoHolder info = resolveSlot(owner, slot);
		return (info == null) ? -1 : info.getObjectId();
	}
	
	/**
	 * @param gm the GM running the command
	 * @param slot the 1 based slot
	 * @return true when the slot is a bot tracked by this manager and is still in the world
	 */
	private static boolean isSlotActive(Player gm, int slot)
	{
		final int charId = resolveSlotCharId(gm, slot);
		return (charId > 0) && BOT_REGISTRY.contains(charId) && (World.getPlayer(charId) != null);
	}
	
	private static boolean toggleSlot(Player gm, int slot)
	{
		if (isSlotActive(gm, slot))
		{
			// Death never changes the meaning of the individual toggle: an active bot, alive or dead, is deactivated here. //bot <N> never revives.
			return deactivateSlot(gm, slot);
		}
		
		return activateSlot(gm, slot);
	}
	
	private static boolean activateSlot(Player gm, int slot)
	{
		final int formationIndex = slot - FIRST_BOT_SLOT;
		final Location fixed = computeFormationDestinations(gm.getX(), gm.getY(), gm.getZ(), BOT_SLOT_COUNT).get(formationIndex);
		return activateSlotAt(gm, slot, fixed, true);
	}
	
	/**
	 * Autoridad de composicion de Party para este manager: poder anyadir o quitar personajes de la Party.
	 * Sin party no hay nada que modificar, asi que pasa automaticamente; dentro de la party solo el
	 * lider ACTUAL (releido de la party en cada comando, nunca cacheado) puede cambiar su composicion.
	 * HANDOFF/SWAP no pasan por aqui: no alteran la membresia y los ejecuta el controlador actual
	 * aunque no sea lider. La autoridad se impone aqui, en las operaciones publicas del gestor;
	 * {@code Party.java} no se toca.
	 * @param gm the player issuing the Bot Manager command
	 * @return true when Party composition changes are allowed for this player
	 */
	private static boolean isPartyLeader(Player gm)
	{
		final Party party = gm.getParty();
		return (party == null) || party.isLeader(gm);
	}
	
	/**
	 * Shared activation used by single slot commands and by the formation-aware bulk activation.
	 * @param gm the GM running the command
	 * @param slot the 1 based slot
	 * @param destination precomputed formation destination, used only for fresh spawns
	 * @param announce true to keep the individual activation messages
	 * @return true when the slot is now active
	 */
	private static boolean activateSlotAt(Player gm, int slot, Location destination, boolean announce)
	{
		final CharacterInfoHolder info = resolveSlot(gm, slot);
		if (info == null)
		{
			gm.sendMessage("[BotManager] No hay personaje en el slot " + slot + ".");
			return false;
		}
		
		final int charId = info.getObjectId();
		if (charId == gm.getObjectId())
		{
			gm.sendMessage("[BotManager] El slot " + slot + " corresponde al personaje actual. No hay nada que activar.");
			return false;
		}
		
		if (!isPartyLeader(gm))
		{
			gm.sendMessage("[BotManager] Debes ser el lider de tu Party para activar o desactivar bots.");
			return false;
		}
		
		final Player existing = World.getPlayer(charId);
		if (existing != null)
		{
			if (BOT_REGISTRY.contains(charId))
			{
				gm.sendMessage("[BotManager] El slot " + slot + " (" + existing.getName() + ") ya esta activo.");
				return false;
			}
			
			if (existing.getClient() != null)
			{
				gm.sendMessage("[BotManager] El personaje ya esta en juego.");
				return false;
			}
			
			if (existing.isOfflinePlay())
			{
				return adoptBot(gm, slot, existing);
			}
			
			gm.sendMessage("[BotManager] El personaje del slot " + slot + " ya esta en el mundo y no es un bot. No se modifica.");
			return false;
		}
		
		// Stale registry entry without a world object.
		untrackBot(charId);
		
		Player bot = null;
		try
		{
			bot = Player.load(charId);
			if (bot == null)
			{
				if (announce)
				{
					gm.sendMessage("[BotManager] No se pudo cargar el personaje del slot " + slot + " (objectId " + charId + ").");
				}
				
				return false;
			}
			
			prepareBot(gm, bot, destination);
			registerBot(bot, gm);
			LOGGER.info("[BotManager] " + gm.getName() + " activated slot " + slot + " (" + bot.getName() + ", objectId " + charId + ").");
			if (announce)
			{
				gm.sendMessage("[BotManager] Slot " + slot + " activado: " + bot.getName() + " (Lv " + bot.getLevel() + ", objectId " + charId + ").");
			}
			
			return true;
		}
		catch (Exception e)
		{
			LOGGER.warning("[BotManager] Could not activate slot " + slot + " (objectId " + charId + "): " + e);
			if (bot != null)
			{
				try
				{
					teardownBot(bot);
				}
				catch (Exception ex)
				{
					LOGGER.warning("[BotManager] Rollback issue for objectId " + charId + ": " + ex);
				}
			}
			
			untrackBot(charId);
			if (announce)
			{
				gm.sendMessage("[BotManager] ERROR al activar el slot " + slot + ": " + e.getMessage());
			}
			
			return false;
		}
	}
	
	/**
	 * Computes the single shared circular formation around the leader.
	 * Index i always maps to the real list being placed, never to the slot number.
	 * @param gmX leader X snapshot
	 * @param gmY leader Y snapshot
	 * @param gmZ leader Z snapshot
	 * @param count amount of bots receiving the formation
	 * @return destinations in index order, empty when count is not positive
	 */
	private static List<Location> computeFormationDestinations(int gmX, int gmY, int gmZ, int count)
	{
		final List<Location> destinations = new ArrayList<>(Math.max(0, count));
		if (count <= 0)
		{
			return destinations;
		}
		
		for (int i = 0; i < count; i++)
		{
			final double angle = (2 * Math.PI * i) / count;
			final int dx = (int) Math.round(FORMATION_RADIUS * Math.cos(angle));
			final int dy = (int) Math.round(FORMATION_RADIUS * Math.sin(angle));
			destinations.add(new Location(gmX + dx, gmY + dy, gmZ));
		}
		
		return destinations;
	}
	
	/**
	 * Collects the really active bots in deterministic order (objectId ascending).
	 * @return active world Players tracked by this manager, without null or dead entries
	 */
	private static List<Player> collectActiveBots()
	{
		final List<Player> active = new ArrayList<>(BOT_REGISTRY.size());
		for (int charId : new HashSet<>(BOT_REGISTRY))
		{
			final Player bot = World.getPlayer(charId);
			if ((bot == null) || bot.isDead())
			{
				continue;
			}
			
			active.add(bot);
		}
		
		active.sort((left, right) -> Integer.compare(left.getObjectId(), right.getObjectId()));
		return active;
	}
	
	/**
	 * Reorders the active bots around the GM using native AI walking only.
	 * No respawn, teleport, party, AutoPlay, AutoUse, target, inventory or role change happens here.
	 * @param gm the GM leader
	 * @return true when at least one bot received a MOVE_TO order
	 */
	/**
	 * Switches the native AutoPlay of the player who opened the panel, using the same task manager pair the
	 * native {@code .play start} and {@code .play stop} arguments run.<br>
	 * <b>No second system.</b> There is no dashboard state: the branch is decided by
	 * {@link Player#isAutoPlaying()}, the same native flag the AutoPlay task manager sets and clears, so a
	 * character that starts AutoPlay by any other means is reflected here too. The state is never stored by
	 * this manager, and no bot, slot, party or registry entry is involved: only the acting player changes.
	 * @param player the player who opened the Bot Manager panel
	 * @return true when a switch was requested
	 */
	private static boolean toggleSelfAutoPlay(Player player)
	{
		if (player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
			AutoUseTaskManager.getInstance().stopAutoUseTask(player);
		}
		else
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(player);
			AutoUseTaskManager.getInstance().startAutoUseTask(player);
		}

		return true;
	}

	private static boolean formationBots(Player gm)
	{
		final List<Player> active = collectActiveBots();
		if (active.isEmpty())
		{
			gm.sendMessage("[BotManager] No hay bots activos para formar.");
			return false;
		}
		
		final int gmX = gm.getX();
		final int gmY = gm.getY();
		final int gmZ = gm.getZ();
		final List<Location> destinations = computeFormationDestinations(gmX, gmY, gmZ, active.size());
		
		int ordered = 0;
		for (int i = 0; i < active.size(); i++)
		{
			final Player bot = active.get(i);
			if ((bot == null) || bot.isDead())
			{
				continue;
			}
			
			// Never yank an engaged bot: the same guard applyAssist and syncBotsWithLeader use. A bot on a
			// living monster keeps fighting and rejoins the ring through BotFormationMode.maintain once the
			// kill is over; this one-shot order only collects the idle ones. Without this guard, pressing
			// Formation while the group was fighting dragged every combatant straight out of combat.
			if (isFighting(bot) || bot.isAttackingNow() || bot.isCastingNow() || bot.isCastingSimultaneouslyNow())
			{
				continue;
			}
			
			final Location destination = destinations.get(i);
			bot.getAI().setIntentionMoveTo(destination);
			ordered++;
		}
		
		gm.sendMessage("[BotManager] Formacion: " + ordered + " bots reagrupandose.");
		LOGGER.info("[BotManager] " + gm.getName() + " ordered formation for " + ordered + " bots.");
		return ordered > 0;
	}
	
	/**
	 * Native bootstrap of a clientless Player, following the same sequence used by the native offline play restore. {@link Player#startOfflinePlay()} is intentionally not used because it requires a real client.
	 * @param gm the GM leader
	 * @param bot the freshly loaded character
	 * @param destination the precomputed formation destination
	 */
	private static void prepareBot(Player gm, Player bot, Location destination)
	{
		bot.setOnlineStatus(true, false);
		bot.spawnMe(destination.getX(), destination.getY(), destination.getZ());
		bot.restoreEffects();
		bot.setRunning();
		
		// Must be set before AutoPlay and AutoUse accept a clientless Player.
		bot.setOfflinePlay(true);
		
		// Native .play configuration only. No invented defaults: holders keep native
		// values unless character_variables holds a persisted AUTO_USE_SETTINGS row.
		restorePersistedAutoUseSettings(bot);
		// A character that never configured .play has no persisted list to restore, so the semantic
		// analyzer builds one in memory. The builder is a no-op when a native configuration exists.
		AutoPlayProfileBuilder.build(bot);

		ensureParty(gm, bot);
		
		// The bot enters with the current group state, never with its own previous flag. A fresh character is
		// off by default, so this only starts the engine while the orchestrator has the switch on.
		if (PARTY_AUTOPLAY_ORCHESTRATORS.contains(gm.getObjectId()))
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(bot);
		}
		AutoUseTaskManager.getInstance().startAutoUseTask(bot);
		
		// Native .potionon equivalent: same server-side registration the AutoPotion command performs.
		AutoPotionTaskManager.getInstance().add(bot);
		
		// Configure native AutoShots from the available shot grade and inventory stock.
		configureBotAutoShots(bot);
	}
	
	/**
	 * Replays the persisted native .play configuration of the character itself (the very same variables the native AutoPlay login listener reads, which does not run for a clientless Player).<br>
	 * Mirrors {@code AutoPlay.ON_PLAYER_LOGIN}: list keys replace the runtime holder content, the potion item is set from its own key, and the seven AUTO_USE_SETTINGS positions keep native semantics ([1] is ignored here; AdminBotManager controls start/stop explicitly).<br>
	 * No value is invented: when a key is absent the corresponding holder keeps its native content (fresh holders start empty, adopted holders keep what they already run with).
	 * @param bot the loaded character
	 */
	private static void restorePersistedAutoUseSettings(Player bot)
	{
		final List<Integer> actions = bot.getVariables().getIntegerList(PlayerVariables.AUTO_USE_ACTIONS);
		if (!actions.isEmpty())
		{
			bot.getAutoUseSettings().getAutoActions().clear();
			bot.getAutoUseSettings().getAutoActions().addAll(actions);
		}
		
		final List<Integer> buffs = bot.getVariables().getIntegerList(PlayerVariables.AUTO_USE_BUFFS);
		if (!buffs.isEmpty())
		{
			bot.getAutoUseSettings().getAutoBuffs().clear();
			bot.getAutoUseSettings().getAutoBuffs().addAll(buffs);
		}
		
		final List<Integer> skills = bot.getVariables().getIntegerList(PlayerVariables.AUTO_USE_SKILLS);
		if (!skills.isEmpty())
		{
			bot.getAutoUseSettings().getAutoSkills().clear();
			bot.getAutoUseSettings().getAutoSkills().addAll(skills);
		}
		
		final List<Integer> items = bot.getVariables().getIntegerList(PlayerVariables.AUTO_USE_ITEMS);
		if (!items.isEmpty())
		{
			bot.getAutoUseSettings().getAutoSupplyItems().clear();
			bot.getAutoUseSettings().getAutoSupplyItems().addAll(items);
		}
		
		if (bot.getVariables().hasVariable(PlayerVariables.AUTO_USE_POTION))
		{
			bot.getAutoUseSettings().setAutoPotionItem(bot.getVariables().getInt(PlayerVariables.AUTO_USE_POTION, 0));
		}
		
		final List<Integer> settings = bot.getVariables().getIntegerList(PlayerVariables.AUTO_USE_SETTINGS);
		if (settings.size() == 7)
		{
			bot.getAutoPlaySettings().setOptions(settings.get(0));
			bot.getAutoPlaySettings().setPickup(settings.get(2) == 1);
			bot.getAutoPlaySettings().setNextTargetMode(settings.get(3));
			bot.getAutoPlaySettings().setShortRange(settings.get(4) == 1);
			bot.getAutoPlaySettings().setAutoPotionPercent(settings.get(5));
			bot.getAutoPlaySettings().setRespectfulHunting(settings.get(6) == 1);
		}
	}
	
	/**
	 * Configures native AutoShots for a clientless bot from the shot grade available in its inventory.<br>
	 * Cleans previous registered shots, registers Soulshot and Blessed Spiritshot independently,
	 * uses normal Spiritshot only when Blessed Spiritshot is unavailable, and precharges the shots.
	 * @param bot the bot Player
	 */
	private static void configureBotAutoShots(Player bot)
	{
		bot.disableAutoShotsAll();
		
		final CrystalType grade = getAvailableAutoShotGrade(bot);
		boolean registeredShot = false;
		
		// Beginner shots are their own source and sit outside the grade tables, so they are registered first and
		// independently. A low level character whose only shots are these two would otherwise be skipped, because
		// no grade in the loop maps to them. They are never a replacement for a higher grade: the grade logic below
		// still runs and adds whatever else the character actually owns.
		if (hasInventoryItem(bot, SOULSHOT_NO_GRADE_FOR_BEGINNERS))
		{
			bot.addAutoSoulShot(SOULSHOT_NO_GRADE_FOR_BEGINNERS);
			registeredShot = true;
		}
		
		if (hasInventoryItem(bot, SPIRITSHOT_NO_GRADE_FOR_BEGINNERS))
		{
			bot.addAutoSoulShot(SPIRITSHOT_NO_GRADE_FOR_BEGINNERS);
			registeredShot = true;
		}
		
		if (grade == null)
		{
			LOGGER.info("[BotManager] " + bot.getName() + (registeredShot ? " AutoShots configured for beginner shots." : " AutoShot: none (no shots available in inventory)."));
			bot.rechargeShots(true, true);
			return;
		}
		
		final int soulShotId = getSoulShotId(grade);
		final int blessedSpiritShotId = getBlessedSpiritShotId(grade);
		final int spiritShotId = getSpiritShotId(grade);
		
		if (hasInventoryItem(bot, soulShotId))
		{
			bot.addAutoSoulShot(soulShotId);
			registeredShot = true;
		}
		
		if (hasInventoryItem(bot, blessedSpiritShotId))
		{
			bot.addAutoSoulShot(blessedSpiritShotId);
			registeredShot = true;
		}
		else if (hasInventoryItem(bot, spiritShotId))
		{
			bot.addAutoSoulShot(spiritShotId);
			registeredShot = true;
		}
		
		bot.rechargeShots(true, true);
		if (registeredShot)
		{
			LOGGER.info("[BotManager] " + bot.getName() + " AutoShots configured for grade " + grade + ".");
		}
		else
		{
			LOGGER.info("[BotManager] " + bot.getName() + " AutoShot: none (no compatible shot in inventory for grade " + grade + ").");
		}
	}
	
	private static CrystalType getAvailableAutoShotGrade(Player bot)
	{
		for (CrystalType grade : new CrystalType[] { CrystalType.S, CrystalType.A, CrystalType.B, CrystalType.C, CrystalType.D, CrystalType.NONE })
		{
			if (hasInventoryItem(bot, getSoulShotId(grade)) || hasInventoryItem(bot, getBlessedSpiritShotId(grade)) || hasInventoryItem(bot, getSpiritShotId(grade)))
			{
				return grade;
			}
		}
		
		return null;
	}
	
	private static boolean hasInventoryItem(Player bot, int itemId)
	{
		return (itemId > 0) && (bot.getInventory().getItemByItemId(itemId) != null);
	}
	
	private static int getSoulShotId(CrystalType grade)
	{
		if (grade == null)
		{
			return 0;
		}
		
		switch (grade)
		{
			case NONE:
				return 1835;
			case D:
				return 1463;
			case C:
				return 1464;
			case B:
				return 1465;
			case A:
				return 1466;
			case S:
			case S80:
			case S84:
				return 1467;
			default:
				return 0;
		}
	}
	
	private static int getSpiritShotId(CrystalType grade)
	{
		if (grade == null)
		{
			return 0;
		}
		
		switch (grade)
		{
			case NONE:
				return 2509;
			case D:
				return 2510;
			case C:
				return 2511;
			case B:
				return 2512;
			case A:
				return 2513;
			case S:
			case S80:
			case S84:
				return 2514;
			default:
				return 0;
		}
	}
	
	private static int getBlessedSpiritShotId(CrystalType grade)
	{
		if (grade == null)
		{
			return 0;
		}
		
		switch (grade)
		{
			case NONE:
				return 3947;
			case D:
				return 3948;
			case C:
				return 3949;
			case B:
				return 3950;
			case A:
				return 3951;
			case S:
			case S80:
			case S84:
				return 3952;
			default:
				return 0;
		}
	}
	
	/**
	 * Joins the bot to the GM party, creating it when needed. The GM always stays the leader and no party is ever destroyed here.
	 * @param gm the GM leader
	 * @param bot the character to join
	 */
	private static void ensureParty(Player gm, Player bot)
	{
		Party party = gm.getParty();
		if (party == null)
		{
			party = new Party(gm, PartyDistributionType.FINDERS_KEEPERS);
			gm.setParty(party);
		}
		
		if ((bot.getParty() != null) && (bot.getParty() != party))
		{
			bot.leaveParty();
		}
		
		bot.joinParty(party);
	}
	
	/**
	 * Takes over a character that is already in the world in offline play state, without loading a second Player for the same objectId.
	 * @param gm the GM running the command
	 * @param slot the 1 based slot
	 * @param bot the existing world object
	 * @return true when the character is now tracked as a bot
	 */
	private static boolean adoptBot(Player gm, int slot, Player bot)
	{
		try
		{
			// Same native .play restore as a fresh activation. Keys present in
			// character_variables replace holder content; absent keys keep runtime state.
			restorePersistedAutoUseSettings(bot);
			// Same native .play restore as a fresh activation, then the same in-memory build.
			AutoPlayProfileBuilder.build(bot);

			ensureParty(gm, bot);
			
			// An adopted character enters with the current group state: on while the orchestrator switch is on,
			// and off otherwise, even when the character still remembered running before the dismissal.
			final boolean partyAutoPlay = PARTY_AUTOPLAY_ORCHESTRATORS.contains(gm.getObjectId());
			if (partyAutoPlay && !bot.isAutoPlaying())
			{
				AutoPlayTaskManager.getInstance().startAutoPlay(bot);
			}
			else if (!partyAutoPlay && bot.isAutoPlaying())
			{
				AutoPlayTaskManager.getInstance().stopAutoPlay(bot);
			}
			
			AutoUseTaskManager.getInstance().startAutoUseTask(bot);
			AutoPotionTaskManager.getInstance().add(bot);
			configureBotAutoShots(bot);
			registerBot(bot, gm);
			LOGGER.info("[BotManager] " + gm.getName() + " adopted slot " + slot + " (" + bot.getName() + ", objectId " + bot.getObjectId() + ").");
			gm.sendMessage("[BotManager] El slot " + slot + " ya estaba en OfflinePlay. Adoptado: " + bot.getName() + ".");
			return true;
		}
		catch (Exception e)
		{
			LOGGER.warning("[BotManager] Could not adopt slot " + slot + " (objectId " + bot.getObjectId() + "): " + e);
			gm.sendMessage("[BotManager] ERROR al adoptar el slot " + slot + ": " + e.getMessage());
			return false;
		}
	}
	
	/**
	 * Native teardown order for a clientless bot.
	 * <p>
	 * <b>BOTAI v2 - two corrections over v1:</b>
	 * <ol>
	 * <li><b>Order.</b> {@link Player#setAutoPlaying(boolean)} invokes
	 * {@code OfflinePlayTable.removeOfflinePlay()} whenever
	 * {@code (!value && _offlinePlay && RESTORE_AUTO_PLAY_OFFLINERS)}. Because a bot is
	 * clientless it requires {@code offlinePlay == true} to survive the AutoPlay/AutoUse
	 * gate, so stopping AutoPlay <em>before</em> clearing that flag reached the DELETE
	 * against {@code character_offline_play}. Clearing the flag first short-circuits the
	 * condition, so no SQL is issued and the table is never required.</li>
	 * <li><b>Data safety.</b> {@code Disconnection.storeAndDelete()} calls
	 * {@link Player#deleteMe()} whenever {@code isOnline()} is true, which permanently
	 * removes the character row, inventory, warehouse and freight. Bots are real, persistent
	 * characters and must survive their own teardown, so that call is forbidden here.
	 * {@code setOnlineStatus(false, false)} and {@code decayMe()} are memory/world only.</li>
	 * </ol>
	 * @param bot the bot to shut down
	 */
	private static void teardownBot(Player bot)
	{
		// Unregister from AutoPotion: it has no setAutoPlaying side effect.
		AutoPotionTaskManager.getInstance().remove(bot);
		// Formation hold is a per session switch.
		BotFormationMode.forget(bot.getObjectId());
		// The per player assist split is per session as well: a bot that goes away must not leave it on.
		AutoPlayTaskManager.clearAssistWithoutFollow(bot);
		bot.disableAutoShotsAll();
		AutoUseTaskManager.getInstance().stopAutoUseTask(bot);
		
		// v2: MUST run before stopAutoPlay, otherwise setAutoPlaying(false) tries to
		// DELETE from character_offline_play while _offlinePlay is still true.
		bot.setOfflinePlay(false);
		
		AutoPlayTaskManager.getInstance().stopAutoPlay(bot);
		if (bot.getParty() != null)
		{
			bot.leaveParty();
		}
		
		// The remembered target mode is handed back before anything is written, so a dismissed character never
		// persists the muted value that only ever existed in memory while it was being driven.
		restoreNativeTargetAcquisition(bot);
		
		// v2: safe cleanup. No storeAndDelete() (it would deleteMe() the character).
		// storeMe() is required, not optional: doRevive() and reviveAnswer() only ever change memory, so without
		// this the characters table keeps the stale dead row and the bot reloads as dead on the next adoption.
		// It runs after setOnlineStatus and before decayMe, so the persisted row matches the live state.
		bot.setOnlineStatus(false, false);
		bot.storeMe();
		bot.decayMe();
	}
	
	private static boolean deactivateSlot(Player gm, int slot)
	{
		final int charId = resolveSlotCharId(gm, slot);
		if (charId < 0)
		{
			gm.sendMessage("[BotManager] No hay personaje en el slot " + slot + ".");
			return false;
		}
		
		return deactivateBot(gm, slot, charId);
	}
	
	private static boolean deactivateBot(Player gm, int slot, int charId)
	{
		// REMOVE de composicion: solo el lider de la Party (isPartyLeader).
		if (!isPartyLeader(gm))
		{
			gm.sendMessage("[BotManager] Debes ser el lider de tu Party para activar o desactivar bots.");
			return false;
		}
		
		final Player bot = World.getPlayer(charId);
		if (bot == null)
		{
			untrackBot(charId);
			gm.sendMessage("[BotManager] El slot " + slot + " no esta en el mundo. Registro limpiado.");
			return false;
		}
		
		final String name = bot.getName();
		final Long activatedAt = BOT_SESSIONS.get(charId);
		try
		{
			teardownBot(bot);
		}
		catch (Exception e)
		{
			LOGGER.warning("[BotManager] Could not deactivate slot " + slot + " (objectId " + charId + "): " + e);
			gm.sendMessage("[BotManager] ERROR al desactivar el slot " + slot + ": " + e.getMessage());
		}
		
		untrackBot(charId);
		if (activatedAt != null)
		{
			LOGGER.info("[BotManager] " + name + " (objectId " + charId + ") deactivated after " + ((System.currentTimeMillis() - activatedAt) / 1000) + "s.");
		}
		
		gm.sendMessage("[BotManager] Slot " + slot + " desactivado: " + name + ".");
		return true;
	}
	
	private static boolean deactivateAllBots(Player gm)
	{
		// REMOVE de composicion: solo el lider de la Party (isPartyLeader).
		if (!isPartyLeader(gm))
		{
			gm.sendMessage("[BotManager] Debes ser el lider de tu Party para activar o desactivar bots.");
			return false;
		}
		
		if (BOT_REGISTRY.isEmpty())
		{
			gm.sendMessage("[BotManager] No hay bots activos.");
			return false;
		}
		
		int removed = 0;
		for (int charId : new HashSet<>(BOT_REGISTRY))
		{
			final Player bot = World.getPlayer(charId);
			if (bot == null)
			{
				untrackBot(charId);
				continue;
			}
			
			final String name = bot.getName();
			try
			{
				teardownBot(bot);
				removed++;
			}
			catch (Exception e)
			{
				LOGGER.warning("[BotManager] Could not deactivate " + name + " (objectId " + charId + "): " + e);
			}
			
			untrackBot(charId);
			gm.sendMessage("[BotManager] Desactivado: " + name + ".");
		}
		
		// The GM party is intentionally left untouched.
		LOGGER.info("[BotManager] " + removed + " bots deactivated by " + gm.getName() + ".");
		gm.sendMessage("[BotManager] Bots desactivados: " + removed + ".");
		return removed > 0;
	}
	
	private static boolean activateAllSlots(Player gm)
	{
		if (!isPartyLeader(gm))
		{
			gm.sendMessage("[BotManager] Debes ser el lider de tu Party para activar o desactivar bots.");
			return false;
		}
		
		// Phase 1: collect the missing slots without touching the bots that are already active.
		int skipped = 0;
		final List<Integer> missingSlots = new ArrayList<>(LAST_BOT_SLOT - FIRST_BOT_SLOT + 1);

for (int slot = FIRST_BOT_SLOT; slot <= LAST_BOT_SLOT; slot++)
		{
			if (isSlotActive(gm, slot))
			{
				skipped++;
				continue;
			}
			
			final CharacterInfoHolder info = resolveSlot(gm, slot);
			if (info == null)
			{
				skipped++;
				continue;
			}
			
			final int charId = info.getObjectId();
			if (charId == gm.getObjectId())
			{
				skipped++;
				continue;
			}
			
			// Already in the world without being tracked: adopt it now and keep it out of the fresh formation.
			final Player existing = World.getPlayer(charId);
			if (existing != null)
			{
				if (BOT_REGISTRY.contains(charId))
				{
					skipped++;
					continue;
				}
				
				if ((existing.getClient() == null) && existing.isOfflinePlay())
				{
					if (adoptBot(gm, slot, existing))
					{
						skipped++;
					}
					else
					{
						skipped++;
					}
					
					continue;
				}
				
				skipped++;
				continue;
			}
			
			missingSlots.add(slot);
		}
		
		// Phase 2: the formation is computed only for the really fresh bots, in slot order.
		if (missingSlots.isEmpty())
		{
			gm.sendMessage("[BotManager] Bots activados: 0. Omitidos: " + skipped + ".");
			return false;
		}
		
		final List<Location> destinations = computeFormationDestinations(gm.getX(), gm.getY(), gm.getZ(), missingSlots.size());
		int activated = 0;
		int failed = 0;
		for (int i = 0; i < missingSlots.size(); i++)
		{
			if (activateSlotAt(gm, missingSlots.get(i), destinations.get(i), false))
			{
				activated++;
			}
			else
			{
				failed++;
			}
		}
		
		skipped += failed;
		gm.sendMessage("[BotManager] Bots activados: " + activated + ". Omitidos: " + skipped + ".");
		return activated > 0;
	}
	
	private static boolean statusBots(Player gm)
	{
		gm.sendMessage("[BotManager] Estado de slots " + FIRST_BOT_SLOT + ".." + LAST_BOT_SLOT + ":");

for (int slot = FIRST_BOT_SLOT; slot <= LAST_BOT_SLOT; slot++)
		{
			final CharacterInfoHolder info = resolveSlot(gm, slot);
			if (info == null)
			{
				gm.sendMessage("- Slot " + slot + ": NO EXISTE");
				continue;
			}
			
			final int charId = info.getObjectId();
			final String base = "- Slot " + slot + ": " + info.getName() + " Lv " + info.getLevel() + " classId " + info.getClassId() + " objectId " + charId + " -> ";
			final Player bot = World.getPlayer(charId);
			if (bot == null)
			{
				if (BOT_REGISTRY.contains(charId))
				{
					untrackBot(charId);
					gm.sendMessage(base + "INACTIVO (registro obsoleto limpiado)");
				}
				else
				{
					gm.sendMessage(base + "INACTIVO");
				}
				
				continue;
			}
			
			if (!BOT_REGISTRY.contains(charId))
			{
				gm.sendMessage(base + "EN JUEGO");
				continue;
			}
			
			gm.sendMessage(base + "BOT ACTIVO AutoPlay " + bot.isAutoPlaying() + " OfflinePlay " + bot.isOfflinePlay() + " Party " + (bot.getParty() != null) + " HP " + ((int) bot.getCurrentHp()) + "/" + ((int) bot.getMaxHp()));
		}
		
		return true;
	}
	
	/**
	 * Periodic task that releases the own target of the bots whenever the party leader no longer has one.<br>
	 * The leader is read from the party of the bots, which is exactly the same leader the native AssistLeader
	 * branch of {@code AutoPlayTaskManager} compares against, so both mechanisms always agree.<br>
	 * The task does nothing at all when there is no bot, no party or no leader, so it costs a single empty
	 * registry read while the Bot Manager is unused.
	 */
	private static final Runnable TARGET_SYNC_TASK = () ->
	{
		// One isolated pass per orchestrator. Never a global pass: each account only ever sees its own bots and
		// its own party, so two accounts running the Bot Manager cannot read or write each other.
		for (int orchestratorId : new HashSet<>(BOT_OWNERS.keySet()))
		{
			processOrchestrator(orchestratorId);
		}
	};

	/**
	 * Runs the full cycle of a single orchestrator: the unchanged baseline release first, then the two additive
	 * layers. Everything it reads and writes belongs to that orchestrator account and party only, so several
	 * accounts can run the Bot Manager at the same time without ever crossing.<br>
	 * <b>Staleness.</b> Only a disconnected orchestrator loses its registration. Being dead, having no party or
	 * having no bot right now are all transient states: dropping the entry on any of them used to destroy the
	 * identity permanently, so the periodic task stopped visiting the orchestrator forever and Assist
	 * could never run again.
	 * @param orchestratorId the object identifier of the orchestrator that owns the group
	 */
	private static void processOrchestrator(int orchestratorId)
	{
		final Player orchestrator = World.getPlayer(orchestratorId);
		if ((orchestrator == null) || (orchestrator.getClient() == null))
		{
			dropOrchestrator(orchestratorId);
			return;
		}
		
		// A dead orchestrator stays registered and is simply skipped this cycle, so it resumes by itself once
		// revived instead of losing its identity.
		if (orchestrator.isDead())
		{
			return;
		}
		
		// The corpse policy of his own .play Spoil switch also governs the orchestrator: he is a normal
		// player, so his own dead target is dropped here when he asked for it, before any group logic.
		releaseOwnCorpse(orchestrator);
		
		final Party party = orchestrator.getParty();
		if (party == null)
		{
			return;
		}
		
		final List<Player> controlled = collectControlledBots(orchestrator, party);
		if (controlled.isEmpty())
		{
			return;
		}
		
		// The leader is read once per cycle and drives both paths, so the baseline release and the orchestrator
		// behaviours can never disagree about who the party leader is.
		final Player leader = party.getLeader();

		// (0) A body is never a target. This runs first and unconditionally, before any layer that could
		// re-acquire, so a bot that just killed something walks away instead of standing on the corpse.
		releaseDeadTargets(controlled);
		
		// (1) Baseline release, unchanged, but fed only with the bots of this orchestrator.
		syncBotsWithLeader(leader, controlled);
		
		// (2) The two additive, independent layers, scoped to the same group and to the same leader.
		applyOrchestratorBehaviours(orchestrator, leader, controlled);
		
		// (3) The continuous formation, only while its switch is on: the ring is re-issued on every cycle so
		// the group walks together instead of being ordered once and then drifting apart.<br>
		// <b>Why it also runs under pure assist.</b> A bot that just finished a kill is out of its slot with
		// nothing to attack, and isBusy() lets the ring take it home. The ring is only skipped for a bot that
		// has a target, and those are the ones that must stay on the monster, so nothing ever fights and
		// walks at the same time.
		if (BotFormationMode.isOn(orchestrator))
		{
			BotFormationMode.maintain(leader, controlled);
		}
	}
	
	/**
	 * Makes sure the orchestrator has an entry in {@link #BOT_OWNERS}, because the periodic task only visits
	 * registered orchestrators.<br>
	 * <b>What the reconstruction may use.</b> Only players that are already tracked as bots by this manager
	 * ({@link #BOT_REGISTRY}), and {@link #collectControlledBots} performs the account and party filtering, so no
	 * filter is duplicated here. Clan, command channel and any other party member are never consulted, and an
	 * association is never created between two different accounts.
	 * @param orchestrator the connected player using the panel
	 */
	private static void ensureOrchestratorRegistration(Player orchestrator)
	{
		// The periodic pass also owns the .play corpse policy of the orchestrator himself, so it must run as
		// soon as any orchestrator registers, not only after the first bot. Single scheduling is kept by the
		// same guard the bot registration uses.
		if (TARGET_SYNC_STARTED.compareAndSet(false, true))
		{
			ThreadPool.schedulePriorityTaskAtFixedRate(TARGET_SYNC_TASK, TARGET_SYNC_PERIOD, TARGET_SYNC_PERIOD);
		}
		
		final int orchestratorId = orchestrator.getObjectId();
		if (BOT_OWNERS.containsKey(orchestratorId))
		{
			return;
		}
		
		final Set<Integer> owned = BOT_OWNERS.computeIfAbsent(orchestratorId, key -> ConcurrentHashMap.newKeySet());
		for (Player bot : collectActiveBots())
		{
			if (bot.getAccountNamePlayer().equals(orchestrator.getAccountNamePlayer()))
			{
				owned.add(bot.getObjectId());
			}
		}
	}
	
	/**
	 * The bots this orchestrator may drive: the ones it registered, still in the world, alive, of its own account
	 * and in its own party. The identity is the stable account identity, never GM status, never clan and never
	 * the command channel, so two of the same operator accounts stay completely independent.
	 * @param orchestrator the connected player owning the group
	 * @param party the orchestrator party
	 * @return the controlled bots of this group only, never null
	 */
	private static List<Player> collectControlledBots(Player orchestrator, Party party)
	{
		final List<Player> controlled = new ArrayList<>();
		final Set<Integer> registered = BOT_OWNERS.get(orchestrator.getObjectId());
		if (registered == null)
		{
			return controlled;
		}
		
		for (int charId : new HashSet<>(registered))
		{
			final Player bot = World.getPlayer(charId);
			if ((bot == null) || bot.isDead())
			{
				continue;
			}
			
			if (!bot.getAccountNamePlayer().equals(orchestrator.getAccountNamePlayer()))
			{
				continue;
			}
			
			if (party.getMembers().contains(bot))
			{
				controlled.add(bot);
			}
		}
		
		// Same deterministic order the global collector produced, so the baseline stays predictable.
		controlled.sort(Comparator.comparingInt(Player::getObjectId));
		return controlled;
	}
	
	/**
	 * Applies the per orchestrator Assist layer on top of the unchanged baseline release.<br>
	 * <b>Who decides and who is the target.</b> The orchestrator decides: it owns the switch and it owns the
	 * group. The behaviour target is the current party leader, never a fixed character, so a leadership change
	 * is picked up on the next cycle without touching the switch. Leadership only defines the destination; the
	 * ownership of the bots is still decided by the account and party filters of {@link #collectControlledBots}.<br>
	 * <b>Why separate.</b> {@link #syncBotsWithLeader} is the pre-existing release path. Assist is additive
	 * and per orchestrator, so it never alters that path and never regresses it when it is off.<br>
	 * @param orchestrator the orchestrator owning the group
	 * @param leader the current party leader, the behaviour destination
	 * @param controlled the bots of that group
	 */
	private static void applyOrchestratorBehaviours(Player orchestrator, Player leader, List<Player> controlled)
	{
		final int orchestratorId = orchestrator.getObjectId();
		final boolean assist = ASSIST_ORCHESTRATORS.contains(orchestratorId);
		
		// No leader, a dead orchestrator or a dead leader means there is nobody to drive the group.
		if (!assist || orchestrator.isDead() || (leader == null) || leader.isDead())
		{
			return;
		}

		for (Player bot : controlled)
		{
			// A character is never driven by itself, not even when it happens to be the party leader.
			if (bot == leader)
			{
				continue;
			}

			// While Assist is on, the native acquisition is forced to monsters only so an npc, a
			// merchant or a guard can never become a target.
			forceMonsterOnlyTargeting(bot);
			
			// Assist mirrors the leader target, and the stock engine makes that same branch follow the leader
			// whenever the leader has none, so the two were impossible to tell apart. The patched engine takes
			// a per player flag: a bot of this manager keeps mirroring the target and never gets the implicit
			// follow, so the group is driven by Assist and Formation instead of a follow task.
			AutoPlayTaskManager.setAssistWithoutFollow(bot);
			
			if (assist)
			{
				applyAssist(bot, leader);
			}
		}
	}

	/**
	 * Assist layer, scoped to this orchestrator: the bots obey the controller on attack, target switch and
	 * target cancel.<br>
	 * <b>Release, never write.</b> The leader target is never written into the bot: dropping the own target
	 * is enough, the native AssistLeader branch adopts the leader target on its next pass (at most 700 ms) -
	 * the very same path that already makes the attack mirroring work - so this manager never races that
	 * thread.<br>
	 * <b>Cancel = ESC.</b> When the controller drops its target, every bot that is fighting does exactly what
	 * the ESC key does to the controller: abort the cast, drop the target and go idle on the next pass.<br>
	 * <b>Transition only.</b> The cancel branch only touches a bot that holds a living monster: an already
	 * idle bot, a self-buff without monster and the spoil sweep on a corpse are never interrupted, so an
	 * idle bot keeps walking with the formation undisturbed.<br>
	 * <b>Support is sacred.</b> A target on a party member is an AutoUse decision and is never interrupted,
	 * neither on switch nor on cancel.<br>
	 * @param bot the controlled bot
	 * @param leader the current party leader, read fresh every cycle
	 */
	private static void applyAssist(Player bot, Player leader)
	{
		if (bot == null)
		{
			return;
		}

		final WorldObject ownTarget = bot.getTarget();

		// Targeting a party member is a support action chosen by AutoUse, not an assist decision.
		if ((ownTarget != null) && ownTarget.isPlayer())
		{
			return;
		}

		// Already mirroring the leader: leave it alone. Releasing a target that is already the right one
		// forces the engine to acquire it again from scratch on its next pass, and that churn every 700 ms
		// is what made a warrior drop its rotation and walk back to its slot in pauses.
		if ((ownTarget != null) && isPveMonster(bot, leader.getTarget()) && (ownTarget == leader.getTarget()))
		{
			return;
		}

		// The controller is on a monster this bot may attack: the switch side of the mirror.
		if (isPveMonster(bot, leader.getTarget()))
		{
			// Never touch a target while a skill runs: that target is also what AutoUseTaskManager aims
			// with, so a switch during a cast waits for the cast to end and lands on the next pass.
			if (bot.isCastingNow() || bot.isCastingSimultaneouslyNow())
			{
				return;
			}

			// Drop the own target only. The ATTACK intention is deliberately kept, so the bot stays busy
			// for the formation ring during the gap and never gets recalled mid-switch; the native loop
			// adopts the leader target on its very next pass - the same path the attack mirror already uses.
			if (ownTarget != null)
			{
				bot.setTarget(null);
			}
			return;
		}

		// The controller cancelled its target (or holds nothing usable): the cancel side, ESC semantics.
		// Transition only - a bot that does not hold a living monster is left completely alone, so an idle
		// bot keeps walking with the formation and a corpse keeps its spoil flow.
		if (!isFighting(bot))
		{
			return;
		}

		// Exact ESC behaviour: cut the cast, drop the target, go idle.
		if (bot.isCastingNow() || bot.isCastingSimultaneouslyNow())
		{
			bot.abortCast();
		}
		bot.setTarget(null);
		bot.getAI().setIntentionIdle();
	}

	/**
	 * Drops the own target of every controlled bot.<br>
	 * Used when Assist is switched on: a bot that holds its own target keeps attacking it, because the native
	 * AutoPlay loop only consults the party leader when no target is held. Clearing it is what lets the very
	 * next native cycle adopt the leader target.
	 * @param controlled the bots to release
	 */
	private static void releaseOwnTargets(List<Player> controlled)
	{
		if (controlled == null)
		{
			return;
		}

		for (Player bot : controlled)
		{
			if ((bot != null) && !bot.isDead() && (bot.getTarget() != null))
			{
				bot.setTarget(null);
			}
		}
	}
	
	/**
	 * Forces the native target acquisition to monsters only while this manager drives the character.<br>
	 * <b>How.</b> The engine searches a creature with {@code isTargetModeValid(mode, …)}. Mode 1 is the only case
	 * of that switch that requires {@code isMonster() && !isRaid() && isAutoAttackable()}, so it is the one mode
	 * that can never return an npc, a merchant, a guard or a teleporter. The player's own mode is remembered once
	 * and handed back untouched when both switches go off, so a character that was already on 1 is restored to 1.
	 * @param bot the controlled bot
	 */
	private static void forceMonsterOnlyTargeting(Player bot)
	{
		final int charId = bot.getObjectId();
		if (OWN_TARGET_MODES.containsKey(charId))
		{
			return;
		}
		
		OWN_TARGET_MODES.put(charId, bot.getAutoPlaySettings().getNextTargetMode());
		bot.getAutoPlaySettings().setNextTargetMode(MONSTER_ONLY_TARGET_MODE);
	}
	
	/**
	 * Gives the native target mode back exactly as it was before this manager ever touched the character. A
	 * character that this manager never touched is left alone, and a bot that is gone just loses its entry.
	 * @param bot the controlled bot
	 */
	private static void restoreNativeTargetAcquisition(Player bot)
	{
		final Integer original = OWN_TARGET_MODES.remove(bot.getObjectId());
		if (original != null)
		{
			bot.getAutoPlaySettings().setNextTargetMode(original);
		}
	}
	
	/**
	 * Tells whether a creature is a valid automatic target for this system.<br>
	 * <b>Rule.</b> Only a real monster that this character is allowed to attack qualifies. An npc, a merchant, a
	 * guard, a teleporter, a raid or another player never does, so neither the native acquisition nor Assist can
	 * ever make a bot engage one of them.
	 * @param actor the character that would own the target
	 * @param candidate the creature to test, may be null
	 * @return true when the candidate is a monster this actor may attack
	 */
	private static boolean isPveMonster(Player actor, WorldObject candidate)
	{
		return (candidate instanceof Creature) && ((Creature) candidate).isMonster() && !((Creature) candidate).isRaid() && ((Creature) candidate).isAutoAttackable(actor);
	}
	
	/**
	 * True when the bot must keep its current target because it is committed to a fight on a living
	 * creature, even if no blow is in flight right now.<br>
	 * Covers the approach walk, the intention window between approach and first hit, the cast of a
	 * nuke or dance, and the AutoPlay re-acquire gap after an Assist release: in all of those the
	 * raw attack flags can read false while the bot is mid fight. The only thing that matters is
	 * holding a living monster: a bot that holds one is never idle, whatever its AI intention says.
	 * @param bot the controlled bot
	 * @return true when the bot holds a living monster target
	 */
	private static boolean isFighting(Player bot)
	{
		if (bot == null)
		{
			return false;
		}
		
		final WorldObject target = bot.getTarget();
		return (target instanceof Creature) && ((Creature) target).isMonster() && !((Creature) target).isDead();
	}
	
	/**
	 * Forgets an orchestrator whose group is gone. The runtime flags are cleared, but the durable choice kept in
	 * {@code character_variables} is never touched, so the next login restores the very same behaviour.
	 * @param orchestratorId the object identifier of the orchestrator to forget
	 */
	private static void dropOrchestrator(int orchestratorId)
	{
		BOT_OWNERS.remove(orchestratorId);
		ASSIST_ORCHESTRATORS.remove(orchestratorId);
		// The party AutoPlay state is session only, so losing it here is what makes a new session start off.
		PARTY_AUTOPLAY_ORCHESTRATORS.remove(orchestratorId);
	}
	
	/**
	 * Snapshot del estado de orquestacion de un controlador. Debe capturarse ANTES del detach:
	 * el task periodico (700 ms) dropea al orquestador sin cliente via {@link #dropOrchestrator}.
	 */
	static final class OrchestratorState
	{
		int oldOid;
		boolean assistOn;
		boolean partyAutoOn;
		boolean formationOn;
		Set<Integer> owned;
	}
	
	/**
	 * PASO 1 de la transferencia: captura el estado de sesion del controlador mientras aun tiene cliente.
	 * @param oldPlayer el controlador actual (todavia conectado)
	 * @return snapshot del estado de ASSIST / PARTY_AUTOPLAY / FORMATION / BOT_OWNERS
	 */
	static OrchestratorState captureOrchestratorState(Player oldPlayer)
	{
		final int oldOid = oldPlayer.getObjectId();
		final OrchestratorState snap = new OrchestratorState();
		snap.oldOid = oldOid;
		snap.assistOn = ASSIST_ORCHESTRATORS.contains(oldOid);
		snap.partyAutoOn = PARTY_AUTOPLAY_ORCHESTRATORS.contains(oldOid);
		snap.formationOn = BotFormationMode.isOn(oldPlayer);
		final Set<Integer> owned = BOT_OWNERS.get(oldOid);
		if (owned != null)
		{
			snap.owned = new HashSet<>(owned);
		}
		
		return snap;
	}
	
	/**
	 * PASO 2 de la transferencia: reasocia el estado de orquestacion al NUEVO controlador tras
	 * SWAP/HANDOFF, con la semantica individual de cada estructura:
	 * <ul>
	 * <li>BOT_OWNERS: reconstruccion nativa por cuenta + union con la captura; nunca copia ciega.</li>
	 * <li>ASSIST: copia del VALOR ON/OFF + espejo durable en la variable del NUEVO controlador.
	 * La variable del antiguo controlador jamas se toca: dropOrchestrator tampoco la toca, y al
	 * volver el control a el se restaura sola.</li>
	 * <li>PARTY_AUTOPLAY: copia del VALOR (switch unico de grupo, sesion no persistida).</li>
	 * <li>FORMATION: copia del VALOR + forget del oid viejo (el unico orfanos que dropOrchestrator
	 * NO limpia).</li>
	 * </ul>
	 * No toca BOT_REGISTRY, BOT_SESSIONS, OWN_TARGET_MODES, isAutoPlaying de nadie, party, world
	 * ni el perfil AutoPlay de los bots: cambiar el controlador NO altera AutoFarm/Assist/Formation.
	 */
	static void transferOrchestratorState(OrchestratorState snap, Player newPlayer)
	{
		final int newOid = newPlayer.getObjectId();
		
		// BOT_OWNERS: reconstruccion nativa (por cuenta) + union con la captura; nunca sobrescribir.
		ensureOrchestratorRegistration(newPlayer);
		if ((snap.owned != null) && !snap.owned.isEmpty())
		{
			BOT_OWNERS.computeIfAbsent(newOid, key -> ConcurrentHashMap.newKeySet()).addAll(snap.owned);
		}
		BOT_OWNERS.remove(snap.oldOid);
		
		// ASSIST: copia del valor + espejo durable en la variable del NUEVO controlador.
		setOrchestratorFlag(ASSIST_ORCHESTRATORS, snap.assistOn, newOid);
		newPlayer.getVariables().set(BOT_ASSIST_VARIABLE, snap.assistOn);
		
		// PARTY_AUTOPLAY: copia del valor (switch unico de grupo).
		setOrchestratorFlag(PARTY_AUTOPLAY_ORCHESTRATORS, snap.partyAutoOn, newOid);
		
		// FORMATION: copia del valor + limpieza del oid viejo (dropOrchestrator no lo limpia).
		if (snap.formationOn != BotFormationMode.isOn(newPlayer))
		{
			BotFormationMode.toggle(newPlayer);
		}
		BotFormationMode.forget(snap.oldOid);
		
		LOGGER.info("[BotManager] Orchestration transferred " + snap.oldOid + " -> " + newOid + " assist=" + snap.assistOn + " partyAuto=" + snap.partyAutoOn + " formation=" + snap.formationOn);
	}
	
	/**
	 * Fija un switch de sesion al valor indicado (set, no flip: el valor del grupo se copia tal cual).
	 * @param flags el set de oids del switch
	 * @param value el valor copiado del controlador anterior
	 * @param objectId el oid del nuevo controlador
	 */
	private static void setOrchestratorFlag(Set<Integer> flags, boolean value, int objectId)
	{
		if (value)
		{
			flags.add(objectId);
		}
		else
		{
			flags.remove(objectId);
		}
	}
	
	/**
	 * Flips one orchestrator behaviour flag and mirrors the new state into the persistent variable, so a logout and
	 * a login restore the same choice without any further input. A toggle never enables anything by itself: the
	 * periodic task is what acts on it, and only for the bots of that orchestrator.
	 * @param flags the runtime flag set to flip
	 * @param variableKey the durable key of the same choice
	 * @param owner the connected player acting as orchestrator
	 * @return the new state, true when this layer now imposes the behaviour
	 */
	private static boolean toggleOrchestratorFlag(Set<Integer> flags, String variableKey, Player owner)
	{
		if (!flags.remove(owner.getObjectId()))
		{
			flags.add(owner.getObjectId());
		}
		
		final boolean enabled = flags.contains(owner.getObjectId());
		owner.getVariables().set(variableKey, enabled);
		return enabled;
	}
	
	/**
	 * Rebuilds the runtime flags of a player from its own persistent variables, once per session. Purely a read:
	 * the stored value is never written here, so opening the panel can never change the saved configuration.
	 * @param owner the connected player using the Bot Manager
	 */
	private static void restoreOrchestratorBehaviour(Player owner)
	{
		final int objectId = owner.getObjectId();
		if (ASSIST_ORCHESTRATORS.contains(objectId))
		{
			return;
		}
		
		if (owner.getVariables().getBoolean(BOT_ASSIST_VARIABLE, false))
		{
			ASSIST_ORCHESTRATORS.add(objectId);
		}
	}
	
	/**
	 * Releases the own target and the current attack intention of every active bot when the leader has no
	 * target.<br>
	 * <b>Why.</b> A bot keeps its own target until something clears it, and while a target is held the native
	 * AutoPlay loop handles that target and never reaches its AssistLeader branch. So a leader target cancel
	 * would never be observed by the bots. Clearing the bot target is enough to let the next AutoPlay cycle fall
	 * through to AssistLeader, which already decides between attacking the leader target and following the
	 * leader.<br>
	 * <b>What is deliberately not done.</b> The leader target is never written, AutoPlay, AutoUse, OfflinePlay
	 * and the party are never touched, and nothing happens while the leader holds a target, so this method never
	 * becomes a second implementation of AssistLeader.<br>
	 * <b>Order.</b> {@code setTarget(null)} drops the world target, so an attack already in flight is
	 * interrupted and the next AutoPlay cycle falls through to the engine decision. No movement is ever
	 * ordered here: repositioning belongs to Formation alone.
	 * @param leader the party leader, the source of truth; it is only read
	 * @param controlled the bots of the calling orchestrator only, already filtered by account and party
	 */
	private static void syncBotsWithLeader(Player leader, List<Player> controlled)
	{
		// The leader is the source of truth: while it holds a target, AssistLeader owns the decision.
		if ((leader == null) || leader.isDead())
		{
			return;
		}

		for (Player bot : controlled)
		{
			if (bot == null)
			{
				continue;
			}

			// The target release is the pre-existing baseline and stays exactly as it was. No follow is
			// re-issued here: this manager no longer imposes one, so this path can never resurrect a follow
			// the engine did not decide on its own. The characters keep the target release, nothing else.<br>
			// <b>Never break a fight in progress.</b> When the leader drops its target mid fight (a manual
			// cancel, a target switch, a dead select), the bot keeps its own living target and intention: clearing
			// it here is what opened the gap where the same cycle's maintain() found no target, no blow in
			// flight and a slot 40+ px away, and yanked the bot back to the ring after a single hit. A bot that
			// is attacking, casting or committed to ATTACK on a living creature is left completely alone, the
			// same guard applyAssist uses. The release only applies to a bot that is really idle.<br>
			// <b>Deliberately only the target.</b> Clearing the intention here as well was tried and reverted:
			// this branch runs on every idle cycle, so it also killed the ring move that Formation had just
			// issued and the bots walked in steps without ever settling. A corpse is handled separately in
			// releaseDeadTargets, which keeps the body as the target and drops only the attack intention.
			if (leader.getTarget() == null)
			{
				// Same guard: a bot holding a living monster keeps it even with no leader target.
				// isFighting alone covers it now (no intention check anymore), so casters, mages
				// and approaching warriors are all protected by the one call.
				if (!isFighting(bot))
				{
					bot.setTarget(null);
				}
			}
		}
	}

	/**
	 * Drops the corpse of a monster that a bot just killed.<br>
	 * <b>Why this is a separate pass.</b> A dead monster stays in the world for several seconds, and a bot that
	 * killed it keeps that body as its target: the engine only cleans a corpse inside the loop that is already
	 * handling that very target, so while the leader was busy with another monster the bots stood next to the
	 * body until it despawned. It has to run here, on every cycle and with no condition on the leader, because
	 * the previous placement inside the target release was skipped exactly in the moment it mattered.<br>
	 * <b>Why setIntentionIdle.</b> {@code setTarget(null)} on its own leaves the attack intention standing, so
	 * the bot keeps facing and walking to the body. {@code setIntentionIdle} is the engine own way to clear the
	 * attack target, the cast target, the follow and the movement in one call.
	 * @param controlled the bots of that orchestrator only
	 */
	private static void releaseDeadTargets(List<Player> controlled)
	{
		if (controlled == null)
		{
			return;
		}
		
		for (Player bot : controlled)
		{
			if ((bot == null) || bot.isDead())
			{
				continue;
			}
			
			final WorldObject target = bot.getTarget();
			if (target == null)
			{
				continue;
			}

			// <b>The .play Spoil switch decides what happens to the body.</b> OFF means leave it right away:
			// the target and the intention are dropped together, whatever held the character there (the
			// native sweep branch included), so Formation can take the bot home on this very cycle. A cast
			// in progress is never interrupted: the release waits for the next pass.<br>
			if (!target.isCreature() || !target.asCreature().isAlikeDead())
			{
				continue;
			}
			
			if (!bot.getVariables().getBoolean(BOT_SPOIL_VARIABLE, true) && !bot.isCastingNow() && !bot.isCastingSimultaneouslyNow())
			{
				bot.setTarget(null);
				bot.getAI().setIntentionIdle();
				continue;
			}
			
			// ON (default): the pre-existing behaviour, unchanged. <b>Why the target is kept.</b> The body is
			// the loot: dropping it would throw away the corpse the bot just killed, and that is what makes
			// it walk away. Only the ATTACK intention has to go, and setIntentionIdle is the engine own call
			// for exactly that: it clears the attack target, the cast target, the follow and the movement
			// without touching the world target.<br>
			// <b>Why this only hits warriors.</b> The engine never puts a mage on Intention.ATTACK: it only
			// walks it into range and lets AutoUse cast. A fighter, on the other hand, is left on ATTACK
			// against the body, because clearing the target is all the engine does and nothing ever clears
			// that intention. A warrior therefore stands glued to the corpse until it despawns, while a mage
			// never does.<br>
			// <b>Why there is no other guard here.</b> A bot that is really fighting holds Intention.ATTACK
			// on a live monster, and that case never reaches here (the dead check above already skipped it).
			// A warrior still walking towards a body is exactly the state to clear, so it must not be
			// excluded.
			if (bot.getAI().getIntention() == Intention.ATTACK)
			{
				// <b>Once, and only once.</b> The condition already requires the ATTACK intention, and
				// setIntentionIdle drops it, so this can fire a single time per corpse. That matters: the engine
				// runs on its own 700 ms loop and re-orders things every pass, so a layer that keeps correcting
				// it would be fighting the engine forever. Cancelling the target by hand works because it ends
				// the situation; this ends it the same way, only the intention instead of the target.
				bot.getAI().setIntentionIdle();
			}
		}
	}
	
	/**
	 * Party AutoPlay switch. It drives <b>only</b> the native AutoPlay of the controlled players, in both
	 * directions, so this manager stays the only owner of the automatic combat decision.<br>
	 * <b>What it never touches.</b> The orchestrator itself, AutoUse, AutoPotion, the AutoBuffs and AutoItems
	 * lists, Assist, Formation and Skills. Stopping AutoPlay here leaves AutoUse and AutoPotion registered
	 * and configured exactly as they were, so a player that stops fighting on its own still keeps drinking potions
	 * and using its configured items.<br>
	 * <b>Why the task manager and not the raw flag.</b> {@code startAutoPlay} and {@code stopAutoPlay} both set
	 * {@code isAutoPlaying()} and add or remove the player from the pool that actually runs the loop. Writing
	 * {@code setAutoPlaying(true)} alone would leave the flag on with no engine behind it, which reads as enabled
	 * and does nothing at all.
	 * @param orchestrator the connected player that owns the group
	 * @return true when the group was not empty and the state was applied
	 */
	private static boolean togglePartyAutoPlay(Player orchestrator)
	{
		final int orchestratorId = orchestrator.getObjectId();
		
		// The group state is flipped, never derived from the bots: an individual flag is a consequence of this
		// state, not its source. An empty group is not a reason to refuse, because the state must still be stored
		// so the bots added later by ALL or by an adoption already start with it.
		final boolean enable = !PARTY_AUTOPLAY_ORCHESTRATORS.contains(orchestratorId);
		if (enable)
		{
			PARTY_AUTOPLAY_ORCHESTRATORS.add(orchestratorId);
		}
		else
		{
			PARTY_AUTOPLAY_ORCHESTRATORS.remove(orchestratorId);
		}
		
		final Party party = orchestrator.getParty();
		if (party != null)
		{
			// The orchestrator is never part of the loop: it keeps its own individual START/STOP.
			for (Player player : collectControlledBots(orchestrator, party))
			{
				if (player.isAutoPlaying() == enable)
				{
					continue;
				}
				
				if (enable)
				{
					AutoPlayTaskManager.getInstance().startAutoPlay(player);
				}
				else
				{
					AutoPlayTaskManager.getInstance().stopAutoPlay(player);
				}
			}
		}
		
		orchestrator.sendMessage("[BotManager] Party AutoPlay " + (enable ? "ON" : "OFF") + ".");
		return true;
	}
	
	private static void registerBot(Player bot, Player orchestrator)
	{
		final int charId = bot.getObjectId();
		BOT_REGISTRY.add(charId);
		BOT_SESSIONS.put(charId, System.currentTimeMillis());
		
		// The owner is what keeps several accounts isolated during the periodic task.
		BOT_OWNERS.computeIfAbsent(orchestrator.getObjectId(), key -> ConcurrentHashMap.newKeySet()).add(charId);
		
		// The periodic task only visits registered orchestrators, so an orchestrator that has no entry yet must
		// be registered as soon as it uses the panel, otherwise Assist would never be visited again.
		ensureOrchestratorRegistration(orchestrator);

		// Scheduled once per manager lifetime, no matter how many bots are registered.
		if (TARGET_SYNC_STARTED.compareAndSet(false, true))
		{
			ThreadPool.schedulePriorityTaskAtFixedRate(TARGET_SYNC_TASK, TARGET_SYNC_PERIOD, TARGET_SYNC_PERIOD);
		}
	}
	
	private static void untrackBot(int charId)
	{
		BOT_REGISTRY.remove(charId);
		BOT_SESSIONS.remove(charId);
		
		for (Set<Integer> owned : BOT_OWNERS.values())
		{
			owned.remove(charId);
		}
		
		// An owner without bots is dropped, so the index never grows without bound.
		BOT_OWNERS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		
		// A character that is gone can no longer be muted, so its remembered target mode loses all meaning.
		// The persisted AutoPlay setting of that character is untouched: the value only ever lived in memory.
		OWN_TARGET_MODES.remove(charId);
	}
	
	/**
	 * @param objectId the world object identifier to test (it matches the character identifier for players)
	 * @return true when the identifier is a bot tracked by this manager
	 */
	public static boolean isBot(int objectId)
	{
		return BOT_REGISTRY.contains(objectId);
	}
	
	/**
	 * @return a copy of the tracked bot identifiers
	 */
	public static Set<Integer> getRegisteredBotObjectIds()
	{
		return new HashSet<>(BOT_REGISTRY);
	}
	
	/**
	 * Opens the native Select Skills window of one character on behalf of the player who opened the panel.<br>
	 * <b>Why not reuse AutoPlay.</b> {@code AutoPlay} reads and writes one single Player: it lists the skills of
	 * the bypass owner and it sends the window to that same owner. Here the character to edit and the character
	 * that receives the window are two different Players, so the native handler cannot be invoked without changing
	 * it. The file, the filter and the holders are the native ones; only the reader and the receiver differ.<br>
	 * <b>Deliberately not done.</b> {@code AutoPlay}, {@code Skills.htm} and the native listeners are never
	 * modified, and the configuration is written on the target holders, so the native AutoPlay task and the native
	 * login restore keep reading exactly what they always read.
	 * @param viewer the player that receives the window
	 * @param parts the bypass tokens: {@code skills}, target charId, then the optional page and skill identifier
	 * @return true when the window was delivered
	 */
	private static boolean openSkillsWindow(Player viewer, String[] parts)
	{
		if (!AutoPlayConfig.ENABLE_AUTO_SKILL)
		{
			return false;
		}
		
		final Player target = resolveSkillTarget(viewer, (parts.length > 1) ? parts[1] : null);
		if (target == null)
		{
			return false;
		}
		
		final String pageToken = (parts.length > 2) ? parts[2] : "1";
		final String skillToken = (parts.length > 3) ? parts[3] : null;
		
		final List<Skill> skills = collectAutoPlaySkills(target);
		toggleAutoPlaySkill(target, skills, skillToken);
		
		final int max = Math.max(1, HtmlUtil.countPageNumber(skills.size(), SKILL_PAGE_LIMIT));
		int page = StringUtil.isNumeric(pageToken) ? Integer.parseInt(pageToken) : 1;
		page = Math.min(Math.max(page, 1), max);
		
		final List<Skill> pageSkills = skills.subList(Math.max(0, (page - 1) * SKILL_PAGE_LIMIT), Math.min(page * SKILL_PAGE_LIMIT, skills.size()));
		
		final NpcHtmlMessage html = new NpcHtmlMessage();
		// Skills.htm is never modified: its Back button is rewritten here, at runtime, only for the window this
		// manager delivers. It returns to the Bot Manager panel, because there is no per character panel state to
		// rebuild. The plain .play window keeps its own Back and is untouched.
		final String htm = HtmCache.getInstance()
			.getHtm(viewer, "data/html/mods/AutoPlay/Skills.htm")
			.replace("bypass voice .play\" value=\"Back\"", "bypass -h " + BYPASS_ROOT + "\" value=\"Back\"")
			.replace("%skills%", buildSkillRows(target, skills.size(), pageSkills, page));
		html.setHtml(htm);
		// Only the viewer ever receives the window: a clientless bot would drop the packet anyway.
		viewer.sendPacket(html);
		return true;
	}
	
	/**
	 * Mirrors the native AutoPlay (.play) window of one character onto the viewer, exactly like
	 * {@link #openSkillsWindow} does for Select Skills: the file and the holders are the native ones; only
	 * the reader and the receiver differ. Every bypass of the delivered copy is rewritten from
	 * {@code voice .play ...} to this manager, so each checkbox acts on the target character and never on
	 * the player that clicked it. The native file itself is never modified.<br>
	 * <b>Isolation.</b> The target goes through {@link #resolveSkillTarget}: same account and same party,
	 * never GM status, so another account stays unreachable even by forging the charId.<br>
	 * <b>v1 limits.</b> The window is deliberately minimal: Select Skills lives on the [Skills] button of
	 * the slot row, and Select Supply Items and Select Healing Potion keep needing their own mirrored
	 * lists, so the plain .play of the connected character still offers them.
	 * @param viewer the player that receives the window
	 * @param parts the bypass tokens: {@code play}, then the target charId
	 * @return true when the window was delivered
	 */
	private static boolean openPlayWindow(Player viewer, String[] parts)
	{
		if (!AutoPlayConfig.ENABLE_AUTO_PLAY)
		{
			return false;
		}
		
		final Player target = resolveSkillTarget(viewer, (parts.length > 1) ? parts[1] : null);
		if (target == null)
		{
			return false;
		}
		
		final String htm = HtmCache.getInstance().getHtm(viewer, "data/html/mods/AutoPlay/Main.htm");
		if (htm == null)
		{
			return false;
		}
		
		// Every native action of the delivered copy addresses the target charId through this manager.
		final String actionBase = "bypass -h " + BYPASS_ROOT + ' ' + PLAYACT_ARGUMENT + ' ' + target.getObjectId() + ' ';
		String out = htm.replace("bypass voice .play ", actionBase);
		
		// The native .play has no Back: this mirror replaces the panel, so one is injected right here, while
		// the file still holds its single </center>. Injecting after %status_button% would also hit that
		// button's own </center> and render a second Back below it.
		out = out.replace("</center>", "<br><button action=\"bypass -h " + BYPASS_ROOT + "\" value=\"Back\" width=80 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center>");
		
		// Native placeholders, filled with the state of the target character, never with the viewer's.
		out = out.replace("%attack%", target.getAutoUseSettings().getAutoActions().contains(AUTO_ATTACK_ACTION) ? "L2UI.CheckBox_checked" : "L2UI.CheckBox");
		out = out.replace("%loot%", target.getAutoPlaySettings().doPickup() ? "L2UI.CheckBox_checked" : "L2UI.CheckBox");
		out = out.replace("%respect%", target.getAutoPlaySettings().isRespectfulHunting() ? "L2UI.CheckBox_checked" : "L2UI.CheckBox");
		out = out.replace("%range%", !target.getAutoPlaySettings().isShortRange() ? "L2UI.CheckBox_checked" : "L2UI.CheckBox");
		out = out.replace("%mode0%", target.getAutoPlaySettings().getNextTargetMode() == 0 ? "L2UI_CH3.radiobutton2" : "L2UI_CH3.radiobutton1");
		out = out.replace("%mode1%", target.getAutoPlaySettings().getNextTargetMode() == 1 ? "L2UI_CH3.radiobutton2" : "L2UI_CH3.radiobutton1");
		out = out.replace("%mode2%", target.getAutoPlaySettings().getNextTargetMode() == 2 ? "L2UI_CH3.radiobutton2" : "L2UI_CH3.radiobutton1");
		out = out.replace("%mode3%", target.getAutoPlaySettings().getNextTargetMode() == 3 ? "L2UI_CH3.radiobutton2" : "L2UI_CH3.radiobutton1");
		out = out.replace("%mode4%", target.getAutoPlaySettings().getNextTargetMode() == 4 ? "L2UI_CH3.radiobutton2" : "L2UI_CH3.radiobutton1");
		out = out.replace("%spoil_row%", "<tr><td height=10>Spoil</td><td width=10 background=\"" + (target.getVariables().getBoolean(BOT_SPOIL_VARIABLE, true) ? "L2UI.CheckBox_checked" : "L2UI.CheckBox") + "\"><button action=\"" + actionBase + "spo\" value=\" \" width=10 height=10 back=\"\" fore=\"\"></td></tr>");
		out = out.replace("%fightback_row%", "<tr><td height=10>Fight Back</td><td width=10 background=\"" + (target.getVariables().getBoolean(BOT_FIGHTBACK_VARIABLE, false) ? "L2UI.CheckBox_checked" : "L2UI.CheckBox") + "\"><button action=\"" + actionBase + "fightback\" value=\" \" width=10 height=10 back=\"\" fore=\"\"></td></tr>");
		out = out.replace("%tsearch_row%", "<tr><td height=10>Target Search</td><td width=10 background=\"" + (target.getVariables().getBoolean(BOT_TSEARCH_VARIABLE, true) ? "L2UI.CheckBox_checked" : "L2UI.CheckBox") + "\"><button action=\"" + actionBase + "tsearch\" value=\" \" width=10 height=10 back=\"\" fore=\"\"></td></tr>");
		// Minimal mirror, exactly the native Settings + Target Mode blocks; Skills opens its native window.
		// The two native list windows stay native (v1: not mirrored).
		// Skills como boton "Select Skill", igual que el .play nativo del orquestador:
		// abre la ventana nativa de skills de ESE personaje via el handler existente (bot skills).
		// No hay lista inline en el espejo; el resto de placeholders queda intacto.
		out = out.replace("%skill_button%", "<br><table width=295><tr><td height=31><center><button action=\"bypass -h " + BYPASS_ROOT + " " + SKILLS_ARGUMENT + " " + target.getObjectId() + "\" value=\"Select Skill\" width=200 height=31 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center></td></tr></table>");


		out = out.replace("%item_button%", "");
		// HP Percent + Apply, same shape as the native potion row, acting on the target character. Shown
		// unconditionally: this mirror is the .play the panel offers, so the row is always available.
		out = out.replace("%potion_button%", "<br><table width=150><tr><td width=120><font color=\"CDB67F\">HP Percent (" + target.getAutoPlaySettings().getAutoPotionPercent() + ")</font></td><td><edit var=\"percentbox\" width=30 height=15></td><td><button value=\"Apply\" action=\"" + actionBase + "percent $percentbox\" width=45 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr></table>");
		out = out.replace("%status_button%", target.isAutoPlaying()
			? "<table width=295><tr><td height=31><center><button action=\"" + actionBase + "stop\" value=\"Stop\" width=200 height=31 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center></td></tr></table>"
			: "<table width=295><tr><td height=31><center><button action=\"" + actionBase + "start\" value=\"Start\" width=200 height=31 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center></td></tr></table>");
		
		final NpcHtmlMessage html = new NpcHtmlMessage();
		html.setHtml(out);
		// Only the viewer ever receives the window: a clientless bot would drop the packet anyway.
		viewer.sendPacket(html);
		return true;
	}
	
	/**
	 * Applies one action of the mirrored .play window to the target character. Every branch mirrors the
	 * native AutoPlay command it comes from, only against the target holder instead of the acting player,
	 * and the native configuration is persisted immediately afterwards because a clientless bot has no
	 * logout to rely on.
	 * @param viewer the player that clicked the mirrored window
	 * @param parts the bypass tokens: {@code playact}, target charId, action, optional value
	 */
	private static void applyPlayAction(Player viewer, String[] parts)
	{
		if (parts.length < 3)
		{
			return;
		}
		
		final Player target = resolveSkillTarget(viewer, parts[1]);
		if (target == null)
		{
			return;
		}
		
		final String action = parts[2];
		if ("attack".equalsIgnoreCase(action))
		{
			// AUTO_ATTACK_ACTION is declared Integer, so remove() drops the value and never a list index.
			if (target.getAutoUseSettings().getAutoActions().contains(AUTO_ATTACK_ACTION))
			{
				target.getAutoUseSettings().getAutoActions().remove(AUTO_ATTACK_ACTION);
			}
			else
			{
				target.getAutoUseSettings().getAutoActions().add(AUTO_ATTACK_ACTION);
			}
		}
		else if ("loot".equalsIgnoreCase(action))
		{
			target.getAutoPlaySettings().setPickup(!target.getAutoPlaySettings().doPickup());
		}
		else if ("respect".equalsIgnoreCase(action))
		{
			target.getAutoPlaySettings().setRespectfulHunting(!target.getAutoPlaySettings().isRespectfulHunting());
		}
		else if ("range".equalsIgnoreCase(action))
		{
			target.getAutoPlaySettings().setShortRange(!target.getAutoPlaySettings().isShortRange());
		}
		else if ("spo".equalsIgnoreCase(action))
		{
			// Pure policy flag: no skill list is read or written, so the switch works for every character.
			final boolean hold = target.getVariables().getBoolean(BOT_SPOIL_VARIABLE, true);
			target.getVariables().set(BOT_SPOIL_VARIABLE, !hold);
		}
		else if ("fightback".equalsIgnoreCase(action))
		{
			final boolean on = target.getVariables().getBoolean(BOT_FIGHTBACK_VARIABLE, false);
			target.getVariables().set(BOT_FIGHTBACK_VARIABLE, !on);
		}
		else if ("tsearch".equalsIgnoreCase(action))
		{
			final boolean on = target.getVariables().getBoolean(BOT_TSEARCH_VARIABLE, true);
			target.getVariables().set(BOT_TSEARCH_VARIABLE, !on);
		}
		else if (action.matches("mode[0-4]"))
		{
			target.getAutoPlaySettings().setNextTargetMode(Integer.parseInt(action.substring(4)));
		}
		else if ("percent".equalsIgnoreCase(action))
		{
			if ((parts.length > 3) && StringUtil.isNumeric(parts[3]))
			{
				target.getAutoPlaySettings().setAutoPotionPercent(Math.max(0, Math.min(100, Integer.parseInt(parts[3]))));
			}
		}
		else if ("start".equalsIgnoreCase(action))
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(target);
			AutoUseTaskManager.getInstance().startAutoUseTask(target);
		}
		else if ("stop".equalsIgnoreCase(action))
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(target);
			AutoUseTaskManager.getInstance().stopAutoUseTask(target);
		}
		else
		{
			return;
		}
		
		persistAutoPlayState(target);
	}
	
	/**
	 * Mirrors the native logout persistence of {@code AutoPlay} for a character that cannot log out: the
	 * four lists, the potion item and the seven AUTO_USE_SETTINGS positions are written to
	 * {@code character_variables} right after every change, so the native login listener replays exactly
	 * what the player just configured. Position [1] stores the running flag like the native listener does.
	 * @param target the character whose native configuration is made durable
	 */
	private static void persistAutoPlayState(Player target)
	{
		target.getVariables().setIntegerList(PlayerVariables.AUTO_USE_ACTIONS, new ArrayList<>(target.getAutoUseSettings().getAutoActions()));
		target.getVariables().setIntegerList(PlayerVariables.AUTO_USE_BUFFS, new ArrayList<>(target.getAutoUseSettings().getAutoBuffs()));
		target.getVariables().setIntegerList(PlayerVariables.AUTO_USE_SKILLS, new ArrayList<>(target.getAutoUseSettings().getAutoSkills()));
		target.getVariables().setIntegerList(PlayerVariables.AUTO_USE_ITEMS, new ArrayList<>(target.getAutoUseSettings().getAutoSupplyItems()));
		target.getVariables().set(PlayerVariables.AUTO_USE_POTION, target.getAutoUseSettings().getAutoPotionItem());
		
		final List<Integer> settings = new ArrayList<>(7);
		settings.add(0, target.getAutoPlaySettings().getOptions());
		settings.add(1, target.isAutoPlaying() ? 1 : 0);
		settings.add(2, target.getAutoPlaySettings().doPickup() ? 1 : 0);
		settings.add(3, target.getAutoPlaySettings().getNextTargetMode());
		settings.add(4, target.getAutoPlaySettings().isShortRange() ? 1 : 0);
		settings.add(5, target.getAutoPlaySettings().getAutoPotionPercent());
		settings.add(6, target.getAutoPlaySettings().isRespectfulHunting() ? 1 : 0);
		target.getVariables().setIntegerList(PlayerVariables.AUTO_USE_SETTINGS, settings);
	}
	
	/**
	 * Enforces the .play Spoil switch of the orchestrator himself on his own dead target. He is a normal
	 * player: when his flag asks for it, the corpse target is dropped right away so he never stays glued
	 * to a body. Only the target is cleared, never the intention, so the movement of a manually played
	 * character is never interrupted, and a cast in progress is left alone as well.
	 * @param orchestrator the connected player using the panel
	 */
	private static void releaseOwnCorpse(Player orchestrator)
	{
		if (orchestrator.getVariables().getBoolean(BOT_SPOIL_VARIABLE, true))
		{
			return;
		}
		
		final WorldObject target = orchestrator.getTarget();
		if ((target == null) || !target.isCreature() || !target.asCreature().isAlikeDead())
		{
			return;
		}
		
		// BOTAI: a dead playable is a resurrection pick held by hand, not a spoil corpse: the Spoil
		// switch only governs monster bodies, so a party member stays selected for the manual res
		// no matter whether AutoPlay is running or not.
		if (target.isPlayable())
		{
			return;
		}
		
		if (orchestrator.isCastingNow() || orchestrator.isCastingSimultaneouslyNow())
		{
			return;
		}
		
		orchestrator.setTarget(null);
	}
	
	/**
	 * Isolation of the skill window: the target must be in the world, of the very same account and inside the
	 * party of the player that opened the panel. The identity is the stable account identity, never GM status and
	 * never the party leadership, so another account is never reachable, not even by forging the charId.<br>
	 * <b>Why the orchestrator itself is a valid target.</b> The window button is the very same one for every
	 * character, so the connected one addresses itself exactly like it addresses a bot. No extra rule is involved:
	 * the same account and the same party already hold for it.
	 * @param viewer the player that opened the panel
	 * @param rawCharId the target identifier as received from the bypass
	 * @return the target, or {@code null} when it is not reachable from here
	 */
	private static Player resolveSkillTarget(Player viewer, String rawCharId)
	{
		if (!StringUtil.isNumeric(rawCharId))
		{
			return null;
		}
		
		final Player target = World.getPlayer(Integer.parseInt(rawCharId));
		if (target == null)
		{
			return null;
		}
		
		// The orchestrator addresses itself without any party: it is always its own reachable target, and a
		// player alone in the world still has to be able to open its own skill page.
		if (target == viewer)
		{
			return target;
		}
		
		// Any other character must be of the very same account and inside the very same party. The identity is
		// the stable account identity, never GM status and never the party leadership, so another account is
		// never reachable, not even by forging the charId.
		if (!target.getAccountNamePlayer().equals(viewer.getAccountNamePlayer()))
		{
			return null;
		}
		
		final Party party = viewer.getParty();
		return ((party != null) && party.getMembers().contains(target)) ? target : null;
	}
	
	/**
	 * Reproduces the skill list of the native {@code AutoPlay} {@code playskills} branch, in the same order and
	 * with the same rules: own skills, then summon and pet skills, minus passive and toggle skills, minus the
	 * disabled ones, minus the item, enchant option and augmentation activation skills. The duplicated part is the
	 * filter only; the configuration itself is always the native holder content.
	 * @param target the character whose skills are listed
	 * @return the candidate skills, in native order
	 */
	private static List<Skill> collectAutoPlaySkills(Player target)
	{
		final List<Skill> skills = new ArrayList<>();
		
		final Set<Skill> siegeSkills = new HashSet<>();
		final Skill[] siegeSkillArray = SkillData.getInstance().getSiegeSkills(true, true);
		if (siegeSkillArray != null)
		{
			for (Skill siegeSkill : siegeSkillArray)
			{
				if (siegeSkill != null)
				{
					siegeSkills.add(siegeSkill);
				}
			}
		}
		
		for (Skill skill : target.getAllSkills())
		{
			if (!siegeSkills.contains(skill) && isAutoPlayable(skill) && !skills.contains(skill))
			{
				skills.add(skill);
			}
		}
		
		if (target.hasServitor() || target.hasPet())
		{
			final Summon summon = target.getSummon();
			for (Skill skill : summon.getAllSkills())
			{
				if (isAutoPlayable(skill) && !skills.contains(skill))
				{
					skills.add(skill);
				}
			}
			
			for (Skill skill : PetSkillData.getInstance().getKnownSkills(summon))
			{
				if (isAutoPlayable(skill) && !skills.contains(skill))
				{
					skills.add(skill);
				}
			}
		}
		
		for (Item item : target.getInventory().getPaperdollItems())
		{
			final ItemTemplate template = item.getTemplate();
			if (template.hasSkills())
			{
				for (SkillHolder holder : template.getSkills())
				{
					removeSkill(skills, holder.getSkill());
				}
			}
			
			if (item.getEnchantOptions() != Item.DEFAULT_ENCHANT_OPTIONS)
			{
				for (int optionId : item.getEnchantOptions())
				{
					removeActivationSkills(skills, OptionData.getInstance().getOptions(optionId));
				}
			}
			
			if (item.isAugmented())
			{
				removeActivationSkills(skills, OptionData.getInstance().getOptions(item.getAugmentation().getAugmentationId()));
			}
		}
		
		return skills;
	}
	
	/**
	 * @param skill the skill to test
	 * @return true when the native AutoPlay accepts the skill at all
	 */
	private static boolean isAutoPlayable(Skill skill)
	{
		return !skill.isPassive() && !skill.isToggle() && !AutoPlayConfig.DISABLED_AUTO_SKILLS.contains(skill.getId());
	}
	
	/**
	 * Drops the skills granted by an option, which the native branch never lets AutoPlay use.
	 * @param skills the list being built
	 * @param options the options to inspect, may be null
	 */
	private static void removeActivationSkills(List<Skill> skills, Options options)
	{
		if ((options == null) || !options.hasActivationSkills())
		{
			return;
		}
		
		for (OptionSkillHolder holder : options.getActivationSkills())
		{
			removeSkill(skills, holder.getSkill());
		}
	}
	
	/**
	 * @param skills the list being built
	 * @param skill the skill to drop, ignored when null
	 */
	private static void removeSkill(List<Skill> skills, Skill skill)
	{
		if (skill != null)
		{
			skills.remove(skill);
		}
	}
	
	/**
	 * Applies one skill selection on the target holders and persists it immediately.<br>
	 * <b>Why persist here.</b> The native handler only writes the runtime holders and relies on its own
	 * {@code ON_PLAYER_LOGOUT} listener to store the lists. A clientless bot never logs out, so that listener never
	 * runs for it and the choice would be lost when the bot is destroyed. Writing the two native variables right
	 * away gives the clientless character the same durability a connected one has: the next normal login restores
	 * them through the existing native listener.<br>
	 * <b>Deliberately not done.</b> No SQL is written here and {@code ON_PLAYER_LOGOUT} is not touched; the native
	 * mechanism is reused as is, so a later logout simply stores the very same content again.
	 * @param target the character being configured
	 * @param skills the current candidate list, used to reject a skill that is no longer offered
	 * @param skillToken the selected skill identifier, or null when the window is only being opened
	 */
	private static void toggleAutoPlaySkill(Player target, List<Skill> skills, String skillToken)
	{
		if (!StringUtil.isNumeric(skillToken))
		{
			return;
		}
		
		final int skillId = Integer.parseInt(skillToken);
		Skill knownSkill = target.getKnownSkill(skillId);
		if ((knownSkill == null) && (target.hasServitor() || target.hasPet()))
		{
			final Summon summon = target.getSummon();
			knownSkill = summon.getKnownSkill(skillId);
			if (knownSkill == null)
			{
				knownSkill = PetSkillData.getInstance().getKnownSkill(summon, skillId);
			}
		}
		
		if (AutoPlayConfig.ENABLE_AUTO_SKILL && (knownSkill != null) && skills.contains(knownSkill))
		{
			final java.util.Collection<Integer> holder = knownSkill.hasNegativeEffect() ? target.getAutoUseSettings().getAutoSkills() : target.getAutoUseSettings().getAutoBuffs();
			if (!holder.remove(skillId))
			{
				holder.add(skillId);
			}
			
			// Immediate durability: a clientless bot has no logout to rely on.
			target.getVariables().setIntegerList(PlayerVariables.AUTO_USE_SKILLS, new ArrayList<>(target.getAutoUseSettings().getAutoSkills()));
			target.getVariables().setIntegerList(PlayerVariables.AUTO_USE_BUFFS, new ArrayList<>(target.getAutoUseSettings().getAutoBuffs()));
		}
	}
	
	/**
	 * Builds the skill rows, mirroring the native markup. Every row keeps the target charId, so a later click
	 * still addresses the same character, and the window is only ever delivered to the viewer.
	 * @param target the character being configured
	 * @param totalSkills the total number of candidate skills, used to size the page footer
	 * @param pageSkills the skills of the current page
	 * @param page the current page number
	 * @return the html of the skill rows and the page footer
	 */
	private static String buildSkillRows(Player target, int totalSkills, List<Skill> pageSkills, int page)
	{
		final StringBuilder sb = new StringBuilder(1024);
		
		if (pageSkills.isEmpty())
		{
			return "<center><br>No skills found.<br></center>";
		}
		
		final int max = Math.max(1, HtmlUtil.countPageNumber(totalSkills, SKILL_PAGE_LIMIT));
		int row = 0;
		for (Skill skill : pageSkills)
		{
			sb.append(((row % 2) == 0) ? "<table width=\"295\" bgcolor=\"000000\"><tr>" : "<table width=\"295\"><tr>");
			
			final boolean selected = target.getAutoUseSettings().getAutoBuffs().contains(skill.getId()) || target.getAutoUseSettings().getAutoSkills().contains(skill.getId());
			sb.append("<td height=40 width=40><img src=\"").append(skill.getIcon()).append("\" width=32 height=32></td><td width=190>");
			if (selected)
			{
				sb.append(skill.getName());
			}
			else
			{
				sb.append("<font color=\"B09878\">").append(skill.getName()).append("</font>");
			}
			sb.append("</td><td><button value=\" \" action=\"bypass -h ").append(BYPASS_ROOT).append(' ').append(SKILLS_ARGUMENT).append(' ').append(target.getObjectId()).append(' ').append(page).append(' ').append(skill.getId());
			sb.append("\" width=32 height=32 back=\"L2UI_CH3.mapbutton_").append(selected ? "zoomout2\" fore=\"L2UI_CH3.mapbutton_zoomout1" : "zoomin2\" fore=\"L2UI_CH3.mapbutton_zoomin1").append("\"></td>");
			sb.append("</tr></table><img src=\"L2UI.SquareGray\" width=295 height=1>");
			row++;
		}
		
		// Page footer, same shape as the native one, with the target charId on every link.
		final String pageBase = "bypass -h " + BYPASS_ROOT + ' ' + SKILLS_ARGUMENT + ' ' + target.getObjectId() + ' ';
		sb.append("<br><img src=\"L2UI.SquareGray\" width=\"295\" height=1><table width=\"100%\" bgcolor=000000><tr>");
		if (page > 1)
		{
			sb.append("<td align=left width=70><a action=\"").append(pageBase).append(page - 1).append("\"><font color=\"CDB67F\">Previous</font></a></td>");
		}
		else
		{
			sb.append("<td align=left width=70><font color=\"B09878\">Previous</font></td>");
		}
		sb.append("<td align=center width=100>Page ").append(page).append(" of ").append(max).append("</td>");
		if (page < max)
		{
			sb.append("<td align=right width=70><a action=\"").append(pageBase).append(page + 1).append("\"><font color=\"CDB67F\">Next</font></a></td>");
		}
		else
		{
			sb.append("<td align=right width=70><font color=\"B09878\">Next</font></td>");
		}
		sb.append("</tr></table><img src=\"L2UI.SquareGray\" width=\"295\" height=1>");
		return sb.toString();
	}
}
