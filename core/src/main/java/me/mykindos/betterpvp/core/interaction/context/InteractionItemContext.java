package me.mykindos.betterpvp.core.interaction.context;

import me.mykindos.betterpvp.core.interaction.component.InteractionContainerComponent;
import me.mykindos.betterpvp.core.item.ItemFactory;
import me.mykindos.betterpvp.core.item.ItemInstance;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Helper record that consolidates the repeated pattern of:
 * ItemStack -> AIR check -> ItemInstance -> InteractionContainerComponent
 * <p>
 * This eliminates 8+ occurrences of boilerplate extraction code.
 */
public record InteractionItemContext(
        @NotNull ItemStack itemStack,
        @NotNull ItemInstance itemInstance,
        @NotNull InteractionContainerComponent container
) {

    /**
     * Attempt to extract an InteractionItemContext from a living entity's main hand.
     *
     * @param entity      the living entity
     * @param itemFactory the item factory for converting ItemStack to ItemInstance
     * @return an Optional containing the context if all conditions are met
     */
    public static Optional<InteractionItemContext> fromMainHand(@NotNull LivingEntity entity, @NotNull ItemFactory itemFactory) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return Optional.empty();
        }
        return from(equipment.getItemInMainHand(), itemFactory);
    }

    /**
     * Attempt to extract an InteractionItemContext from an ItemStack.
     *
     * @param itemStack   the item stack to check
     * @param itemFactory the item factory for converting ItemStack to ItemInstance
     * @return an Optional containing the context if all conditions are met
     */
    public static Optional<InteractionItemContext> from(@NotNull ItemStack itemStack, @NotNull ItemFactory itemFactory) {
        if (itemStack.getType() == Material.AIR || itemStack.getType().isAir()) {
            return Optional.empty();
        }

        return itemFactory.fromItemStack(itemStack).flatMap(InteractionItemContext::of);
    }

    /**
     * Build a context from an already-resolved instance, for callers that have one to hand.
     *
     * <p>Exists so damage handlers can reuse
     * {@link me.mykindos.betterpvp.core.combat.events.DamageEvent#damagerMainHand} rather than
     * deserializing the attacker's weapon a second time -- the resolution the other overloads do is
     * the expensive part, and on the damage path it has already happened.
     *
     * @param itemInstance the resolved instance
     * @return an Optional containing the context if the instance carries an interaction container
     */
    public static Optional<InteractionItemContext> of(@NotNull ItemInstance itemInstance) {
        return itemInstance.getComponent(InteractionContainerComponent.class)
                .map(container -> new InteractionItemContext(itemInstance.getItemStack(), itemInstance, container));
    }
}
