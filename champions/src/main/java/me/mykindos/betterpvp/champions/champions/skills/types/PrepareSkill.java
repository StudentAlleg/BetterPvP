package me.mykindos.betterpvp.champions.champions.skills.types;

import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.builds.menus.events.SkillDequipEvent;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.core.client.gamer.Gamer;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public abstract class PrepareSkill extends Skill implements InteractSkill, Listener {

    protected final Set<UUID> active = new HashSet<>();

    public PrepareSkill(Champions champions, ChampionsManager championsManager) {
        super(champions, championsManager);
    }

    @EventHandler
    public void onDequip(SkillDequipEvent event) {
        if (event.getBuildSkill().getSkill() == this) {
            active.remove(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Drops the armed state when the skill stops being equipped for any reason.
     *
     * <p>{@link #onDequip} only covers an edit in the build menu. A role change or a logout goes
     * through {@code invalidatePlayer} instead, and left the player armed: {@link #canUse} refuses
     * while the entry is present, so re-equipping the skill later found it permanently unusable until
     * a prepared hit happened to land. Same reason the stateful passives override this.
     */
    @Override
    public void invalidatePlayer(Player player, Gamer gamer) {
        active.remove(player.getUniqueId());
    }

    @Override
    public boolean canUse(Player player) {
        if (active.contains(player.getUniqueId())) {
            UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.already-prepared", getDisplayName().color(NamedTextColor.GREEN));
            return false;
        }

        return true;
    }
}
