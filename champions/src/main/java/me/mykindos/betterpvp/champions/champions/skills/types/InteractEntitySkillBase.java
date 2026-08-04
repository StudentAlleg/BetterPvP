package me.mykindos.betterpvp.champions.champions.skills.types;

import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.core.client.gamer.Gamer;
import me.mykindos.betterpvp.core.components.champions.events.PlayerUseSkillEvent;
import me.mykindos.betterpvp.core.utilities.UtilBlock;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.WeakHashMap;

/**
 * The activation plumbing shared by every {@link InteractEntitySkill}.
 *
 * <p>{@code Sever} and {@code HiltSmash} carried byte-identical copies of all of it: the same two
 * handlers, the same {@code rightClicked} flag threaded between them, the same holding/level/
 * {@code PlayerUseSkillEvent} preamble. Only the last step differed -- what to do with the target.
 * Two copies of a dispatch this fiddly is two places for it to drift, and the flag in particular has
 * a failure mode that is invisible until it bites: leave it set and the <em>next</em> genuine air
 * click reads as an entity click.
 *
 * <h2>Why both events</h2>
 * A real client right-clicking a player sends the entity interaction <em>and</em> the air/block
 * interaction. Only the first carries the target, so the second must be recognised as the tail of the
 * same click rather than treated as a separate miss -- that is the whole job of the flag. A click on
 * nothing produces only the second, arrives with the flag clear, and is correctly reported as a miss.
 *
 * <p>Subclasses implement {@link #onTargetInteract} and nothing else. It is called exactly once per
 * click, with the target or {@code null}, after the skill is known to be held at a level above zero
 * and after {@code PlayerUseSkillEvent} has been fired and not cancelled -- so a subclass never
 * repeats those checks and cannot forget one.
 */
public abstract class InteractEntitySkillBase extends Skill implements InteractEntitySkill {

    /**
     * Players whose current click began as an entity interaction.
     *
     * <p>Weak because the value is a single click's worth of state; a player who disconnects
     * mid-click has nothing worth retaining. {@link #invalidatePlayer} clears it explicitly all the
     * same, so a build change between the two halves of a click cannot strand a set flag.
     */
    private final WeakHashMap<Player, Boolean> rightClicked = new WeakHashMap<>();

    protected InteractEntitySkillBase(Champions champions, ChampionsManager championsManager) {
        super(champions, championsManager);
    }

    /**
     * Resolves one right-click.
     *
     * @param player the caster, already confirmed to be holding this skill at a level above zero
     * @param target what they clicked, or {@code null} if they clicked air or a block. Implementations
     *               are expected to report a miss rather than assume a target -- range and relation
     *               are still theirs to check, since only they know their own reach
     * @param level  the caster's level in this skill
     */
    protected abstract void onTargetInteract(Player player, LivingEntity target, int level);

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getRightClicked() instanceof LivingEntity entity) {
            rightClicked.put(event.getPlayer(), true);
            dispatch(event.getPlayer(), entity);
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND || !event.getAction().isRightClick()) return;
        if (UtilBlock.usable(event.getClickedBlock())) return;
        if (!rightClicked.getOrDefault(event.getPlayer(), false)) {
            // Not the tail of an entity click, so this is a genuine click on nothing.
            if (championsManager.getCooldowns().hasCooldown(event.getPlayer(), "DoorAccess")) return;
            dispatch(event.getPlayer(), null);
        }
        // Cleared whether or not it was set: this is the end of the click either way.
        rightClicked.remove(event.getPlayer());
    }

    /**
     * Clears the half-resolved click state when the skill leaves the build.
     *
     * <p>Subclasses that override this must call {@code super}, or a flag set by the entity half of a
     * click survives the build change and swallows the next air click.
     */
    @Override
    public void invalidatePlayer(Player player, Gamer gamer) {
        rightClicked.remove(player);
    }

    private void dispatch(Player player, LivingEntity target) {
        if (!isHolding(player)) return;

        final int level = getLevel(player);
        if (level <= 0) return;

        final PlayerUseSkillEvent event = UtilServer.callEvent(new PlayerUseSkillEvent(player, this, level));
        if (event.isCancelled()) return;

        onTargetInteract(player, target, level);
    }
}
