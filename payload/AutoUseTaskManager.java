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
import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.data.xml.PetSkillData;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Playable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.instance.Guard;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.item.EtcItem;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.network.ConnectionState;

/**
 * @author Mobius
 */
public class AutoUseTaskManager
{
	private static final Set<Set<Player>> POOLS = ConcurrentHashMap.newKeySet();
	private static final int POOL_SIZE = 200;
	private static final int TASK_DELAY = 300;
	private static final int REUSE_MARGIN_TIME = 3;
	
	/**
	 * BOTAI: heal rotation, one counter per character. Every ladder below is rotated by one position on each
	 * pass, so a heal that is always off cooldown (Major Heal reuses in 1 s) cannot monopolise the turn and
	 * leave the rest of the configured heals unused.
	 */
	private static final Map<Integer, Integer> HEAL_TURNS = new ConcurrentHashMap<>();
	
	/** BOTAI: Chain Heal (1553): the chosen member plus the most injured allies around it. */
	private static final int[] HEAL_CHAIN = { 1553 };
	
	/** BOTAI: party wide heals, strongest first (Major Group Heal, Greater Group Heal, Group Heal). */
	private static final int[] HEAL_GROUP = { 1402, 1219, 1027 };
	
	/** BOTAI: heavy single target heal (Major Heal). */
	private static final int[] HEAL_HEAVY = { 1401 };
	
	/** BOTAI: medium single target heals, fastest first (Greater Battle Heal, Greater Heal). */
	private static final int[] HEAL_MEDIUM = { 1218, 1217 };
	
	/** BOTAI: light single target heals, fastest first (Battle Heal, Heal). */
	private static final int[] HEAL_LIGHT = { 1015, 1011 };
	
	/**
	 * BOTAI: gravity of the worst wound, which decides the order of the single target ladders: below 30 % the
	 * heavy heal goes first, below 60 % the medium heals go first, and above that a light heal is enough.
	 * These two numbers are internal policy, not configuration: the threshold the operator owns is still the
	 * .play HP percent, which gates every heal exactly as before.
	 */
	private static final double CRITICAL_HP_PERCENT = 30.0;
	private static final double MODERATE_HP_PERCENT = 60.0;
	
	protected AutoUseTaskManager()
	{
	}
	
	private class AutoUse implements Runnable
	{
		private final Set<Player> _players;
		
		public AutoUse(Set<Player> players)
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
			
