package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Singleton;
import lombok.CustomLog;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Synthesises the inputs a player produces, so the rotation policy never touches a skill directly.
 *
 * <p>This is the hard rule of the whole project applied to activation: the simulator decides
 * <em>when</em> a button is pressed and nothing else. It does not call {@code InteractSkill.activate},
 * {@code ToggleSkill.toggle} or any other skill method, because doing so would skip
 * {@code SkillListener.onUseSkill} -- which is where the cooldown is consumed, the energy is spent,
 * and the silence, stun, slow, liquid and levitation gates are applied. A rotation that bypassed
 * that would report every skill firing at will, which is a model again.
 *
 * <p>So each method here produces the same event a real client's packet produces, at the same point
 * in the chain, and lets the real listeners decide whether anything happens.
 */
@Singleton
@CustomLog
public class SimInputs {

    /**
     * Presses right click once.
     *
     * <p>Reaches {@code SkillListener.onSkillActivate} ({@code HIGHEST}), which finds the build's
     * {@code InteractSkill} for the held weapon's slot and fires {@code PlayerUseInteractSkillEvent} --
     * and {@code RightClickListener.onRightClick} ({@code HIGHEST}), which is what starts the
     * hold-tracking a channel skill later depends on.
     *
     * <p>{@code RIGHT_CLICK_AIR} with a null block, because the arena is a bare platform and the
     * combatants face each other rather than the floor. That also keeps every "did they click a usable
     * block" guard in the chain on its normal path. {@code BlockFace.SELF} is what the server itself
     * uses for an air interaction.
     */
    public void rightClick(Player player) {
        final ItemStack held = player.getInventory().getItemInMainHand();
        player.getServer().getPluginManager().callEvent(new PlayerInteractEvent(player,
                Action.RIGHT_CLICK_AIR, held, null, BlockFace.SELF, EquipmentSlot.HAND));
    }

    /**
     * Presses right click while looking at {@code target}.
     *
     * <p>The input an {@code InteractEntitySkill} answers to. {@link #rightClick} cannot stand in for
     * it: those skills read their target off {@code PlayerInteractEntityEvent}, so an air click
     * reaches them with no entity and takes their failure branch. Firing both is what a real client
     * does -- the vanilla protocol sends the entity interaction, and the skills' own handlers set a
     * flag so their {@code PlayerInteractEvent} handler knows not to treat it as a miss.
     *
     * <p>Order matters and mirrors the real chain: the entity event first, because the skills use it
     * to set exactly that flag, and the air click second so the flag is consumed and reset. Sending
     * only the entity event would leave the flag set and make the <em>next</em> genuine air click read
     * as an entity click.
     */
    public void rightClickEntity(Player player, Entity target) {
        player.getServer().getPluginManager().callEvent(
                new PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND));
        rightClick(player);
    }

    /**
     * Starts holding right click, which is what a channel or charge skill is ticked by.
     *
     * <p>The distinction between a tap and a hold is not in the interact event -- it is in whether the
     * server thinks the player is still using the item. Champions' weapons all carry a
     * {@code CONSUMABLE} data component with {@code consumeSeconds(Float.MAX_VALUE)} precisely so that
     * holding right click on a sword raises the hand indefinitely, and {@code isHandRaised()} is the
     * condition the channel skills' own update loops test ({@code Inferno.doInferno} drops a holder the
     * first tick it is false). {@code startUsingItem} is the same server-side state a held packet
     * produces, and is what {@code RightClickListener} itself calls when it fits a cosmetic shield.
     *
     * <p>Skipped when something is already being used, so this cannot fight
     * {@code RightClickListener.onFinalShieldCheck}, which puts a shield in the off hand and starts
     * using <em>that</em> for the skills that show one.
     */
    public void beginHold(Player player) {
        if (player.hasActiveItem()) {
            return;
        }
        player.startUsingItem(EquipmentSlot.HAND);
    }

    /**
     * Releases right click.
     *
     * <p>{@code clearActiveItem} rather than {@code completeUsingActiveItem}: completing a use would
     * run the item's consume behaviour, and these are weapons whose consumable component exists only to
     * hold the hand up. Releasing is enough -- the channel skills end themselves the tick
     * {@code isHandRaised()} goes false, and {@code RightClickListener} evicts its hold context and
     * fires {@code RightClickEndEvent} on its own next sweep.
     */
    public void endHold(Player player) {
        if (player.hasActiveItem()) {
            player.clearActiveItem();
        }
    }

    /**
     * Presses the drop key, which is the only input a {@code ToggleSkill} listens to.
     *
     * <p>{@code SkillListener.onDrop} handles {@code PlayerDropItemEvent}, reads the dropped stack to
     * check it is a weapon, and fires {@code PlayerUseToggleSkillEvent} for every {@code ToggleSkill}
     * in the active build -- then cancels the drop, which is how a real player keeps their sword.
     *
     * <p>A throwaway item entity carrying a <em>copy</em> of the held weapon is used rather than
     * {@code HumanEntity.dropItem}, which would drop the real stack. The difference only matters when
     * something goes wrong, and that is the point: with the real drop, a build whose toggle skill did
     * not cancel the event for any reason would silently continue the duel unarmed, and the row would
     * read as a weak build rather than as a broken input. Here the inventory is never touched at all,
     * and the event the listener sees is identical.
     *
     * <p>The entity is removed unconditionally, cancelled or not, because nothing ever wanted a dropped
     * item -- it exists for the length of one event dispatch.
     */
    public void dropKey(Player player) {
        final ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            return;
        }
        final Item entity = player.getWorld().dropItem(player.getEyeLocation(), held.clone());
        entity.setVelocity(new Vector());
        entity.setPickupDelay(Integer.MAX_VALUE);
        try {
            player.getServer().getPluginManager().callEvent(new PlayerDropItemEvent(player, entity));
        } finally {
            entity.remove();
        }
    }
}
