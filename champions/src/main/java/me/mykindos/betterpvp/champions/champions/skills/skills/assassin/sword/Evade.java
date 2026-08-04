package me.mykindos.betterpvp.champions.champions.skills.skills.assassin.sword;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.skills.data.SkillActions;
import me.mykindos.betterpvp.champions.champions.skills.types.ChannelSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.CooldownSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.DefensiveSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.InteractSkill;
import me.mykindos.betterpvp.core.client.gamer.Gamer;
import me.mykindos.betterpvp.core.combat.cause.DamageCauseCategory;
import me.mykindos.betterpvp.core.combat.delay.DamageDelayManager;
import me.mykindos.betterpvp.core.combat.events.CustomEntityVelocityEvent;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.cooldowns.CooldownManager;
import me.mykindos.betterpvp.core.effects.EffectTypes;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.locale.Translations;
import me.mykindos.betterpvp.core.utilities.UtilBlock;
import me.mykindos.betterpvp.core.utilities.UtilLocation;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilPlayer;
import me.mykindos.betterpvp.core.utilities.UtilTime;
import me.mykindos.betterpvp.core.utilities.model.SoundEffect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;

@Singleton
@BPvPListener
public class Evade extends ChannelSkill implements InteractSkill, CooldownSkill, DefensiveSkill {

    /**
     * The server tick each channelling player raised their hand on.
     *
     * <p>Ticks rather than a wall-clock stamp. Two things here are read back as durations -- the
     * channel window in {@link #onUpdate()} and the channel time that lengthens the success
     * cooldown in {@link #onEvade(DamageEvent)} -- and both only advance while a tick is being
     * processed. Measured in milliseconds they varied with how fast the server happened to be
     * running, which made the length of an evade, and every exchange sequenced after it, different
     * from one identical fight to the next.
     *
     * <p>Cleared in step with {@code active}. An entry that outlives its channel is not inert: the
     * cooldown term is derived from the difference between now and this tick, so a stale entry
     * yields a channel time measured from some earlier fight entirely.
     */
    private final HashMap<UUID, Integer> handRaisedTick = new HashMap<>();

    private final DamageDelayManager damageDelayManager;

    private double activeBaseDuration;
    private double activeDurationIncreasePerLevel;
    private double baseDamageDelay;
    private double damageDelayIncreasePerLevel;
    private double successBaseCooldown;
    private double successCooldownDecreasePerLevel;
    private double channelTimeCooldownMultiplier;
    private double channelTimeCooldownMultiplierDecreasePerLevel;

    @Inject
    private CooldownManager cooldownManager;

    @Inject
    public Evade(Champions champions, ChampionsManager championsManager, DamageDelayManager damageDelayManager) {
        super(champions, championsManager);
        this.damageDelayManager = damageDelayManager;
    }

    @Override
    public String getName() {
        return "Evade";
    }

    @Override
    public Component[] getDescription(int level) {
        Component duration = getValueComponent(this::getActiveDuration, level);
        Component cooldown = getValueComponent(this::getSuccessCooldown, level);
        Component baseCooldown = getValueComponent(this::getCooldown, level);
        return Translations.componentLines(
                "champions.skill.assassin.evade.description",
                duration,
                cooldown,
                baseCooldown
        );
    }

    @Override
    public Role getClassType() {
        return Role.ASSASSIN;
    }

    @Override
    public SkillType getType() {
        return SkillType.SWORD;
    }

    public double getActiveDuration(int level) {
        return activeBaseDuration + (level - 1) * activeDurationIncreasePerLevel;
    }

    public double getDamageDelay(int level) {
        return baseDamageDelay + (level - 1) * damageDelayIncreasePerLevel;
    }

    public double getSuccessCooldown(int level) {
        return successBaseCooldown - (level - 1) * successCooldownDecreasePerLevel;
    }

    public double getChannelTimeCooldownMultiplier(int level) {
        return channelTimeCooldownMultiplier - (level - 1) * channelTimeCooldownMultiplierDecreasePerLevel;
    }


