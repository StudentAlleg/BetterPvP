package me.mykindos.betterpvp.champions.champions.skills.skills.warlock.sword;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.data.SkillActions;
import me.mykindos.betterpvp.champions.champions.skills.types.CooldownSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.CrowdControlSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.DamageSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.InteractSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.OffensiveSkill;
import me.mykindos.betterpvp.champions.combat.damage.SkillDamageCause;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.framework.customtypes.CustomArmourStand;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.locale.Translations;
import me.mykindos.betterpvp.core.utilities.UtilBlock;
import me.mykindos.betterpvp.core.utilities.UtilDamage;
import me.mykindos.betterpvp.core.utilities.UtilEntity;
import me.mykindos.betterpvp.core.utilities.UtilMath;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilVelocity;
import me.mykindos.betterpvp.core.utilities.math.VelocityData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import static me.mykindos.betterpvp.core.combat.cause.DamageCauseCategory.RANGED;

@Singleton
@BPvPListener
public class Grasp extends Skill implements InteractSkill, CooldownSkill, Listener, OffensiveSkill, CrowdControlSkill, DamageSkill {

    /** How long a single skull stays at one spot before it is moved on or taken down. */
    private static final long SKULL_LIFETIME_MILLIS = 200L;

    /**
     * The helmet every skull wears. Shared because {@code setHelmet} copies what it is given, so no
     * stand ever sees another's instance.
     */
    private static final ItemStack SKULL_HELMET = new ItemStack(Material.WITHER_SKELETON_SKULL);

    private final WeakHashMap<Player, ArrayList<LivingEntity>> cooldownJump = new WeakHashMap<>();

    /** One entry per cast still showing skulls. Drained by {@link #onUpdate()}. */
    private final List<SkullTrail> trails = new ArrayList<>();

    private double baseDistance;

    private double distanceIncreasePerLevel;

    private double baseDamage;

    private double damageIncreasePerLevel;

    private double speed;
    private double speedIncreasePerLevel;

    @Inject
    public Grasp(Champions champions, ChampionsManager championsManager) {
        super(champions, championsManager);
    }

    @Override
    public String getName() {
        return "Grasp";
    }

    @Override
    public Component[] getDescription(int level) {
        Component distance = getValueComponent(this::getDistance, level);
        Component damage = getValueComponent(this::getDamage, level);
        Component cooldown = getValueComponent(this::getCooldown, level);
        return Translations.componentLines("champions.skill.warlock.grasp.description", distance, damage, cooldown);
    }

    public double getDistance(int level) {
        return baseDistance + ((level - 1) * distanceIncreasePerLevel);
    }

    public double getDamage(int level) {
        return baseDamage + ((level - 1) * damageIncreasePerLevel);
    }

    public double getSpeed(int level) {
        return speed + ((level - 1) * speedIncreasePerLevel);
    }

    @Override
    public Role getClassType() {
        return Role.WARLOCK;
    }


    /**
     * The skulls thrown by one cast, moved around rather than respawned.
     *
     * <p>A cast places thirty skulls every two ticks for forty ticks. Spawning each of those meant
     * 600 entity spawns per cast, and every spawn runs {@code ChunkMap.addEntity ->
     * TrackedEntity.updatePlayers}, which is linear in the players in the world. That put
     * {@code createArmourStand} at 5.9% of an entire server thread in a balance-sim profile -- three
     * times what the skill's damage cost -- and it is the same cost on a busy live server.
     *
     * <p>A skull only ever stands for {@link #SKULL_LIFETIME_MILLIS}, so by the time a third batch
     * is placed the first batch is already due to come down. Moving one of those instead of taking
     * it down and putting a fresh one up elsewhere looks the same and holds the spawn count to the
     * two batches on screen at once -- sixty per cast rather than six hundred.
     *
     * <p>Purely cosmetic either way: callers scatter the position randomly, so nothing that decides
     * the outcome of a fight may be read from a skull. Damage is dealt separately by
     * {@link #damageNearby(Player, Location, int)} against the unscattered path.
     */
    private static final class SkullTrail {

