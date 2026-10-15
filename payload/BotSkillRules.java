package handlers.custom.bot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;

/**
 * BOTAI - Skill Intelligence, FASE 1: persistencia inerte de las reglas por habilidad.
 * <p>
 * <b>Esta clase no ejecuta nada.</b> Solo guarda, carga y clasifica reglas en el almacen clave-valor
 * que Mobius ya usa para la configuracion por personaje ({@code character_variables}). No importa
 * {@code AutoUseTaskManager}, {@code AutoPlayTaskManager} ni ninguna lista nativa, y no las modifica.
 * <p>
 * <b>Puerta maestra.</b> Una regla guardada <b>no</b> surte efecto por existir: hace falta que
 * {@link #isSystemActive(Player)} devuelva {@code true}, lo que solo ocurre si la clave
 * {@link #KEY_ACTIVE} vale 1 y fue escrita de forma explicita. Con la clave ausente, el valor es 0 y
 * el sistema esta completamente inerte.
 * <p>
 * Las claves empleadas son literales, siguiendo la convencion ya existente en el proyecto para
 * {@code BOT_ASSIST}, {@code BOT_SPOIL}, {@code BOT_FIGHTBACK} y {@code BOT_TSEARCH}. No requieren
 * ninguna constante nueva en el core ni cambios de esquema: {@code character_variables.val} es un
 * campo de texto.
 *
 * @author BOTAI
 */
public final class BotSkillRules
{
	/** Blob con una linea por regla: {@code skillId=enabled;priority}. */
	public static final String KEY_RULES = "BOT_SKILL_RULES";

	/** Blob con una linea por habilidad: {@code skillId=COND;COND}. */
	public static final String KEY_CONDITIONS = "BOT_SKILL_CONDITIONS";

	/** Version del esquema de serializacion. */
	public static final String KEY_VERSION = "BOT_SKILL_RULES_VERSION";

	/** Puerta maestra del sistema. Ausente equivale a 0. */
	public static final String KEY_ACTIVE = "BOT_SKILL_RULES_ACTIVE";

	private BotSkillRules()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Puerta maestra
	// ---------------------------------------------------------------------------------------------

	/**
	 * Indica si el sistema de reglas esta activo para el personaje.
	 * <p>
	 * <b>Invariante de la fase:</b> la clave ausente devuelve {@code false}. Ninguna operacion de
	 * carga, guardado, migracion o inicializacion escribe esta clave: solo lo hace el operador de forma
	 * explicita.
	 *
	 * @param player el personaje
	 * @return {@code true} solo si la clave vale 1
	 */
	public static boolean isSystemActive(Player player)
	{
		if ((player == null) || (player.getVariables() == null))
		{
			return false;
		}

		return player.getVariables().getInt(KEY_ACTIVE, 0) == 1;
	}

	/**
	 * Escribe la puerta maestra. Es la unica via de activacion y debe invocarse solo desde una accion
	 * explicita del operador.
	 *
	 * @param player el personaje
	 * @param active el valor a persistir
	 */
	public static void setSystemActive(Player player, boolean active)
	{
		if ((player == null) || (player.getVariables() == null))
		{
			return;
		}

		player.getVariables().set(KEY_ACTIVE, active ? 1 : 0);
	}

	// ---------------------------------------------------------------------------------------------
	// Carga y guardado
	// ---------------------------------------------------------------------------------------------

	/**
	 * Carga las reglas del personaje y resuelve su estado.
	 * <p>
	 * Tolerante a datos corruptos: las lineas ilegibles se conservan como reglas invalidas con su
	 * motivo. La version del esquema se lee pero no altera los datos: una version mayor desconocida se
	 * registra en {@link Result#getVersionNote()} sin descartar nada.
	 *
	 * @param player el personaje
	 * @return el resultado de la carga, nunca {@code null}
	 */
	public static Result load(Player player)
	{
		final Map<Integer, SkillRules.Rule> rules = new LinkedHashMap<>();
		if ((player == null) || (player.getVariables() == null))
		{
			return new Result(rules, false, 0, "sin personaje");
		}

		final PlayerVariables variables = player.getVariables();
		final boolean active = variables.getInt(KEY_ACTIVE, 0) == 1;
		final int version = variables.getInt(KEY_VERSION, 0);
		final String versionNote;
		if (version == 0)
		{
			versionNote = "sin version guardada";
		}
		else if (version > SkillRules.SCHEMA_VERSION)
		{
			versionNote = "version " + version + " mayor que la soportada " + SkillRules.SCHEMA_VERSION + ": se conserva sin tocar";
		}
		else
		{
			versionNote = "version " + version;
		}

		SkillRules.decodeConditions(variables.getString(KEY_CONDITIONS, ""), rules);
		final Map<Integer, SkillRules.Rule> fromRules = SkillRules.decodeRules(variables.getString(KEY_RULES, ""));

		// Las condiciones mandan sobre su propia linea, pero el interruptor y la prioridad son de la
		// regla: se fusionan respetando ambas fuentes.
		for (Map.Entry<Integer, SkillRules.Rule> entry : fromRules.entrySet())
		{
			final SkillRules.Rule declared = entry.getValue();
			final SkillRules.Rule withConditions = rules.get(entry.getKey());
			if ((withConditions == null) || withConditions.getConditions().isEmpty())
			{
				rules.put(entry.getKey(), declared);
				continue;
			}

			if (!declared.isValid())
			{
				rules.put(entry.getKey(), declared);
				continue;
			}

			rules.put(entry.getKey(), SkillRules.Rule.create(declared.getSkillId(), declared.isEnabled(), declared.getPriority(), withConditions.getConditions()));
		}

		return new Result(rules, active, version, versionNote);
	}

	/**
	 * Guarda las reglas del personaje.
	 * <p>
	 * Escribe las tres claves de datos y la version. <b>No toca {@link #KEY_ACTIVE}</b>: guardar nunca
	 * activa el sistema.
	 *
	 * @param player el personaje
	 * @param rules las reglas
	 */
	public static void save(Player player, Map<Integer, SkillRules.Rule> rules)
	{
		if ((player == null) || (player.getVariables() == null))
		{
			return;
		}

		final PlayerVariables variables = player.getVariables();
		variables.set(KEY_RULES, SkillRules.encodeRules(rules));
		variables.set(KEY_CONDITIONS, SkillRules.encodeConditions(rules));
		variables.set(KEY_VERSION, SkillRules.SCHEMA_VERSION);
	}

	/**
	 * Elimina datos del sistema de reglas, devolviendo al personaje al comportamiento puramente nativo.
	 * <p>
	 * Solo se invoca desde una accion explicita de restauracion. No altera
	 * {@code AUTO_USE_SKILLS} ni {@code AUTO_USE_BUFFS}.
	 *
	 * @param player el personaje
	 */
	public static void clearAll(Player player)
	{
		if ((player == null) || (player.getVariables() == null))
		{
			return;
		}

		final PlayerVariables variables = player.getVariables();
		variables.remove(KEY_RULES);
		variables.remove(KEY_CONDITIONS);
		variables.remove(KEY_VERSION);
		variables.remove(KEY_ACTIVE);
	}

	// ---------------------------------------------------------------------------------------------
	// Estado por habilidad
	// ---------------------------------------------------------------------------------------------

	/**
	 * Resuelve el estado de una habilidad concreta para el personaje indicado.
	 *
	 * @param player el personaje
	 * @param rules las reglas cargadas
	 * @param skillId la habilidad
	 * @param supportSkill si la habilidad es de soporte
	 * @return el estado resuelto
	 */
	public static SkillRules.State resolveState(Player player, Map<Integer, SkillRules.Rule> rules, int skillId, boolean supportSkill)
	{
		return SkillRules.resolveState(rules, skillId, isSystemActive(player), supportSkill);
	}

