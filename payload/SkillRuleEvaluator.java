package handlers.custom.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BOTAI - Skill Intelligence, FASE 2: evaluador puro de reglas y condiciones.
 * <p>
 * <b>Esta clase no conoce el juego.</b> No importa nada de {@code org.l2jmobius}: no puede lanzar
 * habilidades, ni cambiar objetivos, ni tocar inventario, ni escribir variables. Todo lo que necesita
 * llega ya resuelto en un {@link Snapshot}. Esa separacion es deliberada y verificable: permite probar
 * el evaluador fuera del servidor y garantiza por construccion que no ejecuta nada.
 * <p>
 * <b>LA IA DECIDE; MOBIUS EJECUTA.</b> Aqui solo se decide si una regla resulta aplicable. La
 * validacion real (MP, items, reuso, rango, condiciones del skill) y la ejecucion siguen siendo de
 * Mobius y no se invocan desde esta clase.
 * <p>
 * <b>Precedencia del veredicto</b> (orden estricto):
 * {@code SYSTEM_INACTIVE} &rarr; {@code RULE_CONFLICT} &rarr; sin regla {@code NOT_APPLICABLE} &rarr;
 * {@code RULE_INVALID} &rarr; {@code RULE_OFF} &rarr; sin condiciones {@code APPLICABLE} &rarr;
 * evaluacion de las condiciones.
 *
 * @author BOTAI
 */
public final class SkillRuleEvaluator
{
	private SkillRuleEvaluator()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Resultado
	// ---------------------------------------------------------------------------------------------

	/**
	 * Resultado de evaluar una habilidad. Es un eje <b>distinto</b> del {@link SkillRules.State}
	 * persistido: el estado describe como esta configurada la habilidad, el veredicto describe el
	 * resultado de evaluarla. Por eso no se reutiliza ni se modifica {@code State}.
	 */
	public enum Verdict
	{
		/** La regla existe, esta habilitada y todas sus condiciones se cumplen. */
		APPLICABLE,
		/** No hay regla, o hay regla y alguna condicion no se cumple. */
		NOT_APPLICABLE,
		/** Faltan datos para decidir. <b>Nunca se trata como aplicable.</b> */
		UNKNOWN,
		/** La regla existe y esta deshabilitada por el operador. */
		RULE_OFF,
		/** La regla existe pero es invalida o incompatible. */
		RULE_INVALID,
		/** La habilidad es de soporte y no admite regla reactiva. */
		RULE_CONFLICT,
		/** La puerta maestra del sistema esta apagada. */
		SYSTEM_INACTIVE
	}

	/** Veredicto junto con el diagnostico de que datos faltaron, si faltaron. */
	public static final class RuleVerdict
	{
		private final Verdict verdict;
		private final List<Metric> missingMetrics;

		private RuleVerdict(Verdict verdict, List<Metric> missingMetrics)
		{
			this.verdict = verdict;
			this.missingMetrics = Collections.unmodifiableList(new ArrayList<>(missingMetrics));
		}

		static RuleVerdict of(Verdict verdict)
		{
			return new RuleVerdict(verdict, new ArrayList<>());
		}

		static RuleVerdict unknown(List<Metric> missing)
		{
			return new RuleVerdict(Verdict.UNKNOWN, (missing == null) ? new ArrayList<>() : missing);
		}

		public Verdict getVerdict()
		{
			return verdict;
		}

		/** @return las metricas que no se pudieron determinar, vacio si el veredicto no es UNKNOWN. */
		public List<Metric> getMissingMetrics()
		{
			return missingMetrics;
		}

		public boolean isApplicable()
		{
			return verdict == Verdict.APPLICABLE;
		}

		@Override
		public String toString()
		{
			return (missingMetrics.isEmpty() ? verdict.toString() : (verdict + " missing=" + missingMetrics));
		}
	}

	/** Metricas que una condicion puede consultar. Solo las que el modelo de la Fase 1 admite. */
	public enum Metric
	{
		SELF_HP,
		SELF_CP,
		SELF_MP,
		SELF_HP_MISSING,
		SELF_CP_MISSING,
		SELF_MP_MISSING,
		SELF_LEVEL,
		TARGET_HP,
		TARGET_CP,
		TARGET_MP,
		TARGET_LEVEL,
		TARGET_DISTANCE,
		PARTY_LOWEST_HP,
		PARTY_LOWEST_CP,
		PARTY_LOWEST_MP,
		PARTY_HURT_COUNT
	}

	// ---------------------------------------------------------------------------------------------
	// Instantanea
	// ---------------------------------------------------------------------------------------------

	/**
	 * Instantanea inmutable con todo lo que las condiciones pueden necesitar.
	 * <p>
	 * Las metricas de {@code SELF} y de grupo estan siempre disponibles. Las de {@code TARGET} son
	 * {@code null} cuando no hay objetivo determinable (sin objetivo, no criatura, o criatura muerta):
	 * {@code null} significa <b>desconocido</b> y produce {@link Verdict#UNKNOWN}, nunca {@code true}.
	 */
	public static final class Snapshot
	{
		private final boolean systemActive;
		private final Map<Integer, SkillRules.Rule> rules;
		private final Set<Integer> supportSkillIds;

		private final int selfHpPercent;
		private final int selfCpPercent;
		private final int selfMpPercent;
		private final int selfHpMissing;
		private final int selfCpMissing;
		private final int selfMpMissing;
		private final int selfLevel;

		private final Integer targetHpPercent;
		private final Integer targetCpPercent;
		private final Integer targetMpPercent;
		private final Integer targetLevel;
		private final Integer targetDistance;

		private final Set<Integer> partyMemberHpPercents;
		private final Integer partyLowestCpPercent;
		private final Integer partyLowestMpPercent;

		Snapshot(boolean systemActive, Map<Integer, SkillRules.Rule> rules, Set<Integer> supportSkillIds, int selfHpPercent, int selfCpPercent, int selfMpPercent, int selfHpMissing, int selfCpMissing, int selfMpMissing, int selfLevel, Integer targetHpPercent, Integer targetCpPercent, Integer targetMpPercent, Integer targetLevel, Integer targetDistance, Set<Integer> partyMemberHpPercents, Integer partyLowestCpPercent, Integer partyLowestMpPercent)
		{
			this.systemActive = systemActive;
			this.rules = rules;
			this.supportSkillIds = supportSkillIds;
			this.selfHpPercent = selfHpPercent;
			this.selfCpPercent = selfCpPercent;
			this.selfMpPercent = selfMpPercent;
			this.selfHpMissing = selfHpMissing;
			this.selfCpMissing = selfCpMissing;
			this.selfMpMissing = selfMpMissing;
			this.selfLevel = selfLevel;
			this.targetHpPercent = targetHpPercent;
			this.targetCpPercent = targetCpPercent;
			this.targetMpPercent = targetMpPercent;
			this.targetLevel = targetLevel;
			this.targetDistance = targetDistance;
			this.partyMemberHpPercents = partyMemberHpPercents;
			this.partyLowestCpPercent = partyLowestCpPercent;
			this.partyLowestMpPercent = partyLowestMpPercent;
		}

		/**
		 * Crea una instantanea. Las metricas {@code Integer} nulas significan <b>desconocido</b>, lo que
		 * produce {@link Verdict#UNKNOWN} y nunca un resultado aplicable.
		 *
		 * @param systemActive puerta maestra
		 * @param rules reglas cargadas
		 * @param supportSkillIds habilidades de soporte conocidas
		 * @param selfHpPercent porcentaje de HP propio
		 * @param selfCpPercent porcentaje de CP propio
		 * @param selfMpPercent porcentaje de MP propio
		 * @param selfHpMissing HP faltante propio
		 * @param selfCpMissing CP faltante propio
		 * @param selfMpMissing MP faltante propio
		 * @param selfLevel nivel propio
		 * @param targetHpPercent porcentaje de HP del objetivo, o {@code null}
		 * @param targetCpPercent porcentaje de CP del objetivo, o {@code null}
		 * @param targetMpPercent porcentaje de MP del objetivo, o {@code null}
		 * @param targetLevel nivel del objetivo, o {@code null}
		 * @param targetDistance distancia 2D al objetivo, o {@code null}
		 * @param partyMemberHpPercents porcentajes de HP de los miembros elegibles, o {@code null}
		 * @param partyLowestCpPercent minimo de CP del grupo, o {@code null}
		 * @param partyLowestMpPercent minimo de MP del grupo, o {@code null}
		 * @return la instantanea
		 */
		public static Snapshot of(boolean systemActive, Map<Integer, SkillRules.Rule> rules, Set<Integer> supportSkillIds, int selfHpPercent, int selfCpPercent, int selfMpPercent, int selfHpMissing, int selfCpMissing, int selfMpMissing, int selfLevel, Integer targetHpPercent, Integer targetCpPercent, Integer targetMpPercent, Integer targetLevel, Integer targetDistance, Set<Integer> partyMemberHpPercents, Integer partyLowestCpPercent, Integer partyLowestMpPercent)
		{
			return new Snapshot(systemActive, rules, supportSkillIds, selfHpPercent, selfCpPercent, selfMpPercent, selfHpMissing, selfCpMissing, selfMpMissing, selfLevel, targetHpPercent, targetCpPercent, targetMpPercent, targetLevel, targetDistance, partyMemberHpPercents, partyLowestCpPercent, partyLowestMpPercent);
		}