        /** Standing skulls, oldest first -- the lifetime is fixed, so placement order is expiry order. */
        private final ArrayDeque<PlacedSkull> shown = new ArrayDeque<>();

        /** Set once the cast stops placing skulls, after which {@link #cull(long)} takes them down. */
        private boolean finished;

        private void place(Location loc, long now) {
            ArmorStand stand = reclaim(now);
            if (stand == null) {
                final CustomArmourStand handle = new CustomArmourStand(((CraftWorld) loc.getWorld()).getHandle());
                stand = (ArmorStand) handle.spawn(loc);
                stand.setVisible(false);
                stand.getEquipment().setHelmet(SKULL_HELMET);
                stand.setGravity(false);
                stand.setSmall(true);
            } else {
                stand.teleport(loc);
            }

            stand.setHeadPose(new EulerAngle(UtilMath.randomInt(360), UtilMath.randomInt(360), UtilMath.randomInt(360)));
            shown.add(new PlacedSkull(stand, now + SKULL_LIFETIME_MILLIS));
        }

        /**
         * The oldest skull if it has stood its time, ready to be moved on. Skips any that something
         * else has already removed, since a stale handle cannot be teleported.
         */
        private @Nullable ArmorStand reclaim(long now) {
            while (!shown.isEmpty() && shown.peek().expiresAt() <= now) {
                final ArmorStand candidate = shown.poll().stand();
                if (candidate.isValid()) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Takes down skulls that have stood their time, once the cast has stopped feeding this trail.
         *
         * <p>Only once it has stopped: while the cast is running, {@link #place} is what recycles an
         * expired skull, and removing it here first would put the spawn count straight back to one
         * per skull. Gating on {@link #finished} rather than on elapsed time keeps that true at any
         * tick rate -- a wall-clock grace period would stop reclaiming as soon as two ticks took
         * longer than the grace, which is exactly what happens in a loaded simulation run.
         *
         * @return whether this trail is done with and can be dropped
         */
        private boolean cull(long now) {
            if (!finished) {
                return false;
            }
            while (!shown.isEmpty() && shown.peek().expiresAt() <= now) {
                final ArmorStand stand = shown.poll().stand();
                if (stand.isValid()) {
                    stand.remove();
                }
            }
            return shown.isEmpty();
        }

        private void finish() {
            finished = true;
        }
    }

    private record PlacedSkull(ArmorStand stand, long expiresAt) {
    }

    private void damageNearby(Player player, Location loc, int level) {
        for (LivingEntity target : UtilEntity.getNearbyEnemies(player, loc, 1)) {
            if (target.getLocation().distance(player.getLocation()) < 3) continue;
            Location targetLocation = player.getLocation();
            targetLocation.add(targetLocation.getDirection().normalize().multiply(2));

            if (!cooldownJump.get(player).contains(target)) {

                UtilDamage.doDamage(new DamageEvent(target,
                        player,
                        null,
                        new SkillDamageCause(this).withCategory(RANGED),
                        getDamage(level),
                        getName()));
                cooldownJump.get(player).add(target);
                VelocityData velocityData = new VelocityData(UtilVelocity.getTrajectory(target.getLocation(), targetLocation), 1.0, false, 0, 0.5, 1, true);
                UtilVelocity.velocity(target, player, velocityData);
            }
        }

    }


    @UpdateEvent
    public void onUpdate() {
        if (trails.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        trails.removeIf(trail -> trail.cull(now));
    }


    @Override
    public SkillType getType() {
        return SkillType.SWORD;
    }

    @Override
    public boolean activate(Player player, int level) {
        Block block = player.getTargetBlock(null, (int) getDistance(level));
        Location startPos = player.getLocation();

        final Vector v = player.getLocation().toVector().subtract(block.getLocation().toVector()).normalize().multiply(getSpeed(level));
        v.setY(0);

        final Location loc = block.getLocation().add(v);
        cooldownJump.put(player, new ArrayList<>());

        final SkullTrail trail = new SkullTrail();
        trails.add(trail);

        final BukkitTask runnable = new BukkitRunnable() {

            @Override
            public void run() {

                boolean skip = false;
                if ((loc.getBlock().getType() != Material.AIR)
                        && UtilBlock.solid(loc.getBlock())) {

                    loc.add(0.0D, 1.0D, 0.0D);
                    if ((loc.getBlock().getType() != Material.AIR)
                            && UtilBlock.solid(loc.getBlock())) {
                        skip = true;
                    }

                }


                Location compare = loc.clone();
                compare.setY(startPos.getY());
                if (compare.distance(startPos) < 1) {
                    // Nothing will place into this trail again, so hand it to onUpdate to take down
                    // rather than leaving it standing until the delayed canceller fires.
                    trail.finish();
                    cancel();
                    return;
                }


                if ((loc.clone().add(0.0D, -1.0D, 0.0D).getBlock().getType() == Material.AIR)) {
                    loc.add(0.0D, -1.0D, 0.0D);
                }


                for (int i = 0; i < 10; i++) {

                    loc.add(v);
                    if (!skip) {
                        Location tempLoc = new Location(player.getWorld(), loc.getX() + UtilMath.randDouble(-2D, 2.0D), loc.getY() + UtilMath.randDouble(0.0D, 0.5D) - 0.50,
                                loc.getZ() + UtilMath.randDouble(-2.0D, 2.0D));

                        final long now = System.currentTimeMillis();
                        trail.place(tempLoc.clone(), now);
                        trail.place(tempLoc.clone().add(0, 1, 0), now);
                        trail.place(tempLoc.clone().add(0, 2, 0), now);

                        // Damage tracks the unscattered path, at the heights the skulls average out
                        // to (the Y jitter above is uniform over [0, 0.5) less 0.50, so -0.25).
                        final Location hitLoc = loc.clone().add(0.0D, -0.25D, 0.0D);
                        damageNearby(player, hitLoc, level);
                        damageNearby(player, hitLoc.clone().add(0, 1, 0), level);
                        damageNearby(player, hitLoc.clone().add(0, 2, 0), level);

                        if (i % 2 == 0) {
                            player.getWorld().playSound(tempLoc, Sound.ENTITY_VEX_DEATH, 0.3f, 0.3f);
                        }
                    }
                }


            }

        }.runTaskTimer(champions, 0, 2);


        new BukkitRunnable() {

            @Override
            public void run() {
                runnable.cancel();
                trail.finish();
                cooldownJump.get(player).clear();

            }

        }.runTaskLater(champions, 40);
        return true;


    }

    @Override
    public Action[] getActions() {
        return SkillActions.RIGHT_CLICK;
    }

    @Override
    public double getCooldown(int level) {
        return cooldown - ((level - 1) * cooldownDecreasePerLevel);
    }


    @Override
    public boolean canUse(Player player) {
        int level = getLevel(player);
        Block block = player.getTargetBlock(null, (int) getDistance(level));
        if (block.getLocation().distance(player.getLocation()) < 3) {
            UtilMessage.message(player, getClassType().getDisplayName(), "champions.skill.warlock.grasp.too-close", getDisplayName().color(NamedTextColor.GREEN));
            return false;
        }

        return true;
    }

    @Override
    public void loadSkillConfig() {
        baseDistance = getConfig("baseDistance", 10.0, Double.class);
        distanceIncreasePerLevel = getConfig("distanceIncreasePerLevel", 5.0, Double.class);

        baseDamage = getConfig("baseDamage", 2.0, Double.class);
        damageIncreasePerLevel = getConfig("damageIncreasePerLevel", 1.0, Double.class);

        speed = getConfig("speed", 0.3, Double.class);
        speedIncreasePerLevel = getConfig("speedIncreasePerLevel", 0.0, Double.class);
    }
}
