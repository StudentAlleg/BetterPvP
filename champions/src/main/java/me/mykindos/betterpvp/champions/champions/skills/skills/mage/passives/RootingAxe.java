package me.mykindos.betterpvp.champions.champions.skills.skills.mage.passives;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.data.SkillWeapons;
import me.mykindos.betterpvp.champions.champions.skills.types.CooldownSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.DebuffSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.OffensiveSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PassiveSkill;
import me.mykindos.betterpvp.core.combat.cause.DamageCauseCategory;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.effects.EffectTypes;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.locale.Translations;
import me.mykindos.betterpvp.core.utilities.UtilBlock;
import me.mykindos.betterpvp.core.world.blocks.WorldBlockHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.Effect;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.util.BoundingBox;

@Singleton
@BPvPListener
public class RootingAxe extends Skill implements PassiveSkill, CooldownSkill, DebuffSkill, OffensiveSkill {

    private double baseDuration;

    private double durationIncreasePerLevel;

    private final WorldBlockHandler blockHandler;

    @Inject
    public RootingAxe(Champions champions, ChampionsManager championsManager, WorldBlockHandler blockHandler) {
        super(champions, championsManager);
        this.blockHandler = blockHandler;
    }

    @Override
    public String getName() {
        return "Rooting Axe";
    }

    @Override
    public Component[] getDescription(int level) {
        Component duration = getValueComponent(this::getDuration, level);
        Component cooldown = getValueComponent(this::getCooldown, level);
        return Translations.componentLines("champions.skill.mage.rooting-axe.description", duration, cooldown);
    }

    private double getDuration(int level) {
        return baseDuration + ((level-1) * durationIncreasePerLevel);
    }

    @Override
    public Role getClassType() {
        return Role.MAGE;
    }

    @EventHandler
    public void onDamage(DamageEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;
        if (!event.getCause().getCategories().contains(DamageCauseCategory.MELEE)) return;
        if (!SkillWeapons.isHolding(damager, SkillType.AXE)) return;
        if (event.getDamagee() instanceof Wither) return;
        if (!event.isDamageeLiving()) return;
        // Before isGrounded, which walks the damagee's supporting blocks: every melee axe hit on
        // the server reaches this handler, but only a mage carrying the passive can act on it. A
        // 299 s profile of the balance simulator put 3.2% of the entire main thread in isGrounded
        // under this method, essentially all of it on damagers with no level in the skill.
        int level = getLevel(damager);
        if (level <= 0) return;
        if (!UtilBlock.isGrounded(event.getDamagee())) return;
        final LivingEntity damagee = event.getLivingDamagee();
        if (championsManager.getEffects().hasEffect(damagee, EffectTypes.PROTECTION)) return;

        if (damagee instanceof Player &&
                championsManager.getEffects().hasEffect(damager, EffectTypes.PROTECTION)) {
            return;
        }

        Block block = event.getDamagee().getLocation().getBlock().getRelative(0, -1, 0);

        BlockData blockData = block.getBlockData();
        if (blockData instanceof Slab || blockData instanceof Openable || blockData instanceof Ladder) {
            return;
        }

        Block blockMoreUnder = damagee.getLocation().getBlock().getRelative(0, -2, 0);
        if(!isRootable(blockMoreUnder)) {
            return;
        }

        Block blockUnder = damagee.getEyeLocation().getBlock().getRelative(0, -1, 0);
        if (UtilBlock.airFoliage(blockUnder) && !UtilBlock.airFoliage(blockMoreUnder)) {
            if (!UtilBlock.airFoliage(block) && !block.isLiquid() && !blockMoreUnder.isLiquid()) {

                if (championsManager.getCooldowns().use(damager, getName(), getCooldown(level), false)) {
                    damagee.teleport(damagee.getLocation().add(0, -1, 0));
                    damagee.getWorld().playEffect(damagee.getLocation(), Effect.STEP_SOUND, damagee.getLocation().getBlock().getType());
                    championsManager.getEffects().addEffect(damagee, damager, EffectTypes.NO_JUMP, (long) (getDuration(level) * 1000));
                }
            }
        }
    }

    private boolean isRootable(Block block) {
        BoundingBox boundingBox = block.getBoundingBox();
        return UtilBlock.solid(block) && boundingBox.getHeight() == 1.0 && boundingBox.getWidthX() == 1.0 && boundingBox.getWidthZ() == 1.0
                && !blockHandler.isRestoreBlock(block);
    }

    @Override
    public SkillType getType() {
        return SkillType.PASSIVE_B;
    }


    @Override
    public double getCooldown(int level) {
        return cooldown - ((level - 1) * cooldownDecreasePerLevel);
    }

    public void loadSkillConfig() {
        baseDuration = getConfig("baseDuration", 2.0, Double.class);
        durationIncreasePerLevel = getConfig("durationIncreasePerLevel", 0.0, Double.class);
    }
}
