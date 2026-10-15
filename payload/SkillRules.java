package handlers.custom.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BOTAI - Skill Intelligence, FASE 1: modelo de reglas por habilidad.
 * <p>
 * <b>Esta clase es INERTE por diseno.</b> Contiene el modelo, la validacion, la serializacion y la
 * resolucion de estado de las reglas por habilidad, y <b>no</b> llama a ninguna API del servidor:
 * no conoce {@code Skill}, {@code Player}, {@code AutoUseTaskManager} ni ninguna lista nativa. Su
 * unica razon de existir en esta fase es poder guardar, leer y clasificar reglas sin alterar el
 * comportamiento de {@code .play}.
 * <p>
 * El unico uso de {@code java.util} es para colecciones: la clase es pura Java a proposito, para que
 * el parseo, la validacion y la serializacion puedan probarse fuera del servidor.
 * <p>
 * <b>Invariantes de la fase (verificables por inspeccion):</b>
 * <ul>
 * <li>Nada aqui ejecuta habilidades ni toca los motores {@code AutoUse}/{@code AutoPlay}.</li>
 * <li>Nada aqui modifica {@code AUTO_USE_SKILLS} ni {@code AUTO_USE_BUFFS}.</li>
 * <li>Nada aqui elimina reglas: una regla corrupta se conserva como {@link State#INVALID} y se
 * diagnostica.</li>
 * <li>La existencia de una regla no la activa: hace falta la puerta maestra (ver
 * {@code BotSkillRules#isSystemActive}).</li>
 * </ul>
 *
 * @author BOTAI
 */
public final class SkillRules
{
	/** Version del esquema de serializacion que produce y entiende esta clase. */
	public static final int SCHEMA_VERSION = 2;

	/**
	 * Limite minimo de {@code countLimit}. Uno, porque exigir cero miembros heridos es trivialmente
	 * cierto y no expresa ninguna intencion.
	 */
	public static final int MIN_COUNT_LIMIT = 1;

	/**
	 * Limite maximo de {@code countLimit}. Nueve, porque es el tamano maximo de grupo de este proyecto.
	 */
	public static final int MAX_COUNT_LIMIT = 9;

	/** Maximo de condiciones por regla (limite de interfaz y de coste de evaluacion). */
	public static final int MAX_CONDITIONS = 5;

	/** Valor de prioridad por defecto cuando no se especifica. */
	public static final int DEFAULT_PRIORITY = 50;

	/** Prioridad minima admitida. */
	public static final int MIN_PRIORITY = 0;

	/** Prioridad maxima admitida. */
	public static final int MAX_PRIORITY = 100;

	private SkillRules()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Modelo
	// ---------------------------------------------------------------------------------------------

	/** Sujeto sobre el que se evalua una metrica. */
	public enum Subject
	{
		SELF,
		TARGET,
		PARTY_LOWEST,
		PARTY_COUNT
	}

	/** Metrica observable. */
	public enum Metric
	{
		HP,
		CP,
		MP,
		DISTANCE,
		LEVEL,
		HURT_COUNT
	}

	/** Operador de comparacion. */
	public enum Operator
	{
		EQ,
		NE,
		LT,
		LE,
		GT,
		GE
	}

	/** Interpretacion del valor numerico de una condicion. */
	public enum ValueType
	{
		PERCENT,
		ABSOLUTE,
		MISSING
	}

	/** Estado resuelto de una habilidad respecto al sistema de reglas. */
	public enum State
	{
		/** Sin regla: comportamiento nativo intacto. */
		NO_RULE,
		/** Regla valida y habilitada, con el sistema activo. */
		ENABLED,
		/** Regla valida y deshabilitada por el operador. */
		DISABLED,
		/** Regla presente pero invalida o incompatible. Se conserva y se diagnostica. */
		INVALID,
		/** Regla sobre una habilidad de soporte: no admisible en el pase reactivo. */
		CONFLICT,
		/** Regla valida, pero la puerta maestra del sistema esta apagada. */
		INERT
	}

	/**
	 * Una condicion: sujeto, metrica, operador, interpretacion del valor y valor.
	 * <p>
	 * Inmutable. {@link #error} solo se rellena cuando la condicion proviene de datos que no se
	 * pudieron interpretar.
	 */
	public static final class Condition
	{
		private final Subject subject;
		private final Metric metric;
		private final Operator operator;
		private final ValueType valueType;
		private final int value;
		private final int countLimit;
		private final String error;

		/**
		 * Construye una condicion sin limite de recuento explicito.
		 * <p>
		 * {@code countLimit} queda en {@link #MIN_COUNT_LIMIT}. Para las metricas que no son
		 * {@code HURT_COUNT} ese valor no interviene en la evaluacion.
		 *
		 * @param subject el sujeto
		 * @param metric la metrica
		 * @param operator el operador
		 * @param valueType la interpretacion del valor
		 * @param value el valor: umbral porcentual de HP en {@code HURT_COUNT}
		 */
		public Condition(Subject subject, Metric metric, Operator operator, ValueType valueType, int value)
		{
			this(subject, metric, operator, valueType, value, MIN_COUNT_LIMIT);
		}

		/**
		 * Construye una condicion con las dos magnitudes separadas.
		 * <p>
		 * En {@code PARTY_COUNT.HURT_COUNT}, {@code value} es el <b>umbral porcentual de HP</b> (un
		 * miembro cuenta como herido si su porcentaje es estrictamente menor) y {@code countLimit} es la
		 * <b>cantidad de miembros exigida</b>, contra la que el operador compara el recuento. Separar
		 * ambas magnitudes es lo que permite expresar «al menos dos miembros por debajo del 40 %», que
		 * con un unico campo era inexpresable.
		 *
		 * @param subject el sujeto
		 * @param metric la metrica
		 * @param operator el operador
		 * @param valueType la interpretacion del valor
		 * @param value el umbral porcentual de HP en {@code HURT_COUNT}
		 * @param countLimit la cantidad de miembros exigida en {@code HURT_COUNT}
		 */
		public Condition(Subject subject, Metric metric, Operator operator, ValueType valueType, int value, int countLimit)
		{
			this.subject = subject;
			this.metric = metric;
			this.operator = operator;
			this.valueType = valueType;
			this.value = value;
			this.countLimit = countLimit;
			this.error = null;
		}

		private Condition(String error)
		{
			this.subject = null;
			this.metric = null;
			this.operator = null;
			this.valueType = null;
			this.value = 0;
			this.countLimit = MIN_COUNT_LIMIT;
			this.error = error;
		}

		/** @return una condicion marcada como invalida, conservando el motivo. */
		public static Condition invalid(String reason)
		{
			return new Condition((reason == null) || reason.isEmpty() ? "condicion invalida" : reason);
		}

		public Subject getSubject()
		{
			return subject;
		}

		public Metric getMetric()
		{
			return metric;
		}

		public Operator getOperator()
		{
			return operator;
		}

		public ValueType getValueType()
		{
			return valueType;
		}

		public int getValue()
		{
			return value;
		}

		/**
		 * @return la cantidad de miembros exigida en {@code HURT_COUNT}; {@link #MIN_COUNT_LIMIT} en el
		 *         resto de metricas, donde no interviene en la evaluacion
		 */
		public int getCountLimit()
		{
			return countLimit;
		}

		/** @return el motivo del fallo, o {@code null} si la condicion es valida. */
		public String getError()
		{
			return error;
		}

		public boolean isValid()
		{
			return error == null;
		}

		@Override
		public String toString()
		{
			if (error != null)
			{
				return "INVALID(" + error + ")";
			}

			return subject + "." + metric + " " + operator + " " + valueType + " " + value;
		}
	}

	/**
	 * Una regla por habilidad. Inmutable salvo por {@link #setEnabled}, que existe para que la
	 * interfaz pueda alternar el interruptor sin reconstruir el objeto.
	 */
	public static final class Rule
	{
		private final int skillId;
		private final int priority;
		private final List<Condition> conditions;
		private final String error;
		private boolean enabled;

		private Rule(int skillId, boolean enabled, int priority, List<Condition> conditions, String error)
		{
			this.skillId = skillId;
			this.enabled = enabled;
			this.priority = priority;
			this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
			this.error = error;
		}

		/**
		 * Crea una regla a partir de sus datos.
		 * <p>
		 * Si alguna de las condiciones de la lista no es valida, la regla resultante tambien queda
		 * invalida y conserva un motivo de diagnostico. Antes se devolvia siempre una regla valida, de
		 * modo que una regla con una condicion corrupta <b>se presentaba como valida</b>: el evaluador ya
		 * impedia aplicarla, pero el diagnostico mentia y cualquier logica que decidiera por
		 * {@link #isValid()} trataria como sana una regla rota.
		 * <p>
		 * Se conservan el identificador, el interruptor, la prioridad y las condiciones originales: una
		 * regla invalida por esta via <b>no</b> se degrada a los valores por defecto de {@link #invalid},
		 * porque esa informacion sigue siendo util para que el operador corrija el dato.
		 *
		 * @param skillId la habilidad
		 * @param enabled el interruptor
		 * @param priority la prioridad, se acota al rango admitido
		 * @param conditions las condiciones, pueden ser {@code null}
		 * @return la regla, valida si todas sus condiciones lo son
		 */
		public static Rule create(int skillId, boolean enabled, int priority, List<Condition> conditions)
		{
			final List<Condition> copy = (conditions == null) ? new ArrayList<>() : new ArrayList<>(conditions);

			int invalidConditions = 0;
			String firstReason = null;
			for (Condition condition : copy)
			{
				if ((condition == null) || !condition.isValid())
				{
					invalidConditions++;
					if (firstReason == null)
					{
						firstReason = (condition == null) ? "condicion nula en la lista" : condition.getError();
					}
				}
			}

			final String error;
			if (invalidConditions == 0)
			{
				error = null;
			}
			else
			{
				error = "la regla contiene " + invalidConditions + " condicion" + ((invalidConditions == 1) ? "" : "es") + " invalida" + ((invalidConditions == 1) ? "" : "s") + ": " + ((firstReason == null) ? "sin motivo" : firstReason);
			}

			return new Rule(skillId, enabled, clampPriority(priority), copy, error);
		}

		/** @return una regla marcada como invalida, conservando el motivo y los datos disponibles. */
		public static Rule invalid(int skillId, String reason)
		{
			return new Rule(skillId, false, DEFAULT_PRIORITY, new ArrayList<>(),
				(reason == null) || reason.isEmpty() ? "regla invalida" : reason);
		}

		/**
		 * Reconstruye una regla invalida conservando su motivo y adjuntando las condiciones leidas.
		 * <p>
		 * Existe porque {@link #create} produce siempre una regla valida: usarlo para reconstruir una
		 * regla que ya estaba marcada como invalida borraba el diagnostico y ademas la volvia valida,
		 * con lo que una regla corrupta cambiaba de estado durante la carga sin que nadie la corrigiera.
		 * Aqui la invalidez y su motivo se preservan, y las condiciones se adjuntan sin descartarlas.
		 * <p>
		 * El interruptor y la prioridad <b>no</b> se heredan a proposito: una regla invalida se conserva
		 * desactivada y con la prioridad por defecto, igual que {@link #invalid}.
		 *
		 * @param skillId la habilidad
		 * @param reason el motivo original, se conserva tal cual
		 * @param conditions las condiciones leidas, pueden ser {@code null}
		 * @return la regla invalida reconstruida
		 */
		public static Rule invalidWith(int skillId, String reason, List<Condition> conditions)
		{
			final List<Condition> copy = (conditions == null) ? new ArrayList<>() : new ArrayList<>(conditions);
			return new Rule(skillId, false, DEFAULT_PRIORITY, copy,
				(reason == null) || reason.isEmpty() ? "regla invalida" : reason);
		}

		public int getSkillId()
		{
			return skillId;
		}

		public boolean isEnabled()
		{
			return enabled;
		}

		public void setEnabled(boolean enabled)
		{
			this.enabled = enabled;
		}

		public int getPriority()
		{
			return priority;
		}

		public List<Condition> getConditions()
		{
			return conditions;
		}

		/** @return el motivo del fallo, o {@code null} si la regla es valida. */
		public String getError()
		{
			return error;
		}

		public boolean isValid()
		{
			return error == null;
		}

		@Override
		public String toString()
		{
			return "skill=" + skillId + (enabled ? " ON" : " OFF") + " prio=" + priority + " conds=" + conditions.size() + (error == null ? "" : " INVALID(" + error + ")");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Validacion
	// ---------------------------------------------------------------------------------------------

	/**
	 * Matriz de validez sujeto + metrica + tipo de valor. Rechaza explicitamente las combinaciones que
	 * en otros proyectos devuelven un resultado vacio en silencio: distancia y nivel solo admiten valor
	 * absoluto, y el contador de heridos solo admite valor absoluto.
	 *
	 * @param subject el sujeto
	 * @param metric la metrica
	 * @param valueType la interpretacion del valor
	 * @return {@code null} si la combinacion es valida, o el motivo del rechazo
	 */
	public static String validateCombination(Subject subject, Metric metric, ValueType valueType)
	{
		if ((subject == null) || (metric == null) || (valueType == null))
		{
			return "sujeto, metrica y tipo de valor son obligatorios";
		}

		switch (metric)
		{
			case DISTANCE:
			case LEVEL:
			{
				if (subject != Subject.SELF)
				{
					if ((subject == Subject.PARTY_COUNT) || (subject == Subject.PARTY_LOWEST))
					{
						return metric + " no se evalua sobre " + subject;
					}
				}

				if (valueType != ValueType.ABSOLUTE)
				{
					return metric + " solo admite " + ValueType.ABSOLUTE;
				}

				return null;
			}
			case HURT_COUNT:
			{
				if (subject != Subject.PARTY_COUNT)
				{
					return "HURT_COUNT solo se evalua sobre " + Subject.PARTY_COUNT;
				}

				if (valueType != ValueType.ABSOLUTE)
				{
					return "HURT_COUNT solo admite " + ValueType.ABSOLUTE;
				}

				return null;
			}
			default:
			{
				break;
			}
		}

		// HP, CP y MP se evaluan siempre en porcentaje o como cantidad faltante. ABSOLUTE se rechaza
		// de forma explicita: el evaluador consulta el porcentaje para estas metricas, asi que una
		// condicion como "HP <= 2000 absoluto" se comportaria como "HP% <= 2000", es decir, no
		// representaria el valor configurado. Mejor rechazarla que aceptarla en silencio.
		if ((metric == Metric.HP) || (metric == Metric.CP) || (metric == Metric.MP))
		{
			if (subject == Subject.PARTY_COUNT)
			{
				return metric + " no se evalua sobre " + Subject.PARTY_COUNT;
			}

			if (valueType == ValueType.ABSOLUTE)
			{
				return metric + " no admite " + ValueType.ABSOLUTE + ": use " + ValueType.PERCENT + " o " + ValueType.MISSING;
			}

			return null;
		}

		return "combinacion no soportada";
	}

	/** @return la prioridad acotada al rango admitido. */
	public static int clampPriority(int priority)
	{
		if (priority < MIN_PRIORITY)
		{
			return MIN_PRIORITY;
		}

		if (priority > MAX_PRIORITY)
		{
			return MAX_PRIORITY;
		}

		return priority;
	}

	// ---------------------------------------------------------------------------------------------
	// Serializacion
	// ---------------------------------------------------------------------------------------------

	/**
	 * Serializa las reglas al formato de texto del blob.
	 * <p>
	 * Una linea por regla: {@code skillId=enabled;priority}. El orden es el de iteracion del mapa, que
	 * en {@link LinkedHashMap} es el de insercion, de modo que guardar y volver a leer es determinista.
	 *
	 * @param rules las reglas
	 * @return el texto, cadena vacia si no hay reglas
	 */
	public static String encodeRules(Map<Integer, Rule> rules)
	{
		if ((rules == null) || rules.isEmpty())
		{
			return "";
		}

		final StringBuilder sb = new StringBuilder();
		for (Rule rule : rules.values())
		{
			if (rule == null)
			{
				continue;
			}

			if (sb.length() > 0)
			{
				sb.append('\n');
			}

			sb.append(rule.getSkillId()).append('=').append(rule.isEnabled() ? 1 : 0).append(';').append(rule.getPriority());
		}

		return sb.toString();
	}

	/**
	 * Deserializa el blob de reglas.
	 * <p>
	 * Tolerante a fallos: una linea ilegible no descarta el resto. La linea que no se puede interpretar
	 * se conserva como regla {@link State#INVALID} con su motivo, nunca se elimina.
	 *
	 * @param blob el texto guardado, puede ser {@code null} o vacio
	 * @return el mapa de reglas, ordenado por insercion
	 */
	public static Map<Integer, Rule> decodeRules(String blob)
	{
		final Map<Integer, Rule> rules = new LinkedHashMap<>();
		if ((blob == null) || blob.trim().isEmpty())
		{
			return rules;
		}

		for (String rawLine : blob.split("\\r?\\n"))
		{
			final String line = rawLine.trim();
			if (line.isEmpty())
			{
				continue;
			}

			final int eq = line.indexOf('=');
			if (eq <= 0)
			{
				// Sin identificador no hay nada que conservar de forma util: se registra y se sigue.
				rules.put(-rules.size() - 1, Rule.invalid(-1, "linea sin '=' en BOT_SKILL_RULES: " + abbreviate(line)));
				continue;
			}

			final int skillId;
			try
			{
				skillId = Integer.parseInt(line.substring(0, eq).trim());
			}
			catch (NumberFormatException e)
			{
				rules.put(-rules.size() - 1, Rule.invalid(-1, "skillId no numerico: " + abbreviate(line)));
				continue;
			}

			final String payload = line.substring(eq + 1).trim();
			final int semi = payload.indexOf(';');
			if (semi < 0)
			{
				rules.put(skillId, Rule.invalid(skillId, "falta ';' con la prioridad"));
				continue;
			}

			final String enabledToken = payload.substring(0, semi).trim();
			final Boolean enabled;
			if ("1".equals(enabledToken) || "true".equalsIgnoreCase(enabledToken))
			{
				enabled = Boolean.TRUE;
			}
			else if ("0".equals(enabledToken) || "false".equalsIgnoreCase(enabledToken))
			{
				enabled = Boolean.FALSE;
			}
			else
			{
				// Un interruptor ilegible NO se interpreta como OFF silencioso: la regla queda invalida y
				// visible. Interpretarlo como OFF seria peor que fallar, porque desactivaria una habilidad
				// sin que el operador lo sepa.
				rules.put(skillId, Rule.invalid(skillId, "interruptor no reconocido (se esperaba 0/1): " + abbreviate(enabledToken)));
				continue;
			}

			final int priority;
			try
			{
				priority = clampPriority(Integer.parseInt(payload.substring(semi + 1).trim()));
			}
			catch (NumberFormatException e)
			{
				rules.put(skillId, Rule.invalid(skillId, "prioridad no numerica"));
				continue;
			}

			rules.put(skillId, Rule.create(skillId, enabled.booleanValue(), priority, new ArrayList<>()));
		}

		return rules;
	}

	/**
	 * Serializa las condiciones por habilidad.
	 * <p>
	 * Una linea por habilidad: {@code skillId=COND;COND}, y cada condicion
	 * {@code SUJETO~METRICA~OPERADOR~TIPOVALOR~numero}. Solo se serializan las condiciones validas: una
	 * condicion corrupta se descarta aqui para no propagar basura al guardar, y el diagnostico queda en
	 * el motivo de la regla.
	 *
	 * @param rules las reglas
	 * @return el texto, cadena vacia si no hay condiciones que guardar
	 */
	public static String encodeConditions(Map<Integer, Rule> rules)
	{
		if ((rules == null) || rules.isEmpty())
		{
			return "";
		}

		final StringBuilder sb = new StringBuilder();
		for (Rule rule : rules.values())
		{
			if ((rule == null) || rule.getConditions().isEmpty())
			{
				continue;
			}

			final StringBuilder line = new StringBuilder();
			for (Condition condition : rule.getConditions())
			{
				if ((condition == null) || !condition.isValid())
				{
					continue;
				}

				if (line.length() > 0)
				{
					line.append(';');
				}

				line.append(condition.getSubject()).append('~').append(condition.getMetric()).append('~').append(condition.getOperator()).append('~').append(condition.getValueType()).append('~').append(condition.getValue());

				// HURT_COUNT es la unica metrica con dos magnitudes: se emite el sexto campo solo para
				// ella, de modo que las demas condiciones conservan exactamente los cinco campos de antes.
				if (condition.getMetric() == Metric.HURT_COUNT)
				{
					line.append('~').append(condition.getCountLimit());
				}
			}

			if (line.length() == 0)
			{
				continue;
			}

			if (sb.length() > 0)
			{
				sb.append('\n');
			}

			sb.append(rule.getSkillId()).append('=').append(line);
		}

		return sb.toString();
	}

	/**
	 * Deserializa el blob de condiciones y lo fusiona en el mapa de reglas.
	 * <p>
	 * Si el skillId no tiene regla previa se crea una regla deshabilitada con prioridad por defecto,
	 * porque una condicion sin regla es un dato huerfano que conviene conservar y mostrar, no borrar.
	 *
	 * @param blob el texto guardado, puede ser {@code null} o vacio
	 * @param rulesInOut el mapa de reglas, se modifica en sitio
	 */
	public static void decodeConditions(String blob, Map<Integer, Rule> rulesInOut)
	{
		if ((rulesInOut == null) || (blob == null) || blob.trim().isEmpty())
		{
			return;
		}

		for (String rawLine : blob.split("\\r?\\n"))
		{
			final String line = rawLine.trim();
			if (line.isEmpty())
			{
				continue;
			}

			final int eq = line.indexOf('=');
			if (eq <= 0)
			{
				continue;
			}

			final int skillId;
			try
			{
				skillId = Integer.parseInt(line.substring(0, eq).trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}

			final List<Condition> parsed = decodeConditionList(line.substring(eq + 1));
			Rule rule = rulesInOut.get(skillId);
			if (rule == null)
			{
				// Condicion huerfana: se conserva como regla deshabilitada y visible.
				rule = Rule.create(skillId, false, DEFAULT_PRIORITY, parsed);
				rulesInOut.put(skillId, rule);
				continue;
			}

			if (!rule.isValid())
			{
				// La regla ya estaba marcada como invalida: se reconstruye conservando el motivo y
				// adjuntando las condiciones. Usar Rule.create aqui borraba el diagnostico y devolvia la
				// regla a valida durante la carga, que es justo lo que no debe ocurrir.
				rulesInOut.put(skillId, Rule.invalidWith(skillId, rule.getError(), parsed));
				continue;
			}

			// Se reconstruye con las condiciones leidas manteniendo el interruptor y la prioridad.
			rulesInOut.put(skillId, Rule.create(skillId, rule.isEnabled(), rule.getPriority(), parsed));
		}
	}

	/**
	 * Interpreta la lista de condiciones de una linea.
	 *
	 * @param payload el texto despues de {@code skillId=}
	 * @return las condiciones validas leidas; las ilegibles se sustituyen por una condicion invalida
	 */
	public static List<Condition> decodeConditionList(String payload)
	{
		final List<Condition> conditions = new ArrayList<>();
		if ((payload == null) || payload.trim().isEmpty())
		{
			return conditions;
		}

		for (String rawToken : payload.split(";"))
		{
			final String token = rawToken.trim();
			if (token.isEmpty())
			{
				continue;
			}

			if (conditions.size() >= MAX_CONDITIONS)
			{
				break;
			}

			final String[] parts = token.split("~");
			if ((parts.length != 5) && (parts.length != 6))
			{
				conditions.add(Condition.invalid("se esperaban 5 o 6 campos y hay " + parts.length + ": " + abbreviate(token)));
				continue;
			}

			final Subject subject = parseEnum(Subject.class, parts[0]);
			final Metric metric = parseEnum(Metric.class, parts[1]);
			final Operator operator = parseEnum(Operator.class, parts[2]);
			final ValueType valueType = parseEnum(ValueType.class, parts[3]);
			if ((subject == null) || (metric == null) || (operator == null) || (valueType == null))
			{
				conditions.add(Condition.invalid("token desconocido: " + abbreviate(token)));
				continue;
			}

			final int value;
			try
			{
				value = Integer.parseInt(parts[4].trim());
			}
			catch (NumberFormatException e)
			{
				conditions.add(Condition.invalid("valor no numerico: " + abbreviate(token)));
				continue;
			}

			// Sexto campo: cantidad de miembros exigida. Su ausencia es una condicion antigua de cinco
			// campos, que se interpreta con el minimo (P1). El campo solo tiene sentido en HURT_COUNT.
			int countLimit = MIN_COUNT_LIMIT;
			if (parts.length == 6)
			{
				try
				{
					countLimit = Integer.parseInt(parts[5].trim());
				}
				catch (NumberFormatException e)
				{
					conditions.add(Condition.invalid("limite de recuento no numerico: " + abbreviate(token)));
					continue;
				}

				if ((metric != Metric.HURT_COUNT) && (countLimit != MIN_COUNT_LIMIT))
				{
					conditions.add(Condition.invalid("el limite de recuento solo aplica a HURT_COUNT: " + abbreviate(token)));
					continue;
				}
			}

			final String combinationError = validateCombination(subject, metric, valueType);
			if (combinationError != null)
			{
				conditions.add(Condition.invalid(combinationError));
				continue;
			}

			if (metric == Metric.HURT_COUNT)
			{
				// En HURT_COUNT el valor es el umbral de HP, no un porcentaje generico: se acota aqui
				// porque el tipo de valor es ABSOLUTE y la comprobacion de PERCENT no lo cubriria.
				if ((value < 0) || (value > 100))
				{
					conditions.add(Condition.invalid("umbral de HP fuera de 0..100: " + value));
					continue;
				}

				if ((countLimit < MIN_COUNT_LIMIT) || (countLimit > MAX_COUNT_LIMIT))
				{
					conditions.add(Condition.invalid("limite de recuento fuera de " + MIN_COUNT_LIMIT + ".." + MAX_COUNT_LIMIT + ": " + countLimit));
					continue;
				}
			}

			if ((valueType == ValueType.PERCENT) && ((value < 0) || (value > 100)))
			{
				conditions.add(Condition.invalid("porcentaje fuera de 0..100: " + value));
				continue;
			}

			conditions.add(new Condition(subject, metric, operator, valueType, value, countLimit));
		}

		return conditions;
	}

	// ---------------------------------------------------------------------------------------------
	// Resolucion de estado
	// ---------------------------------------------------------------------------------------------

	/**
	 * Resuelve el estado de una habilidad.
	 * <p>
	 * El orden de precedencia es deliberado: primero se valida la regla, despues se comprueba el
	 * conflicto con soporte, y solo al final se mira la puerta maestra. Asi una regla corrupta se
	 * reporta como {@link State#INVALID} con su motivo aunque el sistema este apagado.
	 *
	 * @param rules las reglas cargadas, puede ser {@code null}
	 * @param skillId la habilidad consultada
	 * @param systemActive si la puerta maestra esta en 1
	 * @param supportSkill si la habilidad es de soporte (heal, dispel o resurreccion)
	 * @return el estado resuelto
	 */
	public static State resolveState(Map<Integer, Rule> rules, int skillId, boolean systemActive, boolean supportSkill)
	{
		if ((rules == null) || rules.isEmpty())
		{
			return State.NO_RULE;
		}

		final Rule rule = rules.get(skillId);
		if (rule == null)
		{
			return State.NO_RULE;
		}

		if (!rule.isValid())
		{
			return State.INVALID;
		}

		if (supportSkill)
		{
			return State.CONFLICT;
		}

		if (!systemActive)
		{
			return State.INERT;
		}

		return rule.isEnabled() ? State.ENABLED : State.DISABLED;
	}

	/**
	 * Cuenta los estados presentes en un mapa de reglas, util para el diagnostico y para el resumen
	 * que mostrara el panel.
	 *
	 * @param rules las reglas
	 * @param systemActive si la puerta maestra esta en 1
	 * @return un mapa con un contador por estado, nunca {@code null}
	 */
	public static Map<State, Integer> countStates(Map<Integer, Rule> rules, boolean systemActive)
	{
		return countStates(rules, systemActive, null);
	}

	/**
	 * Cuenta los estados presentes en un mapa de reglas, incluido {@link State#CONFLICT}.
	 * <p>
	 * La clasificacion replica paso a paso la precedencia de {@link #resolveState}: invalidez, despues
	 * conflicto con soporte, despues la puerta maestra y por ultimo el interruptor. Asi el recuento y la
	 * resolucion por habilidad no pueden discrepar.
	 * <p>
	 * Sin la informacion de soporte, un conflicto es indistinguible de una regla valida normal, porque
	 * depende del {@code TargetType} y los efectos de la habilidad, que esta clase no conoce a proposito.
	 * Por eso el conjunto se recibe como parametro: quien lo omita obtiene {@code CONFLICT = 0} de forma
	 * <b>honesta</b>, no porque el recuento sea estructuralmente incapaz de detectarlo.
	 *
	 * @param rules las reglas
	 * @param systemActive si la puerta maestra esta en 1
	 * @param supportSkillIds habilidades de soporte conocidas, puede ser {@code null} o vacio
	 * @return un mapa con un contador por estado, nunca {@code null}
	 */
	public static Map<State, Integer> countStates(Map<Integer, Rule> rules, boolean systemActive, Set<Integer> supportSkillIds)
	{
		final Map<State, Integer> counts = new EnumMap<>(State.class);
		for (State state : State.values())
		{
			counts.put(state, Integer.valueOf(0));
		}

		if (rules == null)
		{
			return counts;
		}

		for (Rule rule : rules.values())
		{
			if (rule == null)
			{
				continue;
			}

			// Misma precedencia que resolveState: INVALID gana a CONFLICT, y ambas a la puerta maestra.
			final State state;
			if (!rule.isValid())
			{
				state = State.INVALID;
			}
			else if ((supportSkillIds != null) && supportSkillIds.contains(Integer.valueOf(rule.getSkillId())))
			{
				state = State.CONFLICT;
			}
			else if (!systemActive)
			{
				state = State.INERT;
			}
			else
			{
				state = rule.isEnabled() ? State.ENABLED : State.DISABLED;
			}

			counts.put(state, Integer.valueOf(counts.get(state).intValue() + 1));
		}

		return counts;
	}

	// ---------------------------------------------------------------------------------------------
	// Utilidades internas
	// ---------------------------------------------------------------------------------------------

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String token)
	{
		if (token == null)
		{
			return null;
		}

		try
		{
			return Enum.valueOf(type, token.trim().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static String abbreviate(String text)
	{
		if (text == null)
		{
			return "";
		}

		final String flat = text.replace('\n', ' ').replace('\r', ' ');
		return (flat.length() <= 48) ? flat : (flat.substring(0, 45) + "...");
	}
}
