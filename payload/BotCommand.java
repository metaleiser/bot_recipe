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
package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;

import handlers.chat.commands.admin.AdminBotManager;

/**
 * Voiced entry point of the Bot Manager: typed as {@code .bot} in the general chat channel.<br>
 * Reached through {@code ChatGeneral} -> {@code VoicedCommandHandler}, which are plain
 * dispatch maps with no permission layer: no {@code isGM()} check, no access level check,
 * no {@code AdminData} lookup and no administrative confirmation exist on this path.<br>
 * The command is therefore available to any connected {@link Player}, including access level 0
 * characters.<br>
 * <br>
 * Authorization is delegated to {@link AdminBotManager#dispatchCommand}, which keeps the single
 * existing gameplay guard: a player who already belongs to a party must be its leader before bots
 * can be added. A player with no party has no requirement to satisfy: the first activated bot
 * creates the party and the player becomes its leader.<br>
 * <br>
 * Ownership is not defined here. The characters a player may administer are exactly the ones
 * Mobius exposes through {@code GameClient.getCharSelection(slot - 1)}, i.e. the account's own
 * character list. No new ownership store is introduced.
 */
public class BotCommand implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"bot"
	};
	
	@Override
	public boolean onCommand(String command, Player activeChar, String params)
	{
		return AdminBotManager.dispatchCommand(activeChar, params == null ? "" : params.trim(), false);
	}
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}