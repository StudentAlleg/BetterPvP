package me.mykindos.betterpvp.champions.champions.skills.skills.assassin.sword;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.skills.types.CooldownSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.DebuffSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.InteractEntitySkillBase;
import me.mykindos.betterpvp.champions.champions.skills.types.OffensiveSkill;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.effects.EffectTypes;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.locale.Translations;
import me.mykindos.betterpvp.core.utilities.UtilEntity;
import me.mykindos.betterpvp.core.utilities.UtilMath;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilPlayer;
import me.mykindos.betterpvp.core.utilities.events.EntityProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

@Singleton
@BPvPListener
public class Sever extends InteractEntitySkillBase implements CooldownSkill, Listener, OffensiveSkill,
        DebuffSkill {
    private double baseDuration;
    private double durationIncreasePerLevel;
    private double hitDistance;

    @Inject
    public Sever(Champions champions, ChampionsManager championsManager) {
        super(champions, championsManager);
    }

    @Override
    public String getName() {
        return "Sever";
    }

    @Override
    public Component[] getDescription(int level) {
        Component duration = getValueComponent(this::getDuration, level);
        Component cooldown = getValueComponent(this::getCooldown, level);
        Component bleed = Translations.component("champions.skill.effect.bleed.name").color(NamedTextColor.WHITE);
        Component[] components = Translations.componentLines(
                "champions.skill.assassin.sever.description",
                duration,
                cooldown,
                bleed
        );
        Component bleedDetail = Translations.component("champions.skill.effect.bleed.name").color(NamedTextColor.WHITE);
        Component[] detail = Translations.componentLines(
                "champions.skill.effect.bleed.detail",
                bleedDetail,
                Component.text("2.0", NamedTextColor.GREEN)
        );
        Component[] result = new Component[components.length + 1 + detail.length];
        System.arraycopy(components, 0, result, 0, components.length);
        result[components.length] = Component.empty();
        System.arraycopy(detail, 0, result, components.length + 1, detail.length);
        return result;
    }

    public double getDuration(int level) {
        return baseDuration + ((level - 1) * durationIncreasePerLevel);
    }

    @Override
    public Role getClassType() {
        return Role.ASSASSIN;
    }

    @Override
    public SkillType getType() {
        return SkillType.SWORD;
    }

    @Override
    protected void onTargetInteract(Player player, LivingEntity ent, int level) {
        if (ent == null) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SPIDER_HURT, 1.0F, 0.5F);
            UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.failed", getDisplayName().color(NamedTextColor.GREEN), Component.text(String.valueOf(level), NamedTextColor.GREEN));
            return;
        }

        boolean withinRange = UtilMath.offset(player, ent) <= hitDistance;
        if (UtilPlayer.isCreativeOrSpectator(ent) || UtilEntity.getRelation(player, ent) == EntityProperty.FRIENDLY || !withinRange) {
            UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.failed", getDisplayName().color(NamedTextColor.GREEN), Component.text(String.valueOf(level), NamedTextColor.GREEN));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SPIDER_HURT, 1.0F, 0.5F);
        } else {
            championsManager.getEffects().addEffect(ent, player, EffectTypes.BLEED, 1, (long) (getDuration(level) * 1000L));
            UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.assassin.sever.severed", Component.text(ent.getName(), NamedTextColor.GREEN));
            UtilMessage.message(ent, getClassType().getDisplayName(), "champions.skill.assassin.sever.severed-by", Component.text(player.getName(), NamedTextColor.GREEN));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SPIDER_HURT, 1.0F, 1.5F);
        }

    }

    @Override
    public double getCooldown(int level) {
        return cooldown - ((level - 1) * cooldownDecreasePerLevel);
    }

    @Override
    public void loadSkillConfig() {
        baseDuration = getConfig("baseDuration", 1.0, Double.class);
        durationIncreasePerLevel = getConfig("durationIncreasePerLevel", 1.0, Double.class);
        hitDistance = getConfig("hitDistance", 4.0, Double.class);
    }
}
