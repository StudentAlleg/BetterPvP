package me.mykindos.betterpvp.core.combat.death.events;

import com.google.common.base.Preconditions;
import lombok.Data;
import lombok.EqualsAndHashCode;
import me.mykindos.betterpvp.core.framework.events.CustomCancellableEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.function.Function;

@EqualsAndHashCode(callSuper = true)
@Data
public class CustomDeathMessageEvent extends CustomCancellableEvent {

    private Function<LivingEntity, Component> nameFormat = entity -> Component.text(entity.getName());

    // The person to receive the death message
    private final Player receiver;

    // The entity that was killed
    private final CustomDeathEvent deathEvent;

    public LivingEntity getKilled() {
        return deathEvent.getKilled();
    }

    public LivingEntity getKiller() {
        return deathEvent.getKiller();
    }

    public String[] getReason() {
        return deathEvent.getReason();
    }

    public Component getKillerName() {
        Preconditions.checkNotNull(getKiller(), "Killer is null");
        return nameFormat.apply(getKiller());
    }

    public Component getKilledName() {
        return nameFormat.apply(getKilled());
    }

}