		public boolean isSystemActive()
		{
			return systemActive;
		}

		public Map<Integer, SkillRules.Rule> getRules()
		{
			return rules;
		}

		public Set<Integer> getSupportSkillIds()
		{
			return supportSkillIds;
		}

		public int getSelfHpPercent()
		{
			return selfHpPercent;
		}

		public int getSelfCpPercent()
		{
			return selfCpPercent;
		}

		public int getSelfMpPercent()
		{
			return selfMpPercent;
		}

		public int getSelfHpMissing()
		{
			return selfHpMissing;
		}

		public int getSelfCpMissing()
		{
			return selfCpMissing;
		}

		public int getSelfMpMissing()
		{
			return selfMpMissing;
		}

		public int getSelfLevel()
		{
			return selfLevel;
		}

		public Integer getTargetHpPercent()
		{
			return targetHpPercent;
		}

		public Integer getTargetCpPercent()
		{
			return targetCpPercent;
		}

		public Integer getTargetMpPercent()
		{
			return targetMpPercent;
		}

		public Integer getTargetLevel()
		{
			return targetLevel;
		}

		public Integer getTargetDistance()
		{
			return targetDistance;
		}

		public Set<Integer> getPartyMemberHpPercents()
		{
			return partyMemberHpPercents;
		}

		public Integer getPartyLowestCpPercent()
		{
			return partyLowestCpPercent;
		}

