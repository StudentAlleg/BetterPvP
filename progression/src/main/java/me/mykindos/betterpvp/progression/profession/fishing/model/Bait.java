package me.mykindos.betterpvp.progression.profession.fishing.model;

import lombok.Getter;
import lombok.Setter;
import me.mykindos.betterpvp.core.framework.BPvPPlugin;
import me.mykindos.betterpvp.core.utilities.UtilBlock;
import me.mykindos.betterpvp.core.utilities.UtilEntity;
import me.mykindos.betterpvp.core.utilities.UtilLocation;
import me.mykindos.betterpvp.core.utilities.UtilMath;
import me.mykindos.betterpvp.core.utilities.model.ProgressColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public abstract class Bait {

    private final UUID uuid;

    private ArmorStand referenceEntity;
    protected ArmorStand floatingEntity;

    private final Set<WeakReference<FishHook>> hooks = new HashSet<>();



    /**
     * This represents time in ticks since the entity has been alive
     */
    @Getter private long currentTick = 0L;

    /**
     * // This represents the time in ticks the totem has been active for
     */
    @Getter private long aliveTicks = 0L;

    @Getter @Setter
    private long durationInTicks;

    /**
     * Represents whether the totem is active or not (hit the ground once)
     */
    private boolean active = false;

    protected Bait(double duration) {
        this.uuid = UUID.randomUUID();
        this.durationInTicks = (long) (duration * 20L);
    }

    public final Location getLocation() {
        return floatingEntity.getEyeLocation();
    }

    public List<FishHook> getTrackedHooks() {
        return hooks.stream().map(WeakReference::get).filter(Objects::nonNull).toList();
    }

    public boolean hasExpired() {
        return aliveTicks > getDurationInTicks() || (!active && currentTick > getDurationInTicks() * 2)
                || (currentTick > 10 * 20 && !isInWater(referenceEntity.getLocation()));
    }

    protected void tick() {

    }

    /**
     * Called whenever a hook should be tracked by this bait
     * @param hook The hook to track
     */
    public final void track(FishHook hook) {
        hooks.add(new WeakReference<>(hook));
        onTrack(hook);
    }

    public abstract String getType();

    public abstract Material getMaterial();
    public abstract double getRadius();

    protected abstract void onTrack(FishHook hook);

    /**
     * Check if this bait can affect this hook
     * @param hook The hook to check
     * @return True if this bait can affect the hook
     */
    public boolean doesAffect(FishHook hook) {
        return hook.getLocation().distanceSquared(getLocation()) <= Math.pow(getRadius(), 2);
    }

    /**
     * Spawn the bait at the given location
     * @param location The location to spawn the bait at
     * @param velocity The velocity to apply to the {@link ArmorStand} entity after being spawned
     */
    public final void spawn(BPvPPlugin plugin, @NotNull Location location, @NotNull Vector velocity) {
        // Generate item
        final ItemStack item = new ItemStack(getMaterial());

        // Spawn the armor stands
        this.referenceEntity = UtilEntity.createUtilityArmorStand(location);
        this.referenceEntity.setVelocity(velocity);
        this.floatingEntity = UtilEntity.createUtilityArmorStand(location);
        this.floatingEntity.getEquipment().setHelmet(item, true);

        new BukkitRunnable() {
            private final float frequency = 360.0f / 80L; // One full cycle every 80 ticks
            private final float amplitude = 0.3f; // The height difference for each cycle (-1 to 1)
            private final long fullRotationTicks = 120L; // How many ticks does it take to complete a full rotation

            @Override
            public void run() {
                // Clean tracked hooks
                hooks.removeIf(hook -> hook.get() == null || !Objects.requireNonNull(hook.get()).isValid());

                // Only activate the totem after it has been grounded after 8 ticks have passed since it was thrown
                currentTick++;
                boolean feetInWater = isInWater(referenceEntity.getEyeLocation().subtract(0.0, 0.07, 0.0));
                boolean headInWater = isInWater(referenceEntity.getEyeLocation());
                active = active || (currentTick > 8 && feetInWater && !headInWater);

                if (active) {
                    aliveTicks++;
                } else if (headInWater) {
                    referenceEntity.teleport(referenceEntity.getLocation().add(0.0, 0.07, 0.0));
                }

                // Expire the totem after it has been active for the max duration or the ticker has been going on for twice the duration.
                // We do this to prevent stray totems that never land on the ground in extreme cases
                if (hasExpired()) {
                    active = false;
                    floatingEntity.remove(); // Remove the entity
                    referenceEntity.remove(); // Remove reference entity
                    cancel(); // Cancel this task
                    return;
                }

                // Only update the totem's name and rotation/height if it is active
                if (active) {
                    float progress = aliveTicks / (float) getDurationInTicks();
                    double secondsLeft = Math.round((getDurationInTicks() - aliveTicks) / 20.0 * 10) / 10.0;
                    floatingEntity.customName(ProgressColor.of(progress).inverted().withText(secondsLeft + "s").decorate(TextDecoration.BOLD));
                    floatingEntity.setCustomNameVisible(true);
                    referenceEntity.setGravity(false); // If it's active, don't allow it to move, so it looks like it's floating

                    // Handle height
                    double sinHeight = Math.sin(Math.toRadians(currentTick * frequency)) * amplitude;
                    floatingEntity.teleport(referenceEntity.getLocation().add(0.0, sinHeight - referenceEntity.getEyeHeight(), 0.0));

                    // Handle rotation
                    double rotationDeg = currentTick * 360.0 / fullRotationTicks;
                    floatingEntity.setHeadPose(new EulerAngle(0.0, Math.toRadians(rotationDeg), 0.0));

                    // Handle particles
                    if (currentTick % 15 == 0) {
                        // Play particles on affected hooks
                        for (FishHook trackedHook : getTrackedHooks()) {
                            final Location particleLoc = trackedHook.getLocation();
                            Particle.HAPPY_VILLAGER.builder().location(particleLoc).offset(0.3, 0.3, 0.3).receivers(60, true).spawn();
                        }
                    }

                    if (currentTick % getParticleInterval() == 0) {
                        doParticles();
                    }
                } else {
                    floatingEntity.teleport(referenceEntity.getLocation().subtract(0, referenceEntity.getEyeHeight(), 0));
                    Particle.CLOUD.builder().extra(0).location(floatingEntity.getEyeLocation()).receivers(60, true).spawn();
                }

                tick();
            }
        }.runTaskTimer(plugin, 0L, 1L);

    }

    protected int getParticleInterval() {
        return 10;
    }

    protected void doParticles() {
        // Play random splash particles in nearby water
        int particleCount = (int) (getRadius() * 25);
        final Collection<Player> nearby = getLocation().getWorld().getNearbyPlayers(getLocation(), 60);
        for (int i = 0; i < particleCount; i++) {
            // Generate random location
            final int angle = UtilMath.RANDOM.nextInt(360);
            final double dist = UtilMath.RANDOM.nextDouble() * getRadius();

            Location angleLocation = UtilLocation.fromFixedAngleDistance(getLocation(), dist, angle);
            Optional<Location> particleLocation = UtilLocation.getClosestSurfaceBlock(angleLocation,
                    3.0,
                    true,
                    block -> block.getType().equals(Material.WATER)
            );

            if (particleLocation.isEmpty()) {
                continue; // We can't play particles if we can't find a surface water
            }

            final Location loc = particleLocation.get().add(0.0, 1.05, 0.0);
            Particle.SPLASH.builder().location(loc).receivers(nearby).spawn();
        }
    }

    private boolean isInWater(Location location) {
        return UtilBlock.isWater(location.getBlock()) || location.getBlock().getType().name().endsWith("ICE");
    }


}
