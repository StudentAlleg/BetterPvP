package me.mykindos.betterpvp.core.energy.events;

import lombok.Getter;
import lombok.Setter;
import me.mykindos.betterpvp.core.framework.events.CustomCancellableEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

@Getter
@Setter
public class UpdateMaxEnergyEvent extends CustomCancellableEvent {
    private final Player player;
    private double newMax;

    /**
     * Declares its own asynchrony from the thread it is constructed on, rather than asserting one.
     *
     * <p>Bukkit rejects an event whose declared threading disagrees with the thread it is fired on, in
     * both directions, and this event has two callers that legitimately differ:
     * {@code EnergyService.updateMax} raises it from an async task, while
     * {@code EnergyService.addToMap} raises it inline to work out a player's max the first time
     * anything asks for their energy -- and that seeding happens on the main thread, from every
     * accessor on the service. Hardcoding {@code true} made the second caller throw
     * {@code IllegalStateException} out of whatever pipeline it was in, which for a player without an
     * entry yet meant the first energy read after joining could abort a damage event.
     *
     * <p>Both listeners ({@code SapphireGemHandler}, {@code EnergyPool}) only read equipment and skill
     * level, so neither needs a particular thread -- and reading them on the main thread is the safer
     * of the two.
     */
    public UpdateMaxEnergyEvent(Player player, double newMax) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.newMax = newMax;
    }
}