		public Integer getPartyLowestMpPercent()
		{
			return partyLowestMpPercent;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Evaluacion
	// ---------------------------------------------------------------------------------------------

	/**
	 * Evalua la regla de una habilidad contra una instantanea.
	 *
	 * @param snapshot la instantanea, si es {@code null} se considera que no hay datos
	 * @param skillId la habilidad
	 * @return el veredicto, nunca {@code null}
	 */
	public static RuleVerdict evaluate(Snapshot snapshot, int skillId)
	{
		if (snapshot == null)
		{
			return RuleVerdict.of(Verdict.UNKNOWN);
		}

		// 1. Puerta maestra.
		if (!snapshot.isSystemActive())
		{
			return RuleVerdict.of(Verdict.SYSTEM_INACTIVE);
		}

		// 2. Conflicto con soporte, antes de mirar siquiera la regla.
		if ((snapshot.getSupportSkillIds() != null) && snapshot.getSupportSkillIds().contains(Integer.valueOf(skillId)))
		{
			return RuleVerdict.of(Verdict.RULE_CONFLICT);
		}

		// 3. Sin regla.
		final Map<Integer, SkillRules.Rule> rules = snapshot.getRules();
		final SkillRules.Rule rule = (rules == null) ? null : rules.get(Integer.valueOf(skillId));
		if (rule == null)
		{
			return RuleVerdict.of(Verdict.NOT_APPLICABLE);
		}

		// 4. Regla invalida.
		if (!rule.isValid())
		{
			return RuleVerdict.of(Verdict.RULE_INVALID);
		}

		// 5. Regla deshabilitada.
		if (!rule.isEnabled())
		{
			return RuleVerdict.of(Verdict.RULE_OFF);
		}

		// 6. Sin condiciones: disparo incondicional.
		if (rule.getConditions().isEmpty())
		{
			return RuleVerdict.of(Verdict.APPLICABLE);
		}

		// 7. Combinacion AND de tres valores.
		// `indeterminate` recoge las condiciones que no se han podido evaluar por no ser siquiera
		// evaluables (una entrada nula en la lista). No se les asigna metrica porque no hay ninguna que
		// consultar, pero si impiden que la regla resulte aplicable.
		final Set<Metric> missing = new LinkedHashSet<>();
		boolean indeterminate = false;
		for (SkillRules.Condition condition : rule.getConditions())
		{
			if (condition == null)
			{
				// Una condicion nula NO es falsa ni verdadera: es desconocida. Ignorarla permitiria que
				// la regla terminara como APPLICABLE sin haberla evaluado, que es justo lo que no debe
				// ocurrir. No se le inventa metrica alguna.
				indeterminate = true;
				continue;
			}

			if (!condition.isValid())
			{
				// Una condicion corrupta no puede considerarse satisfecha, pero tampoco se le puede
				// asignar una metrica: no tiene sujeto ni metrica que consultar. Se marca como
				// indeterminada para que la regla no pueda terminar en APPLICABLE.
				indeterminate = true;
				continue;
			}

			final Integer value = valueOf(snapshot, condition);
			if (value == null)
			{
				final Metric metric = metricOf(condition);
				if (metric != null)
				{
					missing.add(metric);
				}
				else
				{
					indeterminate = true;
				}
				continue;
			}

			// HURT_COUNT tiene dos magnitudes: el valor es el umbral de HP con el que valueOf ha contado
			// los heridos, y la comparacion se hace contra la cantidad exigida. Para el resto de metricas
			// valueOf devuelve la propia magnitud y se compara contra el valor, como siempre.
			final int expected = (condition.getMetric() == SkillRules.Metric.HURT_COUNT) ? condition.getCountLimit() : condition.getValue();
			if (!compare(value.intValue(), condition.getOperator(), expected))
			{
				return RuleVerdict.of(Verdict.NOT_APPLICABLE);
			}
		}

		// Alguna condicion falsa ya habria retornado arriba: aqui no hay ninguna falsa. Si falta un dato
		// o hay una entrada indeterminada, el resultado es UNKNOWN; nunca APPLICABLE.
		if (indeterminate)
		{
			return RuleVerdict.unknown(null);
		}

		if (!missing.isEmpty())
		{
			return RuleVerdict.unknown(new ArrayList<>(missing));
		}

		return RuleVerdict.of(Verdict.APPLICABLE);
	}

	/**
	 * Resuelve el valor de una condicion a partir de la instantanea.
	 *
	 * @param snapshot la instantanea
	 * @param condition la condicion
	 * @return el valor, o {@code null} si no es determinable
	 */
	public static Integer valueOf(Snapshot snapshot, SkillRules.Condition condition)
	{
		// Una condicion invalida tiene todos sus campos a null, asi que el switch sobre el sujeto
		// lanzaria NullPointerException. Se trata como indeterminada, que es su contrato.
		if ((condition == null) || !condition.isValid())
		{
			return null;
		}

		switch (condition.getSubject())
		{
			case SELF:
			{
				switch (condition.getMetric())
				{
					case HP:
					{
						return Integer.valueOf((condition.getValueType() == SkillRules.ValueType.MISSING) ? snapshot.getSelfHpMissing() : snapshot.getSelfHpPercent());
					}
					case CP:
					{
						return Integer.valueOf((condition.getValueType() == SkillRules.ValueType.MISSING) ? snapshot.getSelfCpMissing() : snapshot.getSelfCpPercent());
					}
					case MP:
					{
						return Integer.valueOf((condition.getValueType() == SkillRules.ValueType.MISSING) ? snapshot.getSelfMpMissing() : snapshot.getSelfMpPercent());
					}
					case LEVEL:
					{
						return Integer.valueOf(snapshot.getSelfLevel());
					}
					default:
					{
						return null;
					}
				}
			}
			case TARGET:
			{
				switch (condition.getMetric())
				{
					case HP:
					{
						// MISSING no es valido para TARGET (lo rechaza validateCombination), pero si llegara
						// aqui se trata como no determinable en vez de inventar un maximo.
						return (condition.getValueType() == SkillRules.ValueType.MISSING) ? null : snapshot.getTargetHpPercent();
					}
					case CP:
					{
						return (condition.getValueType() == SkillRules.ValueType.MISSING) ? null : snapshot.getTargetCpPercent();
					}
					case MP:
					{
						return (condition.getValueType() == SkillRules.ValueType.MISSING) ? null : snapshot.getTargetMpPercent();
					}
					case LEVEL:
					{
						return snapshot.getTargetLevel();
					}
					case DISTANCE:
					{
						return snapshot.getTargetDistance();
					}
					default:
					{
						return null;
					}
				}
			}
			case PARTY_LOWEST:
			{
				switch (condition.getMetric())
				{
					case HP:
					{
						return lowest(snapshot.getPartyMemberHpPercents());
					}
					case CP:
					{
						return snapshot.getPartyLowestCpPercent();
					}
					case MP:
					{
						return snapshot.getPartyLowestMpPercent();
					}
					default:
					{
						return null;
					}
				}
			}
			case PARTY_COUNT:
			{
				if (condition.getMetric() != SkillRules.Metric.HURT_COUNT)
				{
					return null;
				}

				// El umbral lo aporta la propia condicion, nunca un ajuste oculto como el de la pocion.
				// Se recalcula sobre los porcentajes de HP de los miembros elegibles, de modo que el
				// umbral sea el de la condicion y no uno precalculado.
				final Set<Integer> members = snapshot.getPartyMemberHpPercents();
				if (members == null)
				{
					return null;
				}

				int hurt = 0;
				for (Integer hpPercent : members)
				{
					if ((hpPercent != null) && (hpPercent.intValue() < condition.getValue()))
					{
						hurt++;
					}
				}

				return Integer.valueOf(hurt);
			}
			default:
			{
				return null;
			}
		}
	}

	/** Compara con el operador del modelo. */
	public static boolean compare(int value, SkillRules.Operator operator, int expected)
	{
		if (operator == null)
		{
			return false;
		}

		switch (operator)
		{
			case EQ:
			{
				return value == expected;
			}
			case NE:
			{
				return value != expected;
			}
			case LT:
			{
				return value < expected;
			}
			case LE:
			{
				return value <= expected;
			}
			case GT:
			{
				return value > expected;
			}
			case GE:
			{
				return value >= expected;
			}
			default:
			{
				return false;
			}
		}
	}

	/** @return la metrica implicada por una condicion, para el diagnostico de datos ausentes. */
	public static Metric metricOf(SkillRules.Condition condition)
	{
		// Igual que en valueOf: una condicion invalida no tiene sujeto con el que despachar. Se
		// devuelve null y los llamantes no deben registrar ninguna metrica en ese caso.
		if ((condition == null) || !condition.isValid())
		{
			return null;
		}

		switch (condition.getSubject())
		{
			case SELF:
			{
				switch (condition.getMetric())
				{
					case HP:
					{
						return (condition.getValueType() == SkillRules.ValueType.MISSING) ? Metric.SELF_HP_MISSING : Metric.SELF_HP;
					}
					case CP:
					{
						return (condition.getValueType() == SkillRules.ValueType.MISSING) ? Metric.SELF_CP_MISSING : Metric.SELF_CP;
					}
					case MP:
					{
						return (condition.getValueType() == SkillRules.ValueType.MISSING) ? Metric.SELF_MP_MISSING : Metric.SELF_MP;
					}
					case LEVEL:
					{
						return Metric.SELF_LEVEL;
					}
					default:
					{
						return null;
					}
				}
			}
			case TARGET:
			{
				switch (condition.getMetric())
				{
					case HP:
					{
						return Metric.TARGET_HP;
					}
					case CP:
					{
						return Metric.TARGET_CP;
					}
					case MP:
					{
						return Metric.TARGET_MP;
					}
					case LEVEL:
					{
						return Metric.TARGET_LEVEL;
					}
					case DISTANCE:
					{
						return Metric.TARGET_DISTANCE;
					}
					default:
					{
						return null;
					}
				}
			}
			case PARTY_LOWEST:
			{
				switch (condition.getMetric())
				{
					case HP:
					{
						return Metric.PARTY_LOWEST_HP;
					}
					case CP:
					{
						return Metric.PARTY_LOWEST_CP;
					}
					case MP:
					{
						return Metric.PARTY_LOWEST_MP;
					}
					default:
					{
						return null;
					}
				}
			}
			case PARTY_COUNT:
			{
				return (condition.getMetric() == SkillRules.Metric.HURT_COUNT) ? Metric.PARTY_HURT_COUNT : null;
			}
			default:
			{
				return null;
			}
		}
	}

	private static Integer lowest(Set<Integer> values)
	{
		if (values == null)
		{
			return null;
		}

		Integer min = null;
		for (Integer value : values)
		{
			if (value == null)
			{
				continue;
			}

			if ((min == null) || (value.intValue() < min.intValue()))
			{
				min = value;
			}
		}

		return min;
	}
}
