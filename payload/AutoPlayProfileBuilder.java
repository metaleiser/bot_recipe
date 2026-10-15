package handlers.custom.bot;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.player.AutoPlaySettingsHolder;
import org.l2jmobius.gameserver.entity.actor.holders.player.AutoUseSettingsHolder;

/**
 * Applies the two automatic decisions the Bot Manager takes on a character that has no usable native
 * AutoPlay configuration: whether it may use the basic weapon attack, and that it never auto picks up.<br>
 * <br>
 * <b>Scope.</b> This class deliberately does <b>not</b> select skills or buffs. The offensive rotation and
 * the buff list stay exactly as the character defines them through the native {@code .play} command or
 * through the persisted {@code AUTO_USE_*} rows, so an operator keeps full control over what a bot casts. The
 * semantic analyzer is not consulted here at all; producing a rotation from it is a separate, later step.<br>
 * <br>
 * <b>Nothing is persisted.</b> Both decisions live in the runtime holders only, and the persisted
 * {@code character_variables} rows are never written or deleted.
 */
public final class AutoPlayProfileBuilder
{
	/**
	 * The only meaningful value of {@code AUTO_USE_ACTIONS}. The native AutoPlay task manager reads it as
	 * {@code !actions.contains(2) -> treat as mage caster}, so it enables the basic attack of the currently
	 * equipped weapon. Melee, polearm or ranged is resolved afterwards by the combat engine from the weapon
	 * type, which is why a single value covers every physical class.
	 */
	private static final int AUTO_ATTACK_ACTION = 2;

	private AutoPlayProfileBuilder()
	{
	}

	/**
	 * Enables the basic attack for a physical class.
	 * @param player the character to configure
	 * @return true when the character was touched, false when it was skipped
	 */
	public static boolean build(Player player)
	{
		if (player == null)
		{
			return false;
		}

		final AutoUseSettingsHolder autoUse = player.getAutoUseSettings();
		if (autoUse == null)
		{
			return false;
		}

		// Auto loot keeps whatever the player already declares. The native holder is the single source of
		// truth and it already persists the choice into AUTO_USE_SETTINGS, so forcing it here would only
		// overwrite a decision the player made from the native .play window. The fleet wide default lives
		// in Player.ini instead and the recipe writes it there.

		// A mage class is handled by the caster branch of the native task manager and never closes in
		// melee, so it is left without the basic attack action.
		// <b>No fallback, on purpose.</b> Adding the basic attack to a mage with an empty skill list was tried
		// and reverted: isMageCaster() is decided by the absence of that very action, so adding it moves the
		// character out of the caster branch and it stops casting its rotation altogether. A mage without an
		// offensive skill is fixed by giving it one, never by turning it into a basic attacker.
		if (!player.getPlayerClass().isMage() && autoUse.getAutoActions().isEmpty())
		{
			autoUse.getAutoActions().add(AUTO_ATTACK_ACTION);
		}

		// The skill and buff lists are never populated here: they keep whatever the character declares.
		return true;
	}
}