    @EventHandler (priority = EventPriority.LOW)
    public void onEvade(DamageEvent event) {
        if (!event.getCause().getCategories().contains(DamageCauseCategory.MELEE)) return;
        if (!(event.getDamagee() instanceof Player player)) return;
        if (!active.contains(player.getUniqueId())) return;
        if (event.getDamager() == null) return;
        int level = getLevel(player);
        if (level <= 0) return;
        // Read before anything is cancelled or moved. A player in `active` with no start tick is
        // the inconsistent state onUpdate already resolves by dropping them from the channel, and
        // the evade cannot be priced without it -- the success cooldown is derived from how long
        // the channel was held. Bailing here lets the hit land, which is what not channelling
        // means; the previous unguarded read threw instead, mid-way through cancelling the damage.
        final Integer raisedTick = handRaisedTick.get(player.getUniqueId());
        if (raisedTick == null) {
            active.remove(player.getUniqueId());
            return;
        }
        LivingEntity ent = event.getDamager();

        event.setKnockback(false);
        event.cancel("Skill Evade");
        player.setVelocity(new Vector(0, 0, 0));
        damageDelayManager.addDelay(event.getDamager(), event.getDamagee(), event.getCause(), (long) (getDamageDelay(level) * 1000L));

        Particle.LARGE_SMOKE.builder()
                .offset(0.3, 0.3, 0.3)
                .count(3)
                .location(player.getLocation().add(0, player.getHeight() / 2, 0))
                .receivers(60)
                .extra(0)
                .spawn();

        final Vector direction = ent.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();
        double distance = ent.getLocation().distance(player.getLocation()) + 1.5;
        final boolean isReverse = player.isSneaking();
        if (isReverse) {
            distance = 1.5;
            direction.multiply(new Vector(-1, 1, -1)); // flip horizontal
        }

        UtilLocation.teleportToward(player, direction, distance, false, success -> {
            if (!Boolean.TRUE.equals(success)) {
                return;
            }
            player.setVelocity(new Vector(0, 0, 0));
            cooldownManager.removeCooldown(player, getName(), true);

            long channelTicks = Bukkit.getCurrentTick() - (long) raisedTick;
            double channelTimeInSeconds = (double) channelTicks / UtilTime.TICKS_PER_SECOND;
            double newCooldown = getSuccessCooldown(level) + channelTimeInSeconds * getChannelTimeCooldownMultiplier(level);

            if (!isReverse) {
                if (!UtilLocation.isInFront(ent, player.getLocation())) {
                    player.setRotation(ent.getLocation().getYaw(), ent.getLocation().getPitch());
                }
            }

            cooldownManager.use(player, getName(), newCooldown, true);
            handRaisedTick.remove(player.getUniqueId());

            UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.used", getDisplayName().color(NamedTextColor.GREEN), Component.text(String.valueOf(level), NamedTextColor.GREEN));

            if (ent instanceof Player temp) {
                UtilMessage.message(temp, getClassType().getDisplayName(), "champions.skill.assassin.evade.target-used", Component.text(player.getName(), NamedTextColor.YELLOW), getDisplayName().color(NamedTextColor.GREEN), Component.text(String.valueOf(level), NamedTextColor.GREEN));
            }

            active.remove(player.getUniqueId());
        });
    }

    @EventHandler
    public void onCustomVelocity(CustomEntityVelocityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!active.contains(player.getUniqueId())) return;