	/**
	 * Predicado que responde si una habilidad estaria gobernada por regla.
	 * <p>
	 * <b>NO ESTA CONECTADO A NADA EN LA FASE 1.</b> No se invoca desde ningun bucle, no filtra nada y
	 * no altera la ejecucion de {@code .play}. Se expone unicamente para diagnostico y para que la fase
	 * que arbitre los turnos pueda consumirlo. Devuelve {@code false} en {@link SkillRules.State#NO_RULE}
	 * y en {@link SkillRules.State#INERT}.
	 *
	 * @param state el estado resuelto
	 * @return {@code true} si la habilidad queda fuera de las rutas nativas
	 */
	public static boolean isManagedByRule(SkillRules.State state)
	{
		switch (state)
		{
			case ENABLED:
			case DISABLED:
			case INVALID:
			case CONFLICT:
			{
				return true;
			}
			default:
			{
				return false;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Diagnostico
	// ---------------------------------------------------------------------------------------------

	/**
	 * Resumen legible del estado de las reglas de un personaje.
	 * <p>
	 * Sin la lista de habilidades de soporte, el campo CONFLICTO sale siempre a 0. Para obtener un
	 * recuento real de conflictos hay que usar la variante que recibe esas habilidades, porque el
	 * conflicto depende del tipo de objetivo y los efectos de la habilidad, que esta capa no conoce.
	 *
	 * @param player el personaje
	 * @param rules las reglas
	 * @return el resumen
	 */
	public static String describe(Player player, Map<Integer, SkillRules.Rule> rules)
	{
		return describe(player, rules, null);
	}

	/**
	 * Resumen legible del estado de las reglas de un personaje, con recuento real de conflictos.
	 *
	 * @param player el personaje
	 * @param rules las reglas
	 * @param supportSkillIds habilidades de soporte conocidas, puede ser {@code null} o vacio
	 * @return el resumen
	 */
	public static String describe(Player player, Map<Integer, SkillRules.Rule> rules, Set<Integer> supportSkillIds)
	{
		final boolean active = isSystemActive(player);
		final Map<SkillRules.State, Integer> counts = SkillRules.countStates(rules, active, supportSkillIds);
		final StringBuilder sb = new StringBuilder();
		sb.append("BOT_SKILL_RULES: sistema ").append(active ? "ACTIVO (ACTIVE=1)" : "INACTIVO (ACTIVE=0, sistema inerte)");
		sb.append(" | reglas=").append(rules == null ? 0 : rules.size());
		sb.append(" | CONFIGURADAS=").append(counts.get(SkillRules.State.ENABLED));
		sb.append(" DESHABILITADAS=").append(counts.get(SkillRules.State.DISABLED));
		sb.append(" INERTES=").append(counts.get(SkillRules.State.INERT));
		sb.append(" INVALIDAS=").append(counts.get(SkillRules.State.INVALID));
		sb.append(" CONFLICTO=").append(counts.get(SkillRules.State.CONFLICT));
		return sb.toString();
	}

	/** Resultado de una carga: reglas, puerta maestra, version y nota de version. */
	public static final class Result
	{
		private final Map<Integer, SkillRules.Rule> rules;
		private final boolean active;
		private final int version;
		private final String versionNote;

		private Result(Map<Integer, SkillRules.Rule> rules, boolean active, int version, String versionNote)
		{
			this.rules = rules;
			this.active = active;
			this.version = version;
			this.versionNote = versionNote;
		}

		public Map<Integer, SkillRules.Rule> getRules()
		{
			return rules;
		}

		public boolean isActive()
		{
			return active;
		}

		public int getVersion()
		{
			return version;
		}

		public String getVersionNote()
		{
			return versionNote;
		}
	}
}
