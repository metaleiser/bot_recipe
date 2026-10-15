package handlers.custom.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Continuous formation for the Bot Manager window.<br>
 * The stock Formation button orders the ring exactly once, when it is pressed: the bots walk to their slot,
 * that movement ends, and afterwards they drift apart again. This layer turns that same button into a state,
 * so pressing it keeps the group together from that moment on: the ring is re-issued on every Bot Manager
 * cycle and the bots keep walking with the leader while it stays on.<br>
 * <b>Never cuts a cast.</b> A bot that is casting or attacking is left completely alone, so the group walks
 * together and still lands every skill. The engine already queues a move instead of interrupting
 * ({@code PlayerAI.setIntentionMoveTo} returns early while casting), but queuing it on every cycle would
 * replay a stale move the instant the cast ends; skipping the bot is cleaner and is what the player sees as
 * "it never interrupts me".<br>
 * <b>Movement only.</b> No target is read or written here, so the ring cannot interfere with Assist, with
 * pure assist or with the native AutoPlay target loop.
 */
public final class BotFormationMode
{
	private static final Set<Integer> HOLDING = ConcurrentHashMap.newKeySet();
	private static final int RING_RADIUS = 100;
	private static final int RECALL_DISTANCE = 40;
	/** Distance beyond which a caster counts as left behind and is brought home between casts. */
	private static final int LAGGING_DISTANCE = 120;

	private BotFormationMode()
	{
	}

	/**
	 * Starts or stops the continuous formation of one orchestrator.
	 * @param player the orchestrator
	 * @return true when the formation is now on
	 */
	public static boolean toggle(Player player)
	{
		if (player == null)
		{
			return false;
		}

		final int charId = player.getObjectId();
		if (HOLDING.contains(charId))
		{
			HOLDING.remove(charId);
			return false;
		}

		HOLDING.add(charId);
		return true;
	}

	/**
	 * @param player the orchestrator
	 * @return true when the continuous formation is on
	 */
	public static boolean isOn(Player player)
	{
		return (player != null) && HOLDING.contains(player.getObjectId());
	}

	/**
	 * Forgets an orchestrator that is gone, so a reconnection never inherits a stale switch.
	 * @param charId the orchestrator identifier
	 */
	public static void forget(int charId)
	{
		HOLDING.remove(charId);
	}

	/**
	 * Keeps the ring closed around the leader on every cycle while the switch is on.<br>
	 * The same circular order as the one shot command is reused, so the slots are identical to the ones the
	 * player already sees when pressing Formation. A move is only issued when the bot actually left its slot,
	 * which stops the bots from restarting the walk they are already doing.
	 * @param leader the character the group walks behind
	 * @param controlled the bots of that orchestrator only
	 */
	public static void maintain(Player leader, List<Player> controlled)
	{
		if ((controlled == null) || controlled.isEmpty())
		{
			return;
		}

		final List<Location> ring = ringAround(leader.getX(), leader.getY(), leader.getZ(), controlled.size());
		for (int i = 0; i < controlled.size(); i++)
		{
			final Player bot = controlled.get(i);
			if ((bot == null) || bot.isDead() || isBusy(bot, ring.get(i)))
			{
				continue;
			}

			final Location slot = ring.get(i);
			if (farFrom(bot, slot))
			{
				bot.getAI().setIntentionMoveTo(slot);
			}
		}
	}

	/**
	 * True when the bot must not be moved right now.<br>
	 * <b>Engaged first.</b> A bot holding any target is engaged and is never recalled: the ring only claims
	 * bots that are really idle, so a fight always runs to its kill before the bot walks home.<br>
	 * <b>Cast and attack.</b> A cast in progress is exactly what this guard is for: the formation yields to it.
	 * <b>Follow.</b> A bot that is following the leader is already walking towards the group, so the ring is
	 * deferred until that movement ends instead of fighting the follow task for the same destination.
	 * @param bot the bot being placed
	 * @return true when the bot has to be left alone this cycle
	 */
	private static boolean isBusy(Player bot, Location slot)
	{
		// <b>Target first, always.</b> A bot that holds a target is hunting or fighting: it left the ring on
		// purpose and walks back on its own once the kill is over. This check has to run before the cast
		// branch below: a caster with a live monster used to fall into it once past LAGGING_DISTANCE, got a
		// recall queued on every cast, and replayed the walk to the ring right after the cast ended while
		// the engine held that same target - hit, return, attack, return, forever. Nothing engaged can be
		// recalled, caster or not.
		if (bot.getTarget() != null)
		{
			return true;
		}
		
		// A caster that fell behind is not busy, it is lost. setIntentionMoveTo does not cancel a cast:
		// PlayerAI stores the move as the saved intention and replays it when the skill ends, so ordering
		// it is safe and the bot walks as soon as it can. Skipping every caster is what made a healer stay
		// behind casting while the whole group advanced. A caster already in its slot is left alone. With
		// no target in hand (the check above already caught every engaged bot) this is the pure laggard
		// rule it was designed to be.
		if (bot.isCastingNow() || bot.isCastingSimultaneouslyNow())
		{
			return distance(bot, slot) <= LAGGING_DISTANCE;
		}
		
		if (bot.isAttackingNow())
		{
			return true;
		}
		
		final Intention intention = bot.getAI().getIntention();
		if ((intention == Intention.FOLLOW) || (intention == Intention.ATTACK))
		{
			return true;
		}
		
		return bot.getAI().isFollowing();
	}

	/**
	 * True when the bot is far enough from its slot to be worth walking back. A plain coordinate distance is
	 * used on purpose: it needs no world lookup and a ring of a hundred units is all this has to resolve.
	 * @param bot the bot being placed
	 * @param slot the ring position it belongs to
	 * @return true when the bot has to walk
	 */
	private static boolean farFrom(Player bot, Location slot)
	{
		return distance(bot, slot) > RECALL_DISTANCE;
	}
	
	private static double distance(Player bot, Location slot)
	{
		final double dx = (bot.getX() - slot.getX());
		final double dy = (bot.getY() - slot.getY());
		final double dz = (bot.getZ() - slot.getZ());
		return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
	}

	private static List<Location> ringAround(int x, int y, int z, int count)
	{
		final List<Location> ring = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			final double angle = (2 * Math.PI * i) / Math.max(1, count);
			ring.add(new Location((int) (x + (RING_RADIUS * Math.cos(angle))), (int) (y + (RING_RADIUS * Math.sin(angle))), z));
		}
		return ring;
	}
}