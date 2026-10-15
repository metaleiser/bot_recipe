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
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.taskmanagers;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.item.Weapon;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.ConnectionState;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * @author Mobius
 */
public class AutoPlayTaskManager
{
	private static final Set<Set<Player>> POOLS = ConcurrentHashMap.newKeySet();
	private static final Map<Player, Integer> IDLE_COUNT = new ConcurrentHashMap<>();
	private static final int POOL_SIZE = 200;
	private static final int TASK_DELAY = 700;
	private static final Integer AUTO_ATTACK_ACTION = 2;
	/**
	 * Players that mirror the leader target but never follow it.<br>
	 * <b>Why.</b> The assist branch below is an {@code if/else}: with a leader target the player mirrors it,
	 * and with an empty leader the very same branch calls {@code setIntentionFollow(leader)}. That couples two
	 * independent decisions into one switch, so a group that only wants to share a target is forced to walk
	 * behind the leader as well. A player in this set keeps the mirroring half and loses the follow half, and
	 * nothing else in the engine changes.<br>
	 * <b>Why a per player set.</b> The flag used to be the global {@code ENABLE_AUTO_ASSIST}, which decides for
	 * every connected player at once. A set keyed by object id keeps the decision inside the account that made
	 * it and leaves every other character, bot or real player, on the stock behaviour.
	 */
	private static final Set<Integer> ASSIST_WITHOUT_FOLLOW = ConcurrentHashMap.newKeySet();
	
	/**
	 * Turns the implicit follow off for one player, keeping the target mirroring on.
	 * @param player the player that must mirror the leader without following it
	 */
	public static void setAssistWithoutFollow(Player player)
	{
		if (player != null)
		{
			ASSIST_WITHOUT_FOLLOW.add(player.getObjectId());
		}
	}
	
	/**
	 * Restores the stock behaviour, where an empty leader makes the player follow it.
	 * @param player the player that goes back to the stock behaviour
	 */
	public static void clearAssistWithoutFollow(Player player)
	{
		if (player != null)
		{
			ASSIST_WITHOUT_FOLLOW.remove(player.getObjectId());
		}
	}
	
	/**
	 * @param player the player to test
	 * @return true when this player mirrors the leader target but never follows the leader
	 */
	public static boolean isAssistWithoutFollow(Player player)
	{
		return (player != null) && ASSIST_WITHOUT_FOLLOW.contains(player.getObjectId());
	}
	
	protected AutoPlayTaskManager()
	{
	}
	
	private class AutoPlay implements Runnable
	{
		private final Set<Player> _players;
		
		public AutoPlay(Set<Player> players)
		{
			_players = players;
		}
		
