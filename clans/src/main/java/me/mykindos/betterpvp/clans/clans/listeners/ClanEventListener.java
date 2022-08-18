package me.mykindos.betterpvp.clans.clans.listeners;

import com.google.inject.Inject;
import me.mykindos.betterpvp.clans.Clans;
import me.mykindos.betterpvp.clans.clans.Clan;
import me.mykindos.betterpvp.clans.clans.ClanManager;
import me.mykindos.betterpvp.clans.clans.events.*;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.components.clans.data.ClanAlliance;
import me.mykindos.betterpvp.core.components.clans.data.ClanEnemy;
import me.mykindos.betterpvp.core.components.clans.data.ClanMember;
import me.mykindos.betterpvp.core.components.clans.data.ClanTerritory;
import me.mykindos.betterpvp.core.components.clans.events.ClanEvent;
import me.mykindos.betterpvp.core.config.Config;
import me.mykindos.betterpvp.core.framework.events.scoreboard.ScoreboardUpdateEvent;
import me.mykindos.betterpvp.core.framework.inviting.InviteHandler;
import me.mykindos.betterpvp.core.gamer.Gamer;
import me.mykindos.betterpvp.core.gamer.GamerManager;
import me.mykindos.betterpvp.core.gamer.exceptions.NoSuchGamerException;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import me.mykindos.betterpvp.core.utilities.UtilWorld;
import me.mykindos.betterpvp.core.world.blocks.WorldBlockHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.util.Optional;

@BPvPListener
public class ClanEventListener extends ClanListener {

    private final InviteHandler inviteHandler;
    private final WorldBlockHandler blockHandler;
    private final Clans clans;

    @Inject
    @Config(path = "clans.members.max", defaultValue = "6")
    private int maxClanMembers;

    @Inject
    public ClanEventListener(Clans clans, ClanManager clanManager, GamerManager gamerManager, InviteHandler inviteHandler,
                             WorldBlockHandler blockHandler) {
        super(clanManager, gamerManager);
        this.clans = clans;
        this.inviteHandler = inviteHandler;
        this.blockHandler = blockHandler;
    }

    @EventHandler
    public void onClanEvent(ClanEvent<Clan> event) {
        if (event.isGlobalScoreboardUpdate()) {
            Bukkit.getOnlinePlayers().forEach(player -> UtilServer.runTaskLater(clans,
                    () -> UtilServer.callEvent(new ScoreboardUpdateEvent(player)), 5));
        } else {
            UtilServer.runTaskLater(clans, () -> UtilServer.callEvent(new ScoreboardUpdateEvent(event.getPlayer())), 5);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkClaim(ChunkClaimEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        Clan clan = (Clan) event.getClan();

        String chunkString = UtilWorld.chunkToFile(player.getLocation().getChunk());
        clan.getTerritory().add(new ClanTerritory(chunkString));
        clanManager.getRepository().saveClanTerritory(clan, chunkString);

        UtilMessage.message(player, "Clans", "You claimed Territory " + ChatColor.YELLOW
                + UtilWorld.chunkToPrettyString(player.getLocation().getChunk()) + ChatColor.GRAY + ".");

        clan.messageClan(ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " claimed Territory " + ChatColor.YELLOW
                + UtilWorld.chunkToPrettyString(player.getLocation().getChunk()) + ChatColor.GRAY + ".", player.getUniqueId(), true);

        blockHandler.outlineChunk(player.getLocation().getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanCreate(ClanCreateEvent event) {

        Clan clan = event.getClan();

        clan.getMembers().add(new ClanMember(event.getPlayer().getUniqueId().toString(), ClanMember.MemberRank.LEADER));

        clanManager.addObject(clan.getName().toLowerCase(), clan);
        clanManager.getRepository().save(clan);

        UtilMessage.message(event.getPlayer(), "Clans", "Successfully created clan " + ChatColor.AQUA + clan.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanDisband(ClanDisbandEvent event) {

        if (event.isCancelled()) {
            return;
        }

        Clan clan = event.getClan();

        clan.getMembers().clear();
        clan.getTerritory().clear();

        for (ClanAlliance alliance : clan.getAlliances()) {
            alliance.getClan().getAlliances().removeIf(ally -> ally.getClan().getName().equalsIgnoreCase(clan.getName()));
        }
        clan.getAlliances().clear();

        for (ClanEnemy enemy : clan.getEnemies()) {
            enemy.getClan().getEnemies().removeIf(en -> en.getClan().getName().equalsIgnoreCase(clan.getName()));
        }
        clan.getAlliances().clear();

        clanManager.getRepository().delete(clan);
        clanManager.getObjects().remove(clan.getName());

        UtilMessage.broadcast("Clans", ChatColor.YELLOW + event.getPlayer().getName() + ChatColor.GRAY + " disbanded "
                + ChatColor.YELLOW + "Clan " + clan.getName() + ChatColor.GRAY + ".");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanInviteMember(ClanInviteMemberEvent event) {
        if (event.isCancelled()) return;

        Clan clan = event.getClan();
        Player player = event.getPlayer();
        Player target = event.getTarget();


        UtilMessage.message(player, "Clans", "You invited " + ChatColor.YELLOW + target.getName() + ChatColor.GRAY + " to join your Clan.");
        clan.messageClan(ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " invited " + ChatColor.YELLOW
                + target.getName() + ChatColor.GRAY + " to join your Clan.", player.getUniqueId(), true);


        UtilMessage.message(target, "Clans", ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " invited you to join " + ChatColor.YELLOW
                + "Clan " + clan.getName() + ChatColor.GRAY + ".");

        Component inviteMessage = Component.text(ChatColor.GOLD.toString() + ChatColor.UNDERLINE + "Click Here")
                .clickEvent(ClickEvent.runCommand("/c join " + clan.getName()))
                .append(Component.text(ChatColor.GRAY + " or type '"
                        + ChatColor.YELLOW + "/c join " + clan.getName() + ChatColor.GRAY + "'" + ChatColor.GRAY + " to accept!"));
        UtilMessage.message(target, "Clans", inviteMessage);

        Gamer targetGamer = gamerManager.getObject(target.getUniqueId().toString()).orElseThrow(() -> new NoSuchGamerException(target.getName()));
        inviteHandler.createInvite(clan, targetGamer, "Invite", 20);

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinClanEvent(MemberJoinClanEvent event) {
        if (event.isCancelled()) return;

        Clan clan = event.getClan();
        Player player = event.getPlayer();
        Gamer targetGamer = gamerManager.getObject(player.getUniqueId().toString()).orElseThrow(() -> new NoSuchGamerException(player.getName()));

        if (!targetGamer.getClient().isAdministrating()) {
            if (!inviteHandler.isInvited(targetGamer, clan, "Invite")) {
                UtilMessage.message(player, "Clans", "You are not invited to " + ChatColor.YELLOW + "Clan "
                        + clan.getName() + ChatColor.GRAY + ".");
                return;
            }

            if (clan.getSquadCount() >= maxClanMembers) {
                UtilMessage.message(player, "Clans", ChatColor.YELLOW + "Clan " + clan.getName() + ChatColor.GRAY + " has too many members or allies");
                return;
            }
        }

        ClanMember member = new ClanMember(player.getUniqueId().toString(),
                targetGamer.getClient().isAdministrating() ? ClanMember.MemberRank.LEADER : ClanMember.MemberRank.RECRUIT);
        clan.getMembers().add(member);
        clanManager.getRepository().saveClanMember(clan, member);

        inviteHandler.removeInvite(clan, targetGamer, "Invite");
        inviteHandler.removeInvite(targetGamer, clan, "Invite");

        clan.messageClan(ChatColor.YELLOW + player.getName() + ChatColor.GRAY
                + " has joined your Clan.", player.getUniqueId(), true);
        UtilMessage.message(player, "Clans", "You joined " + ChatColor.YELLOW + "Clan " + clan.getName() + ChatColor.GRAY + ".");

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeaveClanEvent(MemberLeaveClanEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        Clan clan = event.getClan();

        Optional<ClanMember> memberOptional = clan.getMemberByUUID(player.getUniqueId());
        if (memberOptional.isPresent()) {
            ClanMember clanMember = memberOptional.get();

            clanManager.getRepository().deleteClanMember(clan, clanMember);
            clan.getMembers().remove(clanMember);

            UtilMessage.message(player, "Clans", "You left " + ChatColor.YELLOW + "Clan " + clan.getName() + ChatColor.GRAY + ".");
            clan.messageClan(ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " left your Clan.", player.getUniqueId(), true);
        }

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMemberKicked(ClanKickMemberEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        Clan clan = event.getClan();
        Client target = event.getTarget();

        Optional<ClanMember> memberOptional = clan.getMemberByUUID(target.getUuid());
        if (memberOptional.isPresent()) {
            ClanMember clanMember = memberOptional.get();

            clanManager.getRepository().deleteClanMember(clan, clanMember);
            clan.getMembers().remove(clanMember);

            UtilMessage.message(player, "Clans", "You kicked " + ChatColor.YELLOW + target.getName() + ChatColor.GRAY + ".");
            clan.messageClan(ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " was kicked from your Clan.", player.getUniqueId(), true);

            Player targetPlayer = Bukkit.getPlayer(target.getName());
            if (targetPlayer != null) {
                UtilMessage.message(targetPlayer, "Clans", "You were kicked from " + ChatColor.YELLOW + clan.getName());
            }
        }

    }

    @EventHandler
    public void onClanRequestAlliance(ClanRequestAllianceEvent event) {
        if (event.isCancelled()) return;

        Clan clan = event.getClan();
        Clan target = event.getTargetClan();

        if (inviteHandler.isInvited(clan, target, "Alliance") || inviteHandler.isInvited(target, clan, "Alliance")) {

            UtilServer.callEvent(new ClanAllianceEvent(event.getPlayer(), clan, target));
            return;
        }

        inviteHandler.createInvite(clan, target, "Alliance", 10);
        UtilMessage.simpleMessage(event.getPlayer(), "Clans", "You have requested an alliance with <yellow>%s<gray>.", target.getName());
        target.messageClan(ChatColor.YELLOW + "Clan " + clan.getName() + ChatColor.GRAY + " has requested an alliance.", null, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanAlly(ClanAllianceEvent event) {
        if (event.isCancelled()) return;
        Clan clan = event.getClan();
        Clan target = event.getTargetClan();

        inviteHandler.removeInvite(clan, target, "Alliance");
        inviteHandler.removeInvite(target, clan, "Alliance");

        ClanAlliance clanAlliance = new ClanAlliance(target, false);
        ClanAlliance targetAlliance = new ClanAlliance(clan, false);
        clan.getAlliances().add(clanAlliance);
        target.getAlliances().add(targetAlliance);

        clan.messageClan(ChatColor.YELLOW + "Clan " + target.getName() + ChatColor.GRAY + " is now allied to your Clan.", null, true);
        target.messageClan(ChatColor.YELLOW + "Clan " + clan.getName() + ChatColor.GRAY + " is now allied to your Clan.", null, true);

        clanManager.getRepository().saveClanAlliance(clan, clanAlliance);
        clanManager.getRepository().saveClanAlliance(target, targetAlliance);
    }
}
