/*
 * MIT License
 *
 * Copyright (c) 2019 Shimmermare
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.shimmermare.inviteroles;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.priv.PrivateMessageReceivedEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

import static com.shimmermare.inviteroles.Utils.censorInviteCode;

public class EventListener extends ListenerAdapter
{
    public static final Logger LOGGER = LoggerFactory.getLogger(EventListener.class);

    private final InviteRoles bot;

    public EventListener(InviteRoles bot)
    {
        this.bot = bot;
    }

    @Override
    public void onGuildMemberJoin(@Nonnull GuildMemberJoinEvent event)
    {
        Guild guild = event.getGuild();
        Member member = event.getMember();

        ServerInstance instance = bot.getServerInstance(guild.getIdLong());
        if (instance == null)
        {
            LOGGER.error("ServerInstance of server {} shouldn't but it is null.", guild.getIdLong());
            return;
        }
        ServerSettings settings = instance.getServerSettings();

        InviteTracker inviteTracker = instance.getInviteTracker();
        inviteTracker.update();
        Map<String, Integer> inviteUsesDelta = inviteTracker.getUsesDelta();
        if (inviteUsesDelta.isEmpty())
        {
            if (!member.getUser().isBot())
            {
                LOGGER.error("No invite delta found after new user {} joined server {}. This is shouldn't be possible.",
                        member.getIdLong(), guild.getIdLong());
            }
            else
            {
                LOGGER.debug("Another bot {} joined server {}, no invite delta as expected",
                        member.getIdLong(), guild.getIdLong());
            }
            return;
        }
        else if (inviteUsesDelta.size() > 1)
        {
            LOGGER.info("Two or more users joined server {} between invite tracker updates.", guild.getIdLong());
            instance.sendWarning("Two or more users joined server at the exact same time! " +
                    "Unfortunately Discord doesn't tell which invite was used by whom, " +
                    "so no invite roles will be granted and you should do this manually.");
            return;
        }

        String inviteCode = inviteUsesDelta.keySet().stream().findFirst().orElseThrow(RuntimeException::new);
        long roleId = settings.getInviteRole(inviteCode);
        if (roleId == 0)
        {
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null)
        {
            settings.removeInviteRole(inviteCode);
            instance.flagUpdated();
            instance.sendWarning("Can't grant invite role `I:" + censorInviteCode(inviteCode)
                    + "/R:n/a (" + roleId + ")`: role doesn't exist. Invite role is removed.");
            LOGGER.debug("Invite role {}/{} at server {} doesn't exist. Invite role is removed.",
                    roleId, inviteCode, guild.getIdLong());
            return;
        }
        if (!guild.getSelfMember().canInteract(role))
        {
            settings.removeInviteRole(inviteCode);
            instance.flagUpdated();
            instance.sendWarning("Can't grant invite role `I:" + censorInviteCode(inviteCode)
                    + "/R:" + role.getName() + " (" + roleId + ")`: insufficient permissions. Invite role is removed.");
            LOGGER.debug("Can't grant invite role {}/{} at server {} " +
                            "as bot doesn't have enough permissions. Invite role is removed.",
                    inviteCode, roleId, guild.getIdLong());
            return;
        }
        guild.addRoleToMember(member, role).reason("Invite role (" + censorInviteCode(inviteCode) + ")").queue();
    }

    @Override
    public void onGuildJoin(@Nonnull GuildJoinEvent event)
    {
        Guild guild = event.getGuild();
        bot.newServerInstance(guild);
        LOGGER.info("The bot has joined server {}. Hello!", guild.getIdLong());
    }

    @Override
    public void onGuildLeave(@Nonnull GuildLeaveEvent event)
    {
        Guild guild = event.getGuild();
        bot.removeServerInstance(guild.getIdLong());
        LOGGER.info("The bot has left server {}. Bye!", guild.getIdLong());
    }

    @Override
    public void onRoleDelete(@Nonnull RoleDeleteEvent event)
    {
        Guild guild = event.getGuild();
        ServerInstance instance = bot.getServerInstance(guild.getIdLong());
        ServerSettings settings = instance.getServerSettings();

        for (Map.Entry<String, Long> entry : new HashMap<>(settings.getInviteRoles()).entrySet())
        {
            if (event.getRole().getIdLong() == entry.getValue())
            {
                settings.removeInviteRole(entry.getKey());
                LOGGER.debug("Invite role {}/{} on server {} is removed because role itself was removed.",
                        entry.getKey(), entry.getValue(), guild.getIdLong());
                break;
            }
        }
    }

    @Override
    public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event)
    {
        Guild guild = event.getGuild();
        ServerInstance instance = bot.getServerInstance(guild.getIdLong());
        CommandDispatcher<CommandSource> dispatcher = bot.getCommandDispatcher();
        Member member = event.getMember();
        Message message = event.getMessage();
        TextChannel channel = message.getTextChannel();

        if (member == null)
        {
            //no commands from webhooks
            return;
        }
        String content = message.getContentRaw();
        if (content.length() < 2 || content.charAt(0) != '/')
        {
            return;
        }

        CommandSource source = new CommandSource(instance, channel, member);
        ParseResults<CommandSource> parseResults = dispatcher.parse(content.substring(1), source);
        if (parseResults.getContext().getNodes().isEmpty())
        {
            return;
        }
        try
        {
            int result = dispatcher.execute(parseResults);
            LOGGER.debug("Command executed with result {} on server {} issued by user {} " +
                            "in channel {} in message {} with content '{}'.",
                    result, guild.getIdLong(), member.getIdLong(), channel.getIdLong(), message.getIdLong(), content);
        }
        catch (CommandSyntaxException e)
        {
            channel.sendMessage("Command syntax error! " + e.getMessage()).queue();
            LOGGER.debug("Command failed to execute on server {} issued by user {} " +
                            "in channel {} in message {} with content '{}'.",
                    guild.getIdLong(), member.getIdLong(), channel.getIdLong(), message.getIdLong(), content, e);
        }
    }

    @Override
    public void onPrivateMessageReceived(@Nonnull PrivateMessageReceivedEvent event)
    {
        User author = event.getAuthor();
        if (author.isBot() || author.isFake())
        {
            return;
        }

        Message message = event.getMessage();
        PrivateChannel channel = message.getPrivateChannel();

        Guild guild = bot.getServerInstance(542666886899302400).getServer();
        //Send thank you message if this is first time feedback
        // channel.getHistoryBefore(message, 1).queue(history ->
        // {
        //     if (history.getRetrievedHistory().isEmpty())
        //     {
        //         channel.sendMessage("**Thank you for feedback!** " +
        //                 "\n• If you found a bug or have a suggestion, please create an issue at GitHub page: https://github.com/Shimmermare/InviteRoles " +
        //                 "\n• If you want to contact creator, use PM: <@474857988075683850>").queue();
        //     }
        // });
        if(message.getContentStripped().toLowerCase().contains("zoomer")) {
            Role zoomerRole = guild.getRoleById(691178944585793576);
            guild.addRoleToMember(author, zoomerRole).reason("Invite to Zoomer by DM").queue();
            channel.sendMessage("Great, you've been added to Zoomer.").queue();
        }
        if(message.getContentStripped().toLowerCase().contains("visor")) {
            Role visorRole = guild.getRoleById(691178944585793576);
            guild.addRoleToMember(author, visorRole).reason("Invite to Visor by DM").queue();
            channel.sendMessage("Great, you've been added to Visor.").queue();        
        }
        

    }
}
