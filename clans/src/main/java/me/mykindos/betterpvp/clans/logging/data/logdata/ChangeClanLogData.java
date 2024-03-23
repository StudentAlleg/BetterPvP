package me.mykindos.betterpvp.clans.logging.data.logdata;

import lombok.CustomLog;
import lombok.Getter;
import me.mykindos.betterpvp.clans.clans.Clan;
import me.mykindos.betterpvp.clans.logging.data.ClanData;
import me.mykindos.betterpvp.clans.logging.data.ClanMemberData;
import me.mykindos.betterpvp.core.client.Client;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

@CustomLog
public class ChangeClanLogData {
    public final ClanData clanData;
    public final ClanMemberData clanMemberData;
    public final long time;
    public final ChangeClanLogType type;

    public final ClanMemberData kicker;

    ChangeClanLogData(ClanData clanData, ClanMemberData clanMemberData, long time, ChangeClanLogType type, @Nullable ClanMemberData kicker) {
        this.clanData = clanData;
        this.clanMemberData = clanMemberData;
        this.time = time;
        this.type = type;
        this.kicker = kicker;
    }

    /**
     * Represents a log in where the member changes their Clan
     */
    ChangeClanLogData(ClanData clanData, ClanMemberData clanMemberData, long time, ChangeClanLogType type) {
       this(clanData, clanMemberData, time, type, null);
    }

    @Getter
    public enum ChangeClanLogType {
        JOIN(" joined "),
        LEAVE(" left "),
        KICK(" kicked ");

        public final String uniqueText;

        ChangeClanLogType(String uniqueText) {
            this.uniqueText = uniqueText;
        }
    }

    public static void generateLog(Player player, @Nullable Client kicker, Clan clan, ChangeClanLogType type) {
        switch (type) {
            case KICK -> {
                log.
            },
        }
    }
}