		@Override
		public void run()
		{
			if (_players.isEmpty())
			{
				return;
			}
			
			PLAY: for (Player player : _players)
			{
				if (!player.isOnline() || (player.isInOfflineMode() && !player.isOfflinePlay()) || !AutoPlayConfig.ENABLE_AUTO_PLAY || !player.isAutoPlaying())
				{
					stopAutoPlay(player);
					continue PLAY;
				}
				
				// HANDOFF/GPF: pausa temporal del tick mientras el cliente de ESTE player
				// aun transiciona (AUTHENTICATED/ENTERING). Solo se omite este tick: no se
				// toca el pool, ni _autoPlaying, ni party, ni offlinePlay. Un bot clientless
				// (client == null) pasa sin cambios. Sin esto, AssistLeader espeja el target
				// del lider y Player.setTarget envia MyTargetSelected/ValidateLocation con un
				// actor que el cliente H5 aun no conoce -> GPF en OnMyTargetSelected.
				final GameClient transitioningClient = player.getClient();
				if ((transitioningClient != null) && (transitioningClient.getConnectionState() != ConnectionState.IN_GAME))
				{
					continue PLAY;
				}
				
				if (player.isSitting() || player.isCastingNow() || (player.getQueuedSkill() != null))
				{
					continue PLAY;
				}
				
				// Next target mode.
				final int targetMode = player.getAutoPlaySettings().getNextTargetMode();
				
				// Skip thinking.
				final WorldObject target = player.getTarget();
				if ((target != null) && target.isCreature())
				{
					final Creature creature = target.asCreature();
					if (creature.isAlikeDead() || !isTargetModeValid(targetMode, player, creature))
					{
						// Logic for Spoil (254) skill.
						if (creature.isMonster() && creature.isDead() && player.getAutoUseSettings().getAutoSkills().contains(254))
						{
							final Skill sweeper = player.getKnownSkill(42);
							if (sweeper != null)
							{
								final Monster monster = target.asMonster();
								if (monster.checkSpoilOwner(player, false))
								{
									// Move to target.
									if (player.calculateDistance2D(target) > 40)
									{
										if (!player.isMoving())
										{
											player.getAI().setIntentionMoveTo(target);
										}
										
										continue PLAY;
									}
									
									// Sweep target.
									player.doCast(sweeper);
									continue PLAY;
								}
							}
						}
						
						// BOTAI: a dead playable is a resurrection pick held by hand. The corpse target is
						// what the player clicks to ressurect, so the engine never drops it here: the pick
						// survives until the player clicks something else or the member stands up again.
						if (!creature.isPlayable())
						{
							// Clear target.
							player.setTarget(null);
						}
					}
					else if ((creature.getTarget() == player) || (creature.getTarget() == null))
					{
						// GeoEngine can see target check.
						if (!GeoEngine.getInstance().canSeeTarget(player, creature))
						{
							player.setTarget(null);
							continue PLAY;
						}
						
						// Logic adjustment for summons not attacking.
						final Summon summon = player.getSummon();
						if ((summon != null) && summon.hasAI() && !summon.isMoving() && !summon.isDisabled() && (summon.getAI().getIntention() != Intention.ATTACK) && (summon.getAI().getIntention() != Intention.CAST) && creature.isAutoAttackable(player) && GeoEngine.getInstance().canSeeTarget(player, creature))
						{
							summon.getAI().setIntentionAttack(creature);
						}
						
						// We take granted that mage classes do not auto hit.
						if (isMageCaster(player))
						{
							// A caster out of range would otherwise stand still casting at a target it never approaches.
							if ((player.calculateDistance2D(creature) > 900) && !player.isMoving() && player.hasAI() && !player.isDisabled())
							{
								player.getAI().setIntentionMoveTo(creature);
							}
							
							continue PLAY;
						}
						
						// Check if actually attacking.
						if (player.hasAI() && !player.isAttackingNow() && !player.isCastingNow() && !player.isMoving() && !player.isDisabled())
						{
							if (player.getAI().getIntention() != Intention.ATTACK)
							{
								if (creature.isAutoAttackable(player))
								{
									// GeoEngine can see target check.
									if (!GeoEngine.getInstance().canSeeTarget(player, creature))
									{
										player.setTarget(null);
										continue PLAY;
									}
									
									player.getAI().setIntentionAttack(creature);
								}
							}
							else
							{
								// Re-submitting the same attack revalidates a move to target that stalled.
								player.getAI().setIntentionAttack(creature);
								if (player.isAttackingNow() || player.isCastingNow() || player.isMoving())
								{
									IDLE_COUNT.remove(player);
									continue PLAY;
								}
								
								final Weapon weapon = player.getActiveWeaponItem();
								if (weapon != null)
								{
									final int idleCount = IDLE_COUNT.getOrDefault(player, 0);
									if (idleCount > 10)
									{
										final boolean ranged = weapon.getItemType().isRanged();
										final double angle = LocationUtil.calculateHeadingFrom(player, creature);
										final double radian = Math.toRadians(angle);
										final double course = Math.toRadians(180);
										final double distance = (ranged ? player.getCollisionRadius() : player.getCollisionRadius() + creature.getTemplate().getCollisionRadius()) * 2;
										final int x1 = (int) (Math.cos(Math.PI + radian + course) * distance);
										final int y1 = (int) (Math.sin(Math.PI + radian + course) * distance);
										final Location location;
										if (ranged)
										{
											location = new Location(player.getX() + x1, player.getY() + y1, player.getZ());
										}
										else
										{
											location = new Location(creature.getX() + x1, creature.getY() + y1, player.getZ());
										}
										
										player.getAI().setIntentionMoveTo(location);
										IDLE_COUNT.remove(player);
									}
									else
									{
										IDLE_COUNT.put(player, idleCount + 1);
									}
								}
							}
						}
						
						continue PLAY;
					}
				}
				
				// Reset idle count.
				IDLE_COUNT.remove(player);
				
				// Pickup.
				if (player.getAutoPlaySettings().doPickup() && player.isInventoryUnder90(false))
				{
					// First item we can act on: either reachable far (move to it) or reachable close + pickup-eligible.
					final Item droppedItem = World.getFirstVisibleObjectInRange(player, Item.class, 200, item ->
					{
						if (!item.isSpawned() || AutoPlayConfig.IGNORED_AUTO_PICK_ITEMS.contains(item.getId()) || !GeoEngine.getInstance().canMoveToTarget(player.getX(), player.getY(), player.getZ(), item.getX(), item.getY(), item.getZ(), player.getInstanceId()))
						{
							return false;
						}
						// Far item: we can move to it.
						if (player.calculateDistance2D(item) > 70)
						{
							return true;
						}
						// Close item: must be unprotected or owned by player.
						return !item.isProtected() || (item.getOwnerId() == player.getObjectId());
					});
					
					if (droppedItem != null)
					{
						if (player.calculateDistance2D(droppedItem) > 70)
						{
							if (!player.isMoving())
							{
								player.getAI().setIntentionMoveTo(droppedItem);
							}
							
							continue PLAY;
						}
						
						// Item is close and pickup-eligible.
						player.doPickupItem(droppedItem);
						continue PLAY; // Avoid pickup being skipped.
					}
				}
				
				// Find target.
				Creature creature = null;
				final Party party = player.getParty();
				final Player leader = party == null ? null : party.getLeader();
				// BOTAI: party fight back. When the BOT_FIGHTBACK switch is on, a monster already hitting
				// one of ours is picked first, before the stock nearest search below. It uses the very same
				// range, line of sight and mode gates, so nothing else in the hunt changes; and because the
				// assist branch below still owns the mirror, the whole party answers back at once.
				if ((party != null) && player.getVariables().getBoolean("BOT_FIGHTBACK", false))
				{
					final int fightBackRange = player.getAutoPlaySettings().isShortRange() && (targetMode != 2) ? AutoPlayConfig.AUTO_PLAY_SHORT_RANGE : AutoPlayConfig.AUTO_PLAY_LONG_RANGE;
					final Creature aggressor = World.getNearestVisibleObjectInRange(player, Creature.class, fightBackRange, nearby ->
					{
						if (nearby.isAlikeDead() || !isTargetModeValid(targetMode, player, nearby))
						{
							return false;
						}
						final WorldObject hunterTarget = nearby.getTarget();
						if ((hunterTarget == null) || (!hunterTarget.equals(player) && !(hunterTarget.isPlayable() && party.containsPlayer(hunterTarget.asPlayer())) && !(player.hasSummon() && (player.getSummon().getObjectId() == hunterTarget.getObjectId()))))
						{
							return false;
						}
						final boolean visible = (Math.abs(player.getZ() - nearby.getZ()) < 800) && GeoEngine.getInstance().canSeeTarget(player, nearby);
						if (!visible)
						{
							return false;
						}
						return nearby.isRaid() || GeoEngine.getInstance().canMoveToTarget(player.getX(), player.getY(), player.getZ(), nearby.getX(), nearby.getY(), nearby.getZ(), player.getInstanceId());
					});
					if (aggressor != null)
					{
						creature = aggressor;
					}
				}
				if (AutoPlayConfig.ENABLE_AUTO_ASSIST && (party != null) && (leader != null) && (leader != player) && !leader.isDead())
				{
					if (leader.calculateDistance3D(player) < (PlayerConfig.ALT_PARTY_RANGE * 2 /* 2? */))
					{
						final WorldObject leaderTarget = leader.getTarget();
						if ((leaderTarget != null) && (leaderTarget.isAttackable() || (leaderTarget.isPlayable() && !party.containsPlayer(leaderTarget.asPlayer()))))
						{
							creature = leaderTarget.asCreature();
						}
						// The follow half is skipped for a player that asked for mirroring only: with an empty
					// leader it simply has nothing to do here, and the stock fallback of walking towards the
					// leader is exactly what that player opted out of. The creature stays null, so the
					// targeting below falls through to the engine, which keeps skills, buffs and the mage
					// cast range working exactly as they did before.
					else if (!isAssistWithoutFollow(player) && (player.getAI().getIntention() != Intention.FOLLOW) && !player.isDisabled())
						{
							player.getAI().setIntentionFollow(leader);
						}
					}
				}
				else
				{
					creature = World.getNearestVisibleObjectInRange(player, Creature.class, player.getAutoPlaySettings().isShortRange() && (targetMode != 2 /* Characters */) ? AutoPlayConfig.AUTO_PLAY_SHORT_RANGE : AutoPlayConfig.AUTO_PLAY_LONG_RANGE, nearby ->
					{
						// Skip unavailable creatures.
						if (nearby.isAlikeDead())
						{
							return false;
						}
						
						// Check creature target.
						if (player.getAutoPlaySettings().isRespectfulHunting() && !nearby.isPlayable() && !nearby.isRaid() && (nearby.getTarget() != null) && (nearby.getTarget() != player) && !(player.hasSummon() && (player.getSummon().getObjectId() == nearby.getTarget().getObjectId())))
						{
							return false;
						}
						
						// Check next target mode.
						if (!isTargetModeValid(targetMode, player, nearby))
						{
							return false;
						}
						
												// Check if creature is reachable. A raid keeps the line of sight but skips the path check:
						// raid lairs often sit on geometry the navmesh cannot reach, and standing and shooting from here
						// is still a valid hunt. Every other creature keeps both gates unchanged.
						final boolean visible = (Math.abs(player.getZ() - nearby.getZ()) < 800) && GeoEngine.getInstance().canSeeTarget(player, nearby);
						if (!visible)
						{
							return false;
						}
						
						return nearby.isRaid() || GeoEngine.getInstance().canMoveToTarget(player.getX(), player.getY(), player.getZ(), nearby.getX(), nearby.getY(), nearby.getZ(), player.getInstanceId());
					});
				}
				
				// New target was assigned.
				if (creature != null)
				{
					// BOTAI: the held dead playable (a manual resurrection pick) outranks any acquisition,
					// assist included: the corpse stays selected until the player clicks elsewhere or the
					// member stands up. Any other held target keeps the stock flow below.
					if ((target != null) && target.isCreature() && target.isPlayable() && target.asCreature().isAlikeDead())
					{
						continue PLAY;
					}
					
					// BOTAI: manual target mode (BOT_TSEARCH off): the native assignment is skipped outright,
					// so the player picks the target by hand while every block that needs a target keeps working
					// on the held one (attack maintenance, mage approach, cast range). With the flag on, the
					// stock assignment below runs exactly as before.
					if (!player.getVariables().getBoolean("BOT_TSEARCH", true))
					{
						continue PLAY;
					}
					player.setTarget(creature);
					
					// We take granted that mage classes do not auto hit.
					if (isMageCaster(player))
					{
						// A caster out of range would otherwise stand still casting at a target it never approaches.
						if ((player.calculateDistance2D(creature) > 900) && !player.isMoving() && player.hasAI() && !player.isDisabled())
						{
							player.getAI().setIntentionMoveTo(creature);
						}
						
						continue PLAY;
					}
					
					player.getAI().setIntentionAttack(creature);
				}
			}
		}
		
