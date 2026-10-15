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
package handlers.bypass;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IBypassHandler;

import handlers.chat.commands.admin.AdminBotManager;

/**
 * Bypass entry point of the Bot Manager window buttons, registered as {@code "bot"}.<br>
 * Reached through {@code RequestBypassToServer} -> {@code BypassHandler}, which resolves the
 * handler from the first token of the command. This path contains no permission layer: it never
 * crosses {@code AdminCommandHandler}, never consults {@code AdminData} and therefore never
 * triggers an administrative confirmation dialog. The buttons work for any connected player.<br>
 * <br>
 * Accepted forms are {@code bot <argument>} and {@code bot <argument> ui}. The trailing
 * {@code ui} token is emitted only by the window buttons and is what asks the Bot Manager to
 * rebuild the panel after the operation, so the panel stays in sync with real server state.
 * Plain voiced chat commands never carry the token and keep their previous behaviour.<br>
 * <br>
 * {@link #BYPASS_ROOT} must stay in sync with the prefix emitted by the Bot Manager window.
 */
public class BotManagerBypass implements IBypassHandler
{
	/** Must match the literal used by the Bot Manager window buttons. */
	private static final String BYPASS_ROOT = "bot";
	/** Trailing token appended by the window buttons so only in-window clicks rebuild the panel. */
	private static final String UI_MARKER = "ui";
	
	private static final String[] COMMANDS =
	{
		BYPASS_ROOT
	};
	
	@Override
	public boolean onCommand(String command, Player player, Creature target)
	{
		if (player == null)
		{
			return false;
		}
		
		final String remainder = stripRoot(command);
		if (remainder == null)
		{
			return false;
		}
		
		// "bot <argument> ui" -> argument "<argument>", fromUi true. "bot <argument>" -> plain.
		boolean fromUi = false;
		String argument = remainder;
		final int lastSpace = remainder.lastIndexOf(' ');
		if (lastSpace >= 0)
		{
			final String tail = remainder.substring(lastSpace + 1);
			if (UI_MARKER.equalsIgnoreCase(tail))
			{
				fromUi = true;
				argument = remainder.substring(0, lastSpace);
			}
		}
		
		return AdminBotManager.dispatchCommand(player, argument.trim(), fromUi);
	}
	
	/**
	 * @param command the raw bypass string as received from the client
	 * @return everything after the {@code bot} root token, or {@code null} when the command is not a Bot Manager bypass
	 */
	private static String stripRoot(String command)
	{
		if ((command == null) || !command.toLowerCase().startsWith(BYPASS_ROOT))
		{
			return null;
		}
		
		final int rootEnd = command.indexOf(' ');
		if (rootEnd < 0)
		{
			// Bare "bot": no argument, so the window is opened without a panel refresh.
			return "";
		}
		
		return command.substring(rootEnd + 1);
	}
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}