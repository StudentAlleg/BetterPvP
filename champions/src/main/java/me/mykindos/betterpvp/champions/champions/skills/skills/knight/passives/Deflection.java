package me.mykindos.betterpvp.champions.champions.skills.skills.knight.passives;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.types.DefensiveSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PassiveSkill;
import me.mykindos.betterpvp.champions.combat.damage.SkillDamageModifier;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.gamer.Gamer;
import me.mykindos.betterpvp.core.combat.cause.DamageCauseCategory;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.locale.Translations;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilTime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.util.HashMap;
import java.util.UUID;

@Singleton
@BPvPListener
public class Deflection extends Skill implements PassiveSkill, DefensiveSkill {


    private double timeBetweenCharges;
    private double timeBetweenChargesDecreasePerLevel;
    private double timeOutOfCombat;
    private double timeOutOfCombatDecreasePerLevel;
    private int baseCharges;
    private int chargesIncreasePerLevel;
    private double baseDamageReduction;
    private double damageReductionIncreasePerLevel;

    private final HashMap<UUID, Integer> charges = new HashMap<>();

    @Inject
    public Deflection(Champions champions, ChampionsManager championsManager) {
        super(champions, championsManager);
    }

    @Override
    public String getName() {
        return "Deflection";
    }

    @Override
    public Component[] getDescription(int level) {
        Component timeBetween = getValueComponent(this::getTimeBetweenCharges, level);
        Component maxCharges = Component.text(
                String.valueOf(getMaxCharges(level)),
                NamedTextColor.YELLOW
        );
        Component timeOutOfCombat = getValueComponent(this::getTimeOutOfCombat, level);
        Component reduction = getValueComponent(this::getDamageReductionPerCharge, level);
        return Translations.componentLines(
                "champions.skill.knight.deflection.description",
                timeBetween,
                maxCharges,
                timeOutOfCombat,
                reduction
        );
    }

    public int getMaxCharges(int level) {
        return baseCharges + ((level - 1) * chargesIncreasePerLevel);
    }

    public double getTimeBetweenCharges(int level) {
        return timeBetweenCharges - ((level - 1) * timeBetweenChargesDecreasePerLevel);
    }

    public double getDamageReductionPerCharge(int level) {
        return baseDamageReduction + ((level - 1) * damageReductionIncreasePerLevel);
    }

    public double getTimeOutOfCombat(int level) {
        return timeOutOfCombat - ((level - 1) * timeOutOfCombatDecreasePerLevel);
    }

    @Override
    public Role getClassType() {
        return Role.KNIGHT;
    }

    @Override
    public SkillType getType() {
        return SkillType.PASSIVE_A;
    }


    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(DamageEvent event) {
        if (event.isCancelled()) return;
        if (!event.getCause().getCategories().contains(DamageCauseCategory.MELEE)) return;
        if (!(event.getDamagee() instanceof Player player)) return;
        if (!charges.containsKey(player.getUniqueId())) return;

        int level = getLevel(player);
        if (level > 0) {
            int charge = charges.remove(player.getUniqueId());
            event.addModifier(new SkillDamageModifier.Flat(this, -charge));;

        }
    }

    @UpdateEvent(delay = 250)
    public void addCharge() {

        // Iterates the loaded clients rather than Bukkit.getOnlinePlayers(), matching Swordsmanship,
        // which is the sibling charge passive and was already written this way. The two describe the
        // same mechanic and had no reason to disagree on who they apply to; the player list is also
        // the narrower of the two, so anything holding this skill while absent from it -- a
        // simulation combatant, for one -- never accrued a charge and so had the skill silently
        // contribute nothing.
        for (Client client : championsManager.getClientManager().getLoaded()) {
            Player cur = client.getGamer().getPlayer();
            if (cur == null) continue;

            int level = getLevel(cur);
            if (level > 0) {
                if (charges.containsKey(cur.getUniqueId())) {
                    Gamer gamer = championsManager.getClientManager().search().online(cur).getGamer();
                    if (gamer.hasBeenOutOfCombatFor(UtilTime.toTicks(getTimeOutOfCombat(level)))) {
                        // continue, not return: this is a per-player cooldown, so bailing out of the
                        // whole loop let whichever player happened to be iterated first starve every
                        // player behind them of charges for as long as their cooldown had left.
                        if (!championsManager.getCooldowns().use(cur, getName(), getTimeBetweenCharges(level), false)) continue;
                        int charge = charges.get(cur.getUniqueId());
                        if (charge < getMaxCharges(level)) {
                            charge = Math.min(getMaxCharges(level), charge + 1);
                            UtilMessage.message(cur, getClassType().getDisplayName(), "champions.skill.knight.deflection.charge", Component.text(String.valueOf(charge), NamedTextColor.YELLOW));
                            charges.put(cur.getUniqueId(), charge);
                        }
                    }
                } else {
                    charges.put(cur.getUniqueId(), 0);
                }
            } else {
                charges.remove(cur.getUniqueId());
            }
        }

    }

    /**
     * Drops the charge count when the skill leaves the build.
     *
     * <p>{@link #addCharge()} already removes the entry for anyone whose level has fallen to zero,
     * but only for players it iterates -- the loaded clients. This closes the same state for a player
     * who leaves that set in the same breath as the skill, and re-seeding at zero on re-equip is
     * handled by the same loop.
     */
    @Override
    public void invalidatePlayer(Player player, Gamer gamer) {
        charges.remove(player.getUniqueId());
    }

    @Override
    public boolean enabledInSpectator() {
        return true;
    }

    @Override
    public void loadSkillConfig() {
        timeBetweenCharges = getConfig("timeBetweenCharges", 2.0, Double.class);
        timeBetweenChargesDecreasePerLevel = getConfig("timeBetweenChargesDecreasePerLevel", 0.0, Double.class);
        timeOutOfCombat = getConfig("timeOutOfCombat", 2.0, Double.class);
        timeOutOfCombatDecreasePerLevel = getConfig("timeOutOfCombatDecreasePerLevel", 0.0, Double.class);
        baseCharges = getConfig("baseCharges", 1, Integer.class);
        chargesIncreasePerLevel = getConfig("chargesIncreasePerLevel", 1, Integer.class);
        baseDamageReduction = getConfig("baseDamageReduction", 1.0, Double.class);
        damageReductionIncreasePerLevel = getConfig("damageReductionIncreasePerLevel", 0.0, Double.class);
    }

}
