package me.mykindos.betterpvp.core.effects.types.negative;

import me.mykindos.betterpvp.core.effects.Effect;
import me.mykindos.betterpvp.core.effects.VanillaEffectType;
import me.mykindos.betterpvp.core.localization.keys.CoreTranslationKeys;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffectType;

public class AntiHealEffect extends VanillaEffectType {

    @Override
    public String getName() {
        return "Anti Heal";
    }

    @Override
    public boolean isNegative() {
        return true;
    }

    @Override
    public PotionEffectType getVanillaPotionType() {
        return PotionEffectType.INFESTED;
    }

    @Override
    public void onExpire(LivingEntity livingEntity, Effect effect, boolean notify) {
        super.onExpire(livingEntity, effect, notify);
        UtilMessage.messageKey(livingEntity,
                UtilMessage.translateText(livingEntity, CoreTranslationKeys.PREFIX_EFFECT_ANTI_HEAL),
                CoreTranslationKeys.EFFECT_ANTI_HEAL_EXPIRED);
    }

    @Override
    public String getDescription(int level) {
        return UtilMessage.translateDefaultText(CoreTranslationKeys.EFFECT_ANTI_HEAL_DESCRIPTION);
    }

    @Override
    public void onTick(LivingEntity livingEntity, Effect effect) {
        super.onTick(livingEntity, effect);

        Location entityLoc = livingEntity.getLocation();
        livingEntity.getWorld().spawnParticle(Particle.GLOW, entityLoc, 1, 0.4, 0.4, 0.4, 0);
    }
}