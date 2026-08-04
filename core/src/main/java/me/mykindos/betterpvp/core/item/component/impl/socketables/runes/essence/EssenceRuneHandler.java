package me.mykindos.betterpvp.core.item.component.impl.socketables.runes.essence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.combat.cause.DamageCauseCategory;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.energy.EnergyService;
import me.mykindos.betterpvp.core.energy.events.EnergyEvent;
import me.mykindos.betterpvp.core.item.component.impl.socketables.SocketableContainerComponent;
import me.mykindos.betterpvp.core.item.service.ComponentLookupService;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.utilities.model.SoundEffect;
import org.bukkit.Sound;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Optional;

@BPvPListener
@Singleton
public class EssenceRuneHandler implements Listener {

    private final EssenceRune essenceRune;
    private final ComponentLookupService componentLookupService;
    private final EnergyService energyService;

    @Inject
    public EssenceRuneHandler(EssenceRune essenceRune, ComponentLookupService lookupService, EnergyService energyService) {
        this.essenceRune = essenceRune;
        this.componentLookupService = lookupService;
        this.energyService = energyService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPostDamage(DamageEvent event) {
        if (!(event.getDamager() instanceof Player damager) || !(event.getDamagee() instanceof LivingEntity)) {
            return; // Only handle player damage events
        }

        final boolean melee = event.getCause().getCategories().contains(DamageCauseCategory.MELEE);
        final boolean ranged = event.getCause().getCategories().contains(DamageCauseCategory.RANGED);
        if (!melee && !ranged) {
            return; // Only handle melee attacks
        }

        // get the item used to deal damage
        // for ranged, we get the projectile "bow"
        // for melee, its equipment -- shared with the other damage handlers via the event's memo
        final Optional<SocketableContainerComponent> container;
        if (melee) {
            container = componentLookupService.getDamagerComponent(event, SocketableContainerComponent.class);
        } else if (ranged && event.getProjectile() instanceof Arrow arrow && arrow.getWeapon() != null) {
            container = componentLookupService.getComponent(arrow.getWeapon(), SocketableContainerComponent.class);
        } else {
            return;
        }

        if (container.isEmpty()) {
            return; // No runes present
        }

        // Check if the rune is present in the container
        if (!container.get().hasRune(essenceRune)) {
            return; // Rune not present
        }

        // Apply scorching effect
        energyService.regenerateEnergy(damager, essenceRune.getEnergy(), EnergyEvent.Cause.CUSTOM);
        new SoundEffect(Sound.BLOCK_AMETHYST_CLUSTER_FALL, 2f, 4f).play(event.getDamager());
    }
}