			for (Player player : _players)
			{
				if (!player.isOnline() || (player.isInOfflineMode() && !player.isOfflinePlay()))
				{
					stopAutoUseTask(player);
					continue;
				}
				
				// HANDOFF/GPF: omision temporal del tick mientras el cliente de ESTE player
				// transiciona hacia IN_GAME. Es un guard SEPARADO del bloque anterior a
				// proposito: ese bloque llama a stopAutoUseTask() y aqui solo queremos
				// saltar este tick (sin sacar del pool ni apagar nada), igual que el gate
				// del AutoPlay. Evita setTarget(savedTarget/member) y casteos
				// (PlayerAI.doCast) referenciando actores que el cliente aun no conoce.
				if ((player.getClient() != null) && (player.getClient().getConnectionState() != ConnectionState.IN_GAME))
				{
					continue;
				}
				
				if (player.isSitting() || player.isStunned() || player.isSleeping() || player.isParalyzed() || player.isControlBlocked() || player.isAlikeDead() || player.isMounted() || (player.isTransformed() && player.getTransformation().isRiding()))
				{
					continue;
				}
				
				final boolean isInPeaceZone = player.isInsideZone(ZoneId.PEACE);
				
				if (AutoPlayConfig.ENABLE_AUTO_ITEM && !isInPeaceZone)
				{
					ITEMS: for (Integer itemId : player.getAutoUseSettings().getAutoSupplyItems())
					{
						if (player.isTeleporting())
						{
							break ITEMS;
						}
						
						final Item item = player.getInventory().getItemByItemId(itemId.intValue());
						if (item == null)
						{
							player.getAutoUseSettings().getAutoSupplyItems().remove(itemId);
							continue ITEMS;
						}
						
						if (item.isOlyRestrictedItem() && player.isInOlympiadMode())
						{
							player.getAutoUseSettings().getAutoSupplyItems().remove(itemId);
							continue ITEMS;
						}
						
						final ItemTemplate template = item.getTemplate();
						if ((template == null) || !template.checkCondition(player, player, false))
						{
							continue ITEMS;
						}
						
						final SkillHolder[] skills = item.getTemplate().getSkills();
						if (skills != null)
						{
							ITEM_SKILL: for (SkillHolder itemSkillHolder : skills)
							{
								final Skill skill = itemSkillHolder.getSkill();
								if (!skill.isActive())
								{
									continue ITEM_SKILL;
								}
								
								if (player.isAffectedBySkill(skill.getId()) || player.hasSkillReuse(skill.getReuseHashCode()) || !skill.checkCondition(player, player, false))
								{
									continue ITEMS;
								}
							}
						}
						
						final int reuseDelay = item.getReuseDelay();
						if ((reuseDelay <= 0) || (player.getItemRemainingReuseTime(item.getObjectId()) <= 0))
						{
							final IItemHandler handler = ItemHandler.getInstance().getHandler(item.getEtcItem());
							if ((handler != null) && handler.onItemUse(player, item, false) && (reuseDelay > 0))
							{
								player.addTimeStampItem(item, reuseDelay);
							}
						}
					}
				}
				
				if (AutoPlayConfig.ENABLE_AUTO_POTION && !isInPeaceZone && (player.getCurrentHpPercent() < player.getAutoPlaySettings().getAutoPotionPercent()))
				{
					final int itemId = player.getAutoUseSettings().getAutoPotionItem();
					if (itemId > 0)
					{
						final Item item = player.getInventory().getItemByItemId(itemId);
						if (item == null)
						{
							player.getAutoUseSettings().setAutoPotionItem(0);
						}
						else
						{
							final int reuseDelay = item.getReuseDelay();
							if ((reuseDelay <= 0) || (player.getItemRemainingReuseTime(item.getObjectId()) <= 0))
							{
								final EtcItem etcItem = item.getEtcItem();
								final IItemHandler handler = ItemHandler.getInstance().getHandler(etcItem);
								if ((handler != null) && handler.onItemUse(player, item, false) && (reuseDelay > 0))
								{
									player.addTimeStampItem(item, reuseDelay);
								}
							}
						}
					}
				}
				
				if (AutoPlayConfig.ENABLE_AUTO_SKILL)
				{
					BUFFS: for (Integer skillId : player.getAutoUseSettings().getAutoBuffs())
					{
						// Fixes start area issue.
						if (isInPeaceZone)
						{
							break BUFFS;
						}
						
						// Already casting.
						if (player.isCastingNow())
						{
							break BUFFS;
						}
						
						
						// Player is teleporting.
						if (player.isTeleporting())
						{
							break BUFFS;
						}
						Playable pet = null;
						Skill skill = player.getKnownSkill(skillId);
						if (skill == null)
						{
							if (player.hasServitor() || player.hasPet())
							{
								final Summon summon = player.getSummon();
								skill = summon.getKnownSkill(skillId);
								if (skill == null)
								{
									skill = PetSkillData.getInstance().getKnownSkill(summon, skillId);
								}
								
								if (skill != null)
								{
									pet = summon;
								}
							}
							
							if (skill == null)
							{
								player.getAutoUseSettings().getAutoBuffs().remove(skillId);
								continue BUFFS;
							}
						}
						
						// BOTAI: party support. A heal, a dispel or a party buff is aimed at a party member instead of at
						// whatever the player currently has selected, so it can never land on a monster and can never loop
						// on the caster when nobody needs it.
					// BOTAI: party support follows AutoPlay. The stock engine gates its offensive branch on isAutoPlaying(),
					// but AutoUse keeps ticking on its own thread, so without this check the support skills would keep
					// running with AutoPlay OFF. Turning AutoPlay or Party AutoPlay on or off therefore turns every heal,
					// buff, cleanse and resurrection on or off as well.
					if (!player.isAutoPlaying())
					{
										continue BUFFS;
					}
					
					// BOTAI: out of mana. Every configured buff and skill costs mp and the engine keeps re-submitting them
					// while the condition fails, which pins the character in place instead of letting it walk. With no mana
					// there is nothing left to decide, so the whole support pass is skipped.
						if (player.getCurrentMp() <= 0)
						{
												continue BUFFS;
						}
						
						// BOTAI: a song or a dance is a party wide cast with no single receiver, so redirecting it to
						// one chosen member would silently discard it and only the engine knows how to expand it over
						// the party range. A Dancer or a Singer therefore casts through the stock path here, which is
						// also what keeps the song refreshing while the character moves with the group.
						if (isPartyWideSongOrDance(skill) && !skill.hasEffectType(EffectType.HEAL))
						{
												if (canCastBuff(player, player.getTarget(), skill))
												{
																		player.doCast(skill);
												}
												continue BUFFS;
						}

					// BOTAI: a corpse in the party outranks everything else, so a healer never spends its turn on a buff
					// while a member is lying on the floor waiting to be picked up.
					final Player corpse = findDeadPartyMember(player);
					if (corpse != null)
					{
										final Skill revival = findResurrectionSkill(player);
										// BOTAI: Resurrection gate: cast only off cooldown. While recycling the attempt is dropped here,
										// so no failed doCast spam ever happens. No break either: heals, buffs, walk and fight keep running,
										// and this branch retries every pass, firing the moment the cooldown ends.
										if ((revival != null) && !player.hasSkillReuse(revival.getReuseHashCode()))
										{
															if (castOnSupportTarget(player, corpse, revival))
															{
																break BUFFS;
															}
										}
					}
					
					// BOTAI: smart heal pass, once per tick before the generic skill loop below.
					if (countHurtMembers(player, player.getAutoPlaySettings().getAutoPotionPercent()) > 0)
					{
										for (final Skill heal : selectHealCandidates(player))
										{
															if (heal == null) continue;
															final Player wounded = selectSupportTarget(player, heal);
															if (wounded == null) continue;
															// BOTAI: real pre-cast validation: reuse (isSkillDisabled includes hasSkillReuse), MP,
															// Spirit Ore and the real skill conditions. canUseMagic is read-only (no consumption);
															// on false the next candidate is tried instead of a doCast the core would reject.
															if (!canUseMagic(player, wounded, heal))
															{
																				continue;
															}
															if (castOnSupportTarget(player, wounded, heal))
															{
																				break BUFFS;
															}
															// false: checkCondition rejected BEFORE doCast (nothing was cast): try next candidate.
										}
					}
					// HEAL skills never use the generic support path below: they are only cast
					// through the candidate loop above, at most one doCast per tick.
					if (skill.hasEffectType(EffectType.HEAL))
					{
								continue BUFFS;
					}
						final Player supportTarget = selectSupportTarget(player, skill);
						if (supportTarget != null)
						{
												castOnSupportTarget(player, supportTarget, skill);
												break BUFFS;
						}
						
						// Stock attacking guard removed on purpose: it aborted the entire buff loop, so a healer
						// could never act while AutoPlay was on, because that flag is always true while fighting.
						// Combat state now only blocks the loop once party support has had its chance.
						if (player.isAttackingNow())
						{
												break BUFFS;
						}
						
						if (isSupportSkill(skill))
						{
												// A support skill with nobody to help: do not fall back to self, that is the loop.
												continue BUFFS;
						}
						
						// Buff use check.
						final WorldObject target = player.getTarget();
						if (!canCastBuff(player, target, skill))
						{
							continue BUFFS;
						}
						
						// Playable target cast.
						final Playable caster = pet != null ? pet : player;
						if ((target != null) && (target.isPlayable()))
						{
							final Player targetPlayer = target.asPlayer();
							if (((targetPlayer.getPvpFlag() == 0) && (targetPlayer.getKarma() <= 0)) || (targetPlayer.getParty() == caster.getParty()))
							{
								caster.doCast(skill);
							}
							else
							{
								if (!caster.getEffectList().isAffectedBySkill(skill.getId()))
								{
									final WorldObject savedTarget = target;
									caster.setTarget(caster);
									caster.doCast(skill);
									caster.setTarget(savedTarget);
								}
							}
						}
						else // Target self, cast and re-target.
						{
							final WorldObject savedTarget = target;
							caster.setTarget(caster);
							caster.doCast(skill);
							caster.setTarget(savedTarget);
						}
					}
					
					// Continue when auto play is not enabled.
					if (!player.isAutoPlaying())
					{
						continue;
					}
					
					final int count = player.getAutoUseSettings().getAutoSkills().size();
					SKILLS: for (int i = 0; i < count; i++)
					{
						// Already casting.
						if (player.isCastingNow())
						{
							break SKILLS;
						}
						
						// Player is teleporting.
						if (player.isTeleporting())
						{
							break SKILLS;
						}
						
						// Acquire next skill.
						Playable pet = null;
						final WorldObject target = player.getTarget();
						final Integer skillId = player.getAutoUseSettings().getNextSkillId();
						Skill skill = player.getKnownSkill(skillId);
						if (skill == null)
						{
							if (player.hasServitor() || player.hasPet())
							{
								final Summon summon = player.getSummon();
								skill = summon.getKnownSkill(skillId);
								if (skill == null)
								{
									skill = PetSkillData.getInstance().getKnownSkill(summon, skillId);
								}
								
								if (skill != null)
								{
									pet = summon;
									pet.setTarget(target);
								}
							}
							
							if (skill == null)
							{
								player.getAutoUseSettings().getAutoSkills().remove(skillId);
								player.getAutoUseSettings().resetSkillOrder();
								break SKILLS;
							}
						}
						
						// Casting on self stops movement.
						if (target == player)
						{
							break SKILLS;
						}
						
						// Check negative effect skill target.
						if ((target == null) || target.asCreature().isDead())
						{
							// Remove queued skill.
							if (player.getQueuedSkill() != null)
							{
								player.setQueuedSkill(null, false, false);
							}
							break SKILLS;
						}
						
						// Peace zone and auto attackable checks.
						if (target.isInsideZone(ZoneId.PEACE) || !target.isAutoAttackable(player))
						{
							break SKILLS;
						}
						
						// Do not attack guards.
						if (target instanceof Guard)
						{
							final int targetMode = player.getAutoPlaySettings().getNextTargetMode();
							if ((targetMode != 3 /* NPC */) && (targetMode != 0 /* Any Target */))
							{
								break SKILLS;
							}
						}
						
						// Increment skill order.
						player.getAutoUseSettings().incrementSkillOrder();
						
						// Skill use check.
						final Playable caster = pet != null ? pet : player;
						if (!canUseMagic(caster, target, skill))
						{
							continue SKILLS;
						}
						
						// Use the skill.
						caster.useMagic(skill, true, false);
						
						break SKILLS;
					}
				}
			}
		}
		
		/**
		 * BOTAI: a support skill is any skill whose effect is meant to restore, protect or clean somebody.
		 * Those must never fall back to the stock self cast, which is what caused the endless self heal loop.
		 * @param skill the skill being processed
		 * @return true when the skill only makes sense on a chosen character
		 */
		private static boolean isSupportSkill(Skill skill)
		{
			// RESURRECTION belongs here too: without it a resurrection falls through to the stock self cast
			// whenever there is no corpse, and the engine re-submits it on every single cycle.
			return skill.hasEffectType(EffectType.HEAL, EffectType.DISPEL, EffectType.DISPEL_BY_SLOT, EffectType.RESURRECTION, EffectType.RESURRECTION_SPECIAL);
		}

		/**
		 * BOTAI: true for a self oriented buff that the stock engine only ever casts on the caster. Those
		 * are extended to the whole party. The classification is based on the target type and on the sign
		 * of the effect point, because in High Five a plain buff such as Shield, Might, Acumen or Berserker
		 * Spirit is not tagged with {@link EffectType#BUFF} at all: those use the physical attack effect
		 * type and only differ by a positive effect point.
		 * @param skill the skill being processed
		 * @return true when the skill is a positive effect meant to be cast on somebody
		 */
		private static boolean isPartyBuff(Skill skill)
		{
			if (isSupportSkill(skill))
			{
				return false;
			}

			return (skill.getTargetType() != TargetType.SELF) && (skill.getEffectPoint() > 0) && !skill.hasNegativeEffect();
		}

		/**
		 * BOTAI: picks who should receive this skill, or null when nobody needs it.
		 * <ul>
		 * <li>HEAL: the lowest HP percentage of the party, the caster included, but only while it is below
		 * the auto potion threshold, which is the very same number the potion uses.</li>
		 * <li>DISPEL: a party member carrying an abnormal status this skill can remove.</li>
		 * <li>BUFF: a party member, the caster included, that does not have the effect yet or is about to
		 * lose it. It uses the real remaining time of the effect instead of a guess.</li>
		 * </ul>
		 * @param player the caster
		 * @param skill the skill being processed
		 * @return the receiver, or null when there is no valid candidate
		 */
		private static Player selectSupportTarget(Player player, Skill skill)
		{
			// BOTAI: party support follows AutoPlay. The stock engine gates its offensive skill branch on
			// isAutoPlaying(), but AutoUse itself keeps ticking on its own thread, so without this check the
			// heal and the party buffs would keep running with AutoPlay OFF. Turning AutoPlay on or off,
			// or Party AutoPlay for the bots, now also turns the support on or off.
			if (!player.isAutoPlaying())
			{
				return null;
			}

			final boolean healing = skill.hasEffectType(EffectType.HEAL);
			final boolean buffing = !healing && !skill.hasEffectType(EffectType.DISPEL, EffectType.DISPEL_BY_SLOT) && isPartyBuff(skill);
			if (!healing && !buffing && !isSupportSkill(skill))
			{
				return null;
			}

			if (skill.getTargetType() == TargetType.SELF)
			{
				return null;
			}

			final Party party = player.getParty();
			if (party == null)
			{
				// BOTAI: without a party the caster can still look after itself. The stock engine only ever
				// self casts through the target dance, which is what caused the endless self heal loop, so the
				// self case is resolved here with exactly the same rules used for the party case.
				return needsSelfSupport(player, skill, healing, buffing) ? player : null;
			}

			// Healing ranks by HP percentage, buffs rank by urgency, so they need a separate best value.
			Player best = null;
			double bestHealPercent = 100.0;
			int bestUrgency = Integer.MAX_VALUE;

			for (Player member : party.getMembers())
			{
				// Target safety: only a living Player of the very same party, the caster included.
				if ((member == null) || member.isDead() || member.isAlikeDead())
				{
					continue;
				}

				if (healing)
				{
					final long maxHp = member.getMaxHp();
					if (maxHp <= 0)
					{
						continue;
					}

					final double hpPercent = member.getCurrentHpPercent();
					if (hpPercent >= bestHealPercent)
					{
						continue;
					}

					best = member;
					bestHealPercent = hpPercent;
				}
				else if (buffing)
				{
					// Urgency from the real effect: absent means 1000, expiring means the remaining seconds.
					final BuffInfo info = member.getEffectList().getBuffInfoBySkillId(skill.getId());
					int urgency;
					if (info == null)
					{
						urgency = 1000;
					}
					else
					{
						final int remaining = info.getTime();
						if ((remaining != -1) && (remaining > 30))
						{
							continue;
						}

						urgency = (remaining == -1) ? 0 : (30 - remaining);
					}

					if (urgency <= 0)
					{
						continue;
					}

					if (urgency >= bestUrgency)
					{
						continue;
					}

					best = member;
					bestUrgency = urgency;
				}
				else if (hasCureableAbnormal(member))
				{
					return member;
				}
			}

			if (best == null)
			{
				return null;
			}

			// Healing is gated by the HP threshold, a buff by nothing extra.
			if (healing)
			{
				return (bestHealPercent < player.getAutoPlaySettings().getAutoPotionPercent()) ? best : null;
			}

			return best;
		}

		/**
		 * BOTAI: decides whether the caster is itself in need of this skill. It applies the exact same rules
		 * that govern the party selection, so a lone healer behaves identically to one inside a group.
		 * @param player the caster
		 * @param skill the skill being processed
		 * @param healing true for a heal skill
		 * @param buffing true for a plain buff
		 * @return true when the caster should receive the skill itself
		 */
		private static boolean needsSelfSupport(Player player, Skill skill, boolean healing, boolean buffing)
		{
			if (skill.getTargetType() == TargetType.SELF)
			{
				// A skill the engine already forces onto the caster needs no decision from us.
				return false;
			}

			if (healing)
			{
				final long maxHp = player.getMaxHp();
				return (maxHp > 0) && (player.getCurrentHpPercent() < player.getAutoPlaySettings().getAutoPotionPercent());
			}

			if (buffing)
			{
				final BuffInfo info = player.getEffectList().getBuffInfoBySkillId(skill.getId());
				if (info == null)
				{
					return true;
				}

				final int remaining = info.getTime();
				return (remaining != -1) && (remaining <= 30);
			}

			return false;
		}

		/**
		 * BOTAI: the first dead member of the caster party, the one a resurrection has to be aimed at.
		 * @param player the caster
		 * @return the dead member, or null when the whole party is standing
		 */
		private static Player findDeadPartyMember(Player player)
		{
			final Party party = player.getParty();
			if (party == null)
			{
				return null;
			}

			for (Player member : party.getMembers())
			{
				if ((member != null) && (member != player) && member.isDead())
				{
					return member;
				}
			}
			return null;
		}

		/**
		 * BOTAI: the resurrection skill this character knows. Resurrection is configured like any other
		 * auto play skill, so it is looked up in the very same list the engine already walks.
		 * @param player the caster
		 * @return the skill, or null when the character knows none
		 */
		private static Skill findResurrectionSkill(Player player)
		{
			for (Integer skillId : player.getAutoUseSettings().getAutoBuffs())
			{
				final Skill skill = player.getKnownSkill(skillId);
				if ((skill != null) && skill.hasEffectType(EffectType.RESURRECTION, EffectType.RESURRECTION_SPECIAL))
				{
					return skill;
				}
			}
			return null;
		}

		/**
		 * BOTAI: ordered heal candidates for this pass.
		 * Reuses the shared .play threshold via countHurtMembers and lowestMemberHpPercent (caster included).
		 * All nine heal skills are offered: 1011 Heal, 1015 Battle Heal, 1027 Group Heal, 1217 Greater Heal,
		 * 1218 Greater Battle Heal, 1219 Greater Group Heal, 1401 Major Heal, 1402 Major Group Heal and
		 * 1553 Chain Heal. 1258 Restore Life and 1335 Balance Life are never returned here, and 1216/6847
		 * Self Heal is targetType SELF, which selectSupportTarget rejects before any cast.
		 * The order decides only which one is tried first: null entries mean not configured/not known and the
		 * caller skips them, and a rejected attempt (checkCondition/doCast) moves on to the next candidate.
		 * Each id keeps its own target type, so target ONE still heals one member, target PARTY still expands
		 * over the party and AREA_FRIENDLY (1553) still chains natively from the member it was aimed at.
		 * <b>Why the ladders rotate.</b> With a fixed order the first candidate wins whenever it is off cooldown:
		 * Major Heal reuses in 1 s, so it would be cast forever and the other configured heals would never fire,
		 * which is exactly what happened in game. Rotating each ladder one step per pass gives every configured
		 * heal its turn, while the ladder itself keeps deciding how strong a heal the wound deserves.
		 * @param player the caster
		 * @return candidates in priority order, possibly containing nulls
		 */
		private static Skill[] selectHealCandidates(Player player)
		{
			final int hurt = countHurtMembers(player, player.getAutoPlaySettings().getAutoPotionPercent());
			final double worst = lowestMemberHpPercent(player);
			final int turn = nextHealTurn(player);
			final Skill[] candidates = new Skill[9];
			int slot = 0;
			// Chain Heal leads both cases: it heals the chosen member and the most injured allies around it.
			slot = appendLadder(candidates, slot, player, HEAL_CHAIN, turn);
			if (hurt >= 2)
			{
				// Several members below the threshold: multi target heals first, then the single target ladders.
				slot = appendLadder(candidates, slot, player, HEAL_GROUP, turn);
				slot = appendSingleLadders(candidates, slot, player, worst, turn);
			}
			else
			{
				// One member below the threshold: single target ladders first, multi target heals as last resort.
				slot = appendSingleLadders(candidates, slot, player, worst, turn);
				slot = appendLadder(candidates, slot, player, HEAL_GROUP, turn);
			}
			return candidates;
		}

		/**
		 * BOTAI: appends a ladder rotated by one position per pass, so every skill of the ladder takes the first
		 * slot in its turn. The rotation never changes which ladder comes first, only the order inside it.
		 * @param candidates the array being filled
		 * @param slot the first free position
		 * @param player the caster
		 * @param ladder the skill ids of this ladder
		 * @param turn the per character rotation counter
		 * @return the next free position
		 */
		private static int appendLadder(Skill[] candidates, int slot, Player player, int[] ladder, int turn)
		{
			final int offset = ((turn % ladder.length) + ladder.length) % ladder.length;
			for (int i = 0; i < ladder.length; i++)
			{
				candidates[slot++] = getKnownHeal(player, ladder[(offset + i) % ladder.length]);
			}
			return slot;
		}

		/**
		 * BOTAI: appends the single target ladders in the order the worst wound deserves: the heavier the wound,
		 * the sooner the stronger heal is tried. The lighter heals stay available behind them so a wounded member
		 * is never left without a heal just because the strong ones are recycling.
		 * @param candidates the array being filled
		 * @param slot the first free position
		 * @param player the caster
		 * @param worst lowest HP percentage among the members that count, 100 when nobody is hurt
		 * @param turn the per character rotation counter
		 * @return the next free position
		 */
		private static int appendSingleLadders(Skill[] candidates, int slot, Player player, double worst, int turn)
		{
			if (worst < CRITICAL_HP_PERCENT)
			{
				slot = appendLadder(candidates, slot, player, HEAL_HEAVY, turn);
				slot = appendLadder(candidates, slot, player, HEAL_MEDIUM, turn);
				return appendLadder(candidates, slot, player, HEAL_LIGHT, turn);
			}
			if (worst < MODERATE_HP_PERCENT)
			{
				slot = appendLadder(candidates, slot, player, HEAL_MEDIUM, turn);
				slot = appendLadder(candidates, slot, player, HEAL_HEAVY, turn);
				return appendLadder(candidates, slot, player, HEAL_LIGHT, turn);
			}
			slot = appendLadder(candidates, slot, player, HEAL_LIGHT, turn);
			slot = appendLadder(candidates, slot, player, HEAL_MEDIUM, turn);
			return appendLadder(candidates, slot, player, HEAL_HEAVY, turn);
		}

		/**
		 * BOTAI: advances the heal rotation of a character, one step per pass.
		 * @param player the caster
		 * @return the turn this pass runs with
		 */
		private static int nextHealTurn(Player player)
		{
			final Integer current = HEAL_TURNS.get(Integer.valueOf(player.getObjectId()));
			final int turn = (current == null) ? 0 : current.intValue();
			HEAL_TURNS.put(Integer.valueOf(player.getObjectId()), Integer.valueOf(turn + 1));
			return turn;
		}

		/**
		 * BOTAI: lowest HP percentage among the members that count for healing, caster included.
		 * Mirrors countHurtMembers eligibility (null, dead, alikeDead, client outside IN_GAME, maxHp &lt;= 0) and
		 * returns 100 when nobody is eligible, so the caller falls back to the light single target heals.
		 * @param player the caster
		 * @return the lowest HP percentage of the group, or 100
		 */
		private static double lowestMemberHpPercent(Player player)
		{
			final Party party = player.getParty();
			if (party == null)
			{
				if (player.isDead() || player.isAlikeDead() || (player.getMaxHp() <= 0))
				{
					return 100.0;
				}
				return player.getCurrentHpPercent();
			}
			double lowest = 100.0;
			for (Player member : party.getMembers())
			{
				if ((member == null) || member.isDead() || member.isAlikeDead())
				{
					continue;
				}
				if ((member.getClient() != null) && (member.getClient().getConnectionState() != ConnectionState.IN_GAME))
				{
					continue;
				}
				if (member.getMaxHp() <= 0)
				{
					continue;
				}
				final double hp = member.getCurrentHpPercent();
				if (hp < lowest)
				{
					lowest = hp;
				}
			}
			return lowest;
		}

		/**
		 * BOTAI: counts living party members under the .play HP threshold, caster included.
		 * Mirrors selectSupportTarget exclusions (null, dead, alikeDead,
		 * client outside IN_GAME) and guards maxHp &lt;= 0. Solo (no party) counts only self.
		 * @param player the caster
		 * @param threshold the configured .play HP percent
		 * @return number of members needing a heal
		 */
		private static int countHurtMembers(Player player, double threshold)
		{
			final Party party = player.getParty();
			if (party == null)
			{
				if (player.isDead() || player.isAlikeDead())
				{
					return 0;
				}
				final long maxHp = player.getMaxHp();
				if (maxHp <= 0)
				{
					return 0;
				}
				return (player.getCurrentHpPercent() < threshold) ? 1 : 0;
			}
			int hurt = 0;
			for (Player member : party.getMembers())
			{
				if ((member == null) || member.isDead() || member.isAlikeDead())
				{
					continue;
				}
				if ((member.getClient() != null) && (member.getClient().getConnectionState() != ConnectionState.IN_GAME))
				{
					continue;
				}
				final long maxHp = member.getMaxHp();
				if (maxHp <= 0)
				{
					continue;
				}
				if (member.getCurrentHpPercent() < threshold)
				{
					hurt++;
				}
			}
			return hurt;
		}

		/**
		 * BOTAI: returns the requested heal only when it is both configured in AUTO_USE_BUFFS
		 * and known with EffectType.HEAL. No generic first-HEAL fallback here on purpose.
		 * @param player the caster
		 * @param skillId one of the nine heal ids offered by selectHealCandidates
		 * @return the skill, or null when not configured/known/HEAL
		 */
		private static Skill getKnownHeal(Player player, int skillId)
		{
			if (!player.getAutoUseSettings().getAutoBuffs().contains(Integer.valueOf(skillId)))
			{
				return null;
			}
			final Skill skill = player.getKnownSkill(skillId);
			if ((skill != null) && skill.hasEffectType(EffectType.HEAL))
			{
				return skill;
			}
			return null;
		}

		/**
		 * BOTAI: the first heal configured for this character.
		 * @param player the caster
		 * @return the skill, or null when none is configured
		 */
		private static Skill findHealSkill(Player player)
		{
			for (Integer skillId : player.getAutoUseSettings().getAutoBuffs())
			{
				final Skill skill = player.getKnownSkill(skillId);
				if ((skill != null) && skill.hasEffectType(EffectType.HEAL))
				{
					return skill;
				}
			}
			return null;
		}

		/**
		 * BOTAI: true for a song or a dance, which in High Five is a party wide cast rather than a single
		 * receiver buff.<br>
		 * <b>How it is told apart.</b> Every song and dance declares {@code targetType} PARTY, while a plain buff
		 * such as Shield or Might is SELF or ONE. That is the only difference the engine acts on, and it is
		 * exactly why a song can never be redirected to one chosen member.
		 * @param skill the skill being processed
		 * @return true when the skill covers the whole party by itself
		 */
		private static boolean isPartyWideSongOrDance(Skill skill)
		{
			return skill.getTargetType() == TargetType.PARTY;
		}

		/**
		 * BOTAI: true when the member carries a debuff that a cleanse style skill is meant to remove.
		 */
		private static boolean hasCureableAbnormal(Player member)
		{
			return member.isAffectedByAbnormalType(AbnormalType.POISON) || member.isAffectedByAbnormalType(AbnormalType.BLEEDING) || member.isAffectedByAbnormalType(AbnormalType.PARALYZE) || member.isAffectedByAbnormalType(AbnormalType.TURN_STONE) || member.isAffectedByAbnormalType(AbnormalType.FATAL_POISON) || member.isAffectedByAbnormalType(AbnormalType.CRITICAL_POISON) || member.isAffectedByAbnormalType(AbnormalType.CURSE_LIFE_FLOW);
		}

		/**
		 * BOTAI: casts on the chosen member. Every remaining rule, MP, range, reuse, items and the actual
		 * cast, is still decided by the engine itself through the very same public API the stock code uses.
		 * The result reports whether this attempt really started a cast. doCast() aborts silently inside
		 * beginCast() whenever its own conditions refuse the cast: an empty native target list (the target
		 * handler rejected the chosen member), a muted caster, a missing item. In those cases nothing was cast,
		 * so the caller must stay free to try the next candidate instead of ending the pass.
		 * The simultaneous state inherited from the caller is captured on purpose. The buff pass only breaks on
		 * isCastingNow(), so a simultaneous cast already running (an item skill, a herb) survives that guard and
		 * is not the result of this attempt: only a flag that turns on during the call counts. isCastingNow() is
		 * read bare because all three callers run after that guard and every abort path clears it, so it can only
		 * be true here when this attempt started the cast. This reads native casting state; no rule of its own is
		 * implemented and no native validation is duplicated.
		 */
		private static boolean castOnSupportTarget(Player player, Player member, Skill skill)
		{
			if (!skill.checkCondition(player, member, true))
			{
				return false;
			}

			final boolean wasCastingSimultaneously = player.isCastingSimultaneouslyNow();
			final WorldObject savedTarget = player.getTarget();
			player.setTarget(member);
			player.doCast(skill);
			player.setTarget(savedTarget);
			return player.isCastingNow() || (player.isCastingSimultaneouslyNow() && !wasCastingSimultaneously);
		}

		private boolean canCastBuff(Player player, WorldObject target, Skill skill)
		{
			if ((target != null) && target.isCreature() && target.asCreature().isAlikeDead() && (skill.getTargetType() != TargetType.SELF) && (skill.getTargetType() != TargetType.CORPSE) && (skill.getTargetType() != TargetType.PC_BODY))
			{
				return false;
			}
			
			final Playable playableTarget = (target == null) || !target.isPlayable() || (skill.getTargetType() == TargetType.SELF) ? player : target.asPlayable();
			if ((player != playableTarget) && (player.calculateDistance3D(playableTarget) > skill.getCastRange()))
			{
				return false;
			}
			
			if (!canUseMagic(player, playableTarget, skill))
			{
				return false;
			}
			
			final BuffInfo buffInfo = playableTarget.getEffectList().getBuffInfoBySkillId(skill.getId());
			final BuffInfo abnormalBuffInfo = playableTarget.getEffectList().getBuffInfoByAbnormalType(skill.getAbnormalType());
			if (abnormalBuffInfo != null)
			{
				if (buffInfo != null)
				{
					return (abnormalBuffInfo.getSkill().getId() == buffInfo.getSkill().getId()) && ((buffInfo.getTime() <= REUSE_MARGIN_TIME) || (buffInfo.getSkill().getLevel() < skill.getLevel()));
				}
				
				return (abnormalBuffInfo.getSkill().getAbnormalLevel() < skill.getAbnormalLevel()) || abnormalBuffInfo.isAbnormalType(AbnormalType.NONE);
			}
			
			return buffInfo == null;
		}
		
		private boolean canUseMagic(Playable playable, WorldObject target, Skill skill)
		{
			if ((skill.getItemConsumeCount() > 0) && (playable.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) < skill.getItemConsumeCount()))
			{
				return false;
			}
			
			if (playable.isPlayer() && (playable.asPlayer().getCharges() < skill.getChargeConsumeCount()))
			{
				return false;
			}
			
			final int mpConsume = skill.getMpInitialConsume() + skill.getMpConsume();
			if ((mpConsume > 0) && (playable.getCurrentMp() < mpConsume))
			{
				return false;
			}
			
			// Check if monster is spoiled to avoid Spoil (254) skill recast.
			if ((skill.getId() == 254) && (target != null) && target.isMonster() && target.asMonster().isSpoiled())
			{
				return false;
			}
			
			return !playable.isSkillDisabled(skill) && skill.checkCondition(playable, target, false);
		}
	}
	
	public synchronized void startAutoUseTask(Player player)
	{
		for (Set<Player> pool : POOLS)
		{
			if (pool.contains(player))
			{
				return;
			}
		}
		
		for (Set<Player> pool : POOLS)
		{
			if (pool.size() < POOL_SIZE)
			{
				pool.add(player);
				return;
			}
		}
		
		final Set<Player> pool = ConcurrentHashMap.newKeySet(POOL_SIZE);
		pool.add(player);
		ThreadPool.schedulePriorityTaskAtFixedRate(new AutoUse(pool), TASK_DELAY, TASK_DELAY);
		POOLS.add(pool);
	}
	
	public void stopAutoUseTask(Player player)
	{
		player.getAutoUseSettings().resetSkillOrder();
		HEAL_TURNS.remove(Integer.valueOf(player.getObjectId()));
		for (Set<Player> pool : POOLS)
		{
			if (pool.remove(player))
			{
				return;
			}
		}
	}
	
	public static AutoUseTaskManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final AutoUseTaskManager INSTANCE = new AutoUseTaskManager();
	}
}