		private boolean isMageCaster(Player player)
		{
			return !player.getAutoUseSettings().getAutoActions().contains(AUTO_ATTACK_ACTION);
		}
		
		private boolean isTargetModeValid(int mode, Player player, Creature creature)
		{
			if (!creature.isTargetable() || (creature.isNpc() && (creature.isInvul() || !creature.asNpc().isShowName())))
			{
				return false;
			}
			
			switch (mode)
			{
				case 1: // Monster
				{
					return creature.isMonster() && !creature.isRaid() && creature.isAutoAttackable(player);
				}
				case 2: // Characters
				{
					return creature.isPlayable() && creature.isAutoAttackable(player);
				}
				case 3: // NPC
				{
					return creature.isNpc() && !creature.isMonster() && !creature.isInsideZone(ZoneId.PEACE);
				}
				case 4: // Monster + Raidboss
				{
					// The Monster rule without the !isRaid() exclusion: raid and grand bosses are monsters
					// too, so they join the hunt. Players, non-monster NPCs and unattackable targets stay out.
					return creature.isMonster() && creature.isAutoAttackable(player);
				}
				default: // Any Target
				{
					return (creature.isNpc() && !creature.isInsideZone(ZoneId.PEACE)) || (creature.isPlayable() && creature.isAutoAttackable(player));
				}
			}
		}
	}
	
	public synchronized void startAutoPlay(Player player)
	{
		for (Set<Player> pool : POOLS)
		{
			if (pool.contains(player))
			{
				return;
			}
		}
		
		player.setAutoPlaying(true);
		
		for (Set<Player> pool : POOLS)
		{
			if (pool.size() < POOL_SIZE)
			{
				player.onActionRequest();
				pool.add(player);
				return;
			}
		}
		
		final Set<Player> pool = ConcurrentHashMap.newKeySet(POOL_SIZE);
		player.onActionRequest();
		pool.add(player);
		ThreadPool.schedulePriorityTaskAtFixedRate(new AutoPlay(pool), TASK_DELAY, TASK_DELAY);
		POOLS.add(pool);
	}
	
	public void stopAutoPlay(Player player)
	{
		for (Set<Player> pool : POOLS)
		{
			if (pool.remove(player))
			{
				player.setAutoPlaying(false);
				
				// Pets must follow their owner.
				if (player.hasServitor() || player.hasPet())
				{
					player.getSummon().followOwner();
				}
				
				IDLE_COUNT.remove(player);
				return;
			}
		}
	}
	
	public static AutoPlayTaskManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final AutoPlayTaskManager INSTANCE = new AutoPlayTaskManager();
	}
}
