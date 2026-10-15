package handlers.custom.bot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.network.ConnectionState;

/**
 * BOTAI - Skill Intelligence, FASE 2: construccion de la instantanea de evaluacion.
 * <p>
 * Esta clase es el <b>unico</b> puente entre el evaluador puro y Mobius, y es de <b>solo lectura</b>:
 * consulta estado y devuelve un {@link SkillRuleEvaluator.Snapshot} inmutable. No lanza habilidades,
 * no cambia objetivos, no escribe variables y no altera el estado del juego.
 * <p>
 * Deliberadamente <b>no</b> pasa el {@code Player} ni el {@code Skill} al evaluador: los traduce a
 * valores planos. Asi el evaluador queda libre de dependencias del juego y es comprobable por
 * inspeccion.
 *
 * @author BOTAI
 */
public final class BotSkillRuleContext
{
	private BotSkillRuleContext()
	{
	}

	/**
	 * Construye la instantanea para un personaje.
	 *
	 * @param player el personaje, puede ser {@code null}
	 * @param supportSkillIds habilidades de soporte conocidas, puede ser {@code null} o vacio
	 * @return la instantanea, nunca {@code null}
	 */
	public static SkillRuleEvaluator.Snapshot snapshot(Player player, Set<Integer> supportSkillIds)
	{
		return snapshot(player, supportSkillIds, BotSkillRules.isSystemActive(player), BotSkillRules.load(player).getRules());
	}

	/**
	 * Construye la instantanea para un personaje a partir de unas reglas y un estado ya resueltos.
	 * <p>
	 * Esta variante existe para que quien ya tenga las reglas en memoria (por ejemplo una cache por
	 * personaje) no tenga que releerlas.
	 *
	 * @param player el personaje, puede ser {@code null}
	 * @param supportSkillIds habilidades de soporte conocidas, puede ser {@code null} o vacio
	 * @param systemActive si la puerta maestra esta en 1
	 * @param rules las reglas cargadas
	 * @return la instantanea, nunca {@code null}
	 */
	public static SkillRuleEvaluator.Snapshot snapshot(Player player, Set<Integer> supportSkillIds, boolean systemActive, java.util.Map<Integer, SkillRules.Rule> rules)
	{
		final Set<Integer> support = (supportSkillIds == null) ? new LinkedHashSet<>() : new LinkedHashSet<>(supportSkillIds);

		if (player == null)
		{
			return SkillRuleEvaluator.Snapshot.of(systemActive, rules, support, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null, null, null, null);
		}

		final int maxHp = player.getMaxHp();
		final int maxCp = player.getMaxCp();
		final int maxMp = player.getMaxMp();

		// Grupo: conjunto de porcentajes de HP de los miembros elegibles, replicando la elegibilidad
		// exacta de AutoUseTaskManager.countHurtMembers, y minimos reales de CP y MP.
		final Set<Integer> memberHp = new LinkedHashSet<>();
		Integer lowestCp = null;
		Integer lowestMp = null;
		final Party party = player.getParty();
		if (party == null)
		{
			// Sin grupo el caster se mira a si mismo, igual que el motor existente.
			if (isEligible(player))
			{
				memberHp.add(Integer.valueOf(player.getCurrentHpPercent()));
				lowestCp = Integer.valueOf(player.getCurrentCpPercent());
				lowestMp = Integer.valueOf(player.getCurrentMpPercent());
			}
		}
		else
		{
			final List<Player> members = party.getMembers();
			if (members != null)
			{
				for (Player member : members)
				{
					if (!isEligible(member))
					{
						continue;
					}

					final int memberHpPercent = member.getCurrentHpPercent();
					memberHp.add(Integer.valueOf(memberHpPercent));

					final int memberCpPercent = member.getCurrentCpPercent();
					if ((lowestCp == null) || (memberCpPercent < lowestCp.intValue()))
					{
						lowestCp = Integer.valueOf(memberCpPercent);
					}

					final int memberMpPercent = member.getCurrentMpPercent();
					if ((lowestMp == null) || (memberMpPercent < lowestMp.intValue()))
					{
						lowestMp = Integer.valueOf(memberMpPercent);
					}
				}
			}
		}

		// Objetivo: solo se expone si es una criatura viva. Sin objetivo, objeto que no es criatura, o
		// criatura muerta, las metricas quedan nulas y el evaluador devolvera UNKNOWN.
		Integer targetHp = null;
		Integer targetCp = null;
		Integer targetMp = null;
		Integer targetLevel = null;
		Integer targetDistance = null;
		final WorldObject target = player.getTarget();
		if ((target != null) && target.isCreature())
		{
			final Creature creature = target.asCreature();
			if (isLivingCreature(creature))
			{
				targetHp = Integer.valueOf(creature.getCurrentHpPercent());
				targetCp = Integer.valueOf(creature.getCurrentCpPercent());
				targetMp = Integer.valueOf(creature.getCurrentMpPercent());
				targetLevel = Integer.valueOf(creature.getLevel());
				targetDistance = Integer.valueOf((int) player.calculateDistance2D(creature));
			}
		}

		return SkillRuleEvaluator.Snapshot.of(systemActive, rules, support, player.getCurrentHpPercent(), player.getCurrentCpPercent(), player.getCurrentMpPercent(), missing(maxHp, player.getCurrentHp()), missing(maxCp, player.getCurrentCp()), missing(maxMp, player.getCurrentMp()), player.getLevel(), targetHp, targetCp, targetMp, targetLevel, targetDistance, memberHp, lowestCp, lowestMp);
	}

	/**
	 * Elegibilidad de un miembro para las metricas de grupo.
	 * <p>
	 * <b>Bots clientless.</b> Reproduce literalmente el predicado de
	 * {@code AutoUseTaskManager.countHurtMembers}: se descarta a un miembro <b>solo</b> si tiene cliente
	 * ({@code getClient() != null}) y ese cliente no esta en {@link ConnectionState#IN_GAME}. Un miembro
	 * con {@code getClient() == null} —el caso de los nueve bots clientless de este proyecto— <b>no</b>
	 * entra en esa condicion y por tanto <b>si</b> se cuenta. Excluirlos por no tener cliente seria un
	 * error: dejaria las metricas de grupo vacias precisamente con los personajes para los que existe
	 * esta capa.
	 * <p>
	 * Se descartan tambien los muertos, los {@code isAlikeDead} y los que tienen {@code maxHp <= 0},
	 * igual que el metodo original.
	 *
	 * @param member el miembro, puede ser {@code null}
	 * @return {@code true} si cuenta para las metricas de grupo
	 */
	public static boolean isEligible(Player member)
	{
		if (member == null)
		{
			return false;
		}

		if (member.isDead() || member.isAlikeDead())
		{
			return false;
		}

		// Clientless (getClient() == null) NO se descarta: la condicion solo aplica a quien tiene cliente.
		if ((member.getClient() != null) && (member.getClient().getConnectionState() != ConnectionState.IN_GAME))
		{
			return false;
		}

		return member.getMaxHp() > 0;
	}

	private static boolean isLivingCreature(Creature creature)
	{
		if (creature == null)
		{
			return false;
		}

		if (creature.isDead() || creature.isAlikeDead())
		{
			return false;
		}

		return true;
	}

	private static int missing(int max, double current)
	{
		final int value = max - (int) current;
		return (value < 0) ? 0 : value;
	}
}
