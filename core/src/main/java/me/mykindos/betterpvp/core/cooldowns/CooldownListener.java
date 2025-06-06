package me.mykindos.betterpvp.core.cooldowns;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.combat.death.events.CustomDeathMessageEvent;
import me.mykindos.betterpvp.core.cooldowns.events.CooldownEvent;
import me.mykindos.betterpvp.core.effects.EffectManager;
import me.mykindos.betterpvp.core.effects.EffectTypes;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

@Singleton
@BPvPListener
public class CooldownListener implements Listener {

    private final CooldownManager cooldownManager;
    private final EffectManager effectManager;

    @Inject
    public CooldownListener(CooldownManager cooldownManager, EffectManager effectManager) {
        this.cooldownManager = cooldownManager;
        this.effectManager = effectManager;
    }

    @UpdateEvent(delay = 100, isAsync = true, priority = 999)
    public void processCooldowns() {
        cooldownManager.processCooldowns();
    }

    @EventHandler
    public void onDeath(CustomDeathMessageEvent event) {
        if (!(event.getKilled() instanceof Player player)) return;
        cooldownManager.getObject(player.getUniqueId()).ifPresent(cooldowns -> {
            cooldowns.entrySet().removeIf(cooldown -> cooldown.getValue().isRemoveOnDeath());
        });
    }

    @EventHandler
    public void onCooldown(CooldownEvent event) {
        if(event.isCancelled()) return;

        effectManager.getEffect(event.getPlayer(), EffectTypes.COOLDOWN_REDUCTION).ifPresent(effect -> {
            event.getCooldown().setSeconds(event.getCooldown().getSeconds() * (1 - (effect.getAmplifier() / 100d)));
        });
    }

}