        if (hasSkill(player)) {
            event.setCancelled(true);
        }
    }

    @UpdateEvent
    public void onUpdate() {
        Iterator<UUID> it = active.iterator();
        while (it.hasNext()) {
            // Held rather than read through the player, so that every exit below can clear the
            // start tick too. Leaving it behind used to be harmless-looking bookkeeping, but
            // combatants are recycled onto the same UUID, so a stale entry becomes the origin the
            // next channel's duration is measured from.
            final UUID uuid = it.next();
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                int level = getLevel(player);
                if (level > 0) {
                    if (!player.isHandRaised()) {
                        handRaisedTick.remove(uuid);
                        it.remove();
                        UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.failed", getDisplayName().color(NamedTextColor.GREEN), Component.text(String.valueOf(level), NamedTextColor.GREEN));
                        player.getWorld().playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 2.0f, 1.0f);
                    } else if (!handRaisedTick.containsKey(uuid)) {
                        it.remove();
                    } else if (!isHolding(player)) {
                        handRaisedTick.remove(uuid);
                        it.remove();
                    } else if (UtilBlock.isInLiquid(player)) {
                        handRaisedTick.remove(uuid);
                        it.remove();
                    } else if (championsManager.getEffects().hasEffect(player, EffectTypes.SILENCE)) {
                        handRaisedTick.remove(uuid);
                        it.remove();
                    } else if (championsManager.getEffects().hasEffect(player, EffectTypes.STUN)) {
                        handRaisedTick.remove(uuid);
                        it.remove();
                    } else if (UtilTime.ticksElapsed(handRaisedTick.get(uuid), UtilTime.toTicks(getActiveDuration(level)))) {
                        handRaisedTick.remove(uuid);
                        UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.failed", getDisplayName().color(NamedTextColor.GREEN), Component.text(String.valueOf(getLevel(player)), NamedTextColor.GREEN));
                        player.getWorld().playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 2.0f, 1.0f);
                        it.remove();
                    }
                    spawnParticles(player);
                } else {
                    handRaisedTick.remove(uuid);
                    it.remove();
                }

            } else {
                handRaisedTick.remove(uuid);
                it.remove();
            }
        }
    }

    public void spawnParticles(Player player) {
        final Location location = UtilPlayer.getMidpoint(player);
        new SoundEffect(Sound.ENTITY_PLAYER_SMALL_FALL, 1.0f, 0.2f).play(location);
        Particle.BLOCK.builder()
                .count(10)
                .location(location)
                .offset(0.3, 0.3, 0.3)
                .data(Material.BEDROCK.createBlockData())
                .receivers(60)
                .spawn();
    }

    @EventHandler
    public void onDamage(DamageEvent e) {
        if (e.getDamager() instanceof Player player) {
            if (active.contains(player.getUniqueId())) {
                e.cancel("Skill: Evade");
            }
        }
    }

    @Override
    public double getCooldown(int level) {
        return cooldown - (level - 1);
    }

    @Override
    public boolean activate(Player player, int level) {
        active.add(player.getUniqueId());
        handRaisedTick.put(player.getUniqueId(), Bukkit.getCurrentTick());
        return true;
    }

    /**
     * Drops the channel state when the skill is unequipped, for any reason.
     *
     * <p>Nothing else covers this. {@link ChannelSkill}'s quit and death handlers clear
     * {@code active}, and {@link #onUpdate()} clears both once the player is gone -- but a build
     * change leaves the player online and holding the item, so the channel simply persists into a
     * loadout that no longer has the skill.
     */
    @Override
    public void invalidatePlayer(Player player, Gamer gamer) {
        active.remove(player.getUniqueId());
        handRaisedTick.remove(player.getUniqueId());
    }

    /**
     * Clears the start tick on the cancel paths {@link ChannelSkill} owns.
     *
     * <p>{@code ChannelSkill.cancel} removes the player from {@code active} itself but knows
     * nothing about this map, so silence, levitation, stun, water and death all ended the channel
     * while leaving its origin tick behind.
     */
    @Override
    public void onCancel(Player player) {
        handRaisedTick.remove(player.getUniqueId());
    }

    @Override
    public Action[] getActions() {
        return SkillActions.RIGHT_CLICK;
    }

    @Override
    public void loadSkillConfig() {
        activeBaseDuration = getConfig("activeBaseDuration", 0.6, Double.class);
        activeDurationIncreasePerLevel = getConfig("activeDurationIncreasePerLevel", 0.0, Double.class);
        baseDamageDelay = getConfig("baseDamageDelay", 0.4, Double.class);
        damageDelayIncreasePerLevel = getConfig("damageDelayIncreasePerLevel", 0.0, Double.class);
        successBaseCooldown = getConfig("successBaseCooldown", 0.8, Double.class);
        successCooldownDecreasePerLevel = getConfig("successCooldownDecreasePerLevel", 0.1, Double.class);
        channelTimeCooldownMultiplier = getConfig("channelTimeCooldownMultiplier", 1.0, Double.class);
        channelTimeCooldownMultiplierDecreasePerLevel = getConfig("channelTimeCooldownMultiplierDecreasePerLevel", 0.0, Double.class);
    }
}
