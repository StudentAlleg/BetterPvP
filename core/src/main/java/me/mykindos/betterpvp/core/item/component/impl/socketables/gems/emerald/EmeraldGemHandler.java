package me.mykindos.betterpvp.core.item.component.impl.socketables.gems.emerald;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.combat.modifiers.DamageOperator;
import me.mykindos.betterpvp.core.combat.modifiers.ModifierType;
import me.mykindos.betterpvp.core.combat.modifiers.impl.GenericModifier;
import me.mykindos.betterpvp.core.item.component.impl.socketables.SocketableContainerComponent;
import me.mykindos.betterpvp.core.item.service.ComponentLookupService;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

@BPvPListener
@Singleton
public class EmeraldGemHandler implements Listener {

    private final EmeraldGem emeraldGem;
    private final ComponentLookupService componentLookupService;

    @Inject
    public EmeraldGemHandler(EmeraldGem emeraldGem, ComponentLookupService componentLookupService) {
        this.emeraldGem = emeraldGem;
        this.componentLookupService = componentLookupService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(DamageEvent event) {
        if (event.getDamager() instanceof Player) {
            componentLookupService.getDamagerComponent(event, SocketableContainerComponent.class).ifPresent(container -> {
                if (container.hasRune(emeraldGem)) {
                    event.addModifier(new GenericModifier(emeraldGem.getName(), ModifierType.RUNE, DamageOperator.MULTIPLIER, 1.0 + emeraldGem.getDamageIncrease()));
                }
            });
        }
    }
}
