package me.mykindos.betterpvp.core.utilities.model.projectile;

import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.Setter;
import me.mykindos.betterpvp.core.combat.events.EntityCanHurtEntityEvent;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import me.mykindos.betterpvp.core.utilities.UtilTime;
import me.mykindos.betterpvp.core.utilities.UtilVelocity;
import me.mykindos.betterpvp.core.utilities.math.VectorLine;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

@Getter
public abstract class Projectile {

    public static final Vector DEFAULT_GRAVITY = new Vector(0, -9.81, 0);
    public static final double DEFAULT_DRAG_COEFFICIENT = 0.2;

    @Setter
    protected boolean markForRemoval;
    protected final Player caster;
    protected final double hitboxSize;
    protected final int creationTick = Bukkit.getCurrentTick();
    protected Location location;
    protected boolean impacted;
    protected int impactTick;
    protected Vector velocity = new Vector();
    protected Vector gravity = new Vector(); // Default to a ray projectile
    protected double dragCoefficient = 0;
    protected final long aliveTicks;
    protected Location lastLocation;
    protected int lastTick = Bukkit.getCurrentTick();
    protected long elapsedTicks;

    /**
     * @param aliveTime how long the projectile lives, in milliseconds; quantised to ticks on the
     *                  way in so the callers' seconds-times-1000 idiom keeps working unchanged
     */
    protected Projectile(@Nullable Player caster, double hitboxSize, final Location location, long aliveTime) {
        this.caster = caster;
        this.hitboxSize = hitboxSize;
        this.location = location.clone();
        this.lastLocation = location;
        this.aliveTicks = UtilTime.millisToTicks(aliveTime);
    }

    public boolean isExpired() {
        return UtilTime.ticksElapsed(creationTick, aliveTicks);
    }

    protected Location[] interpolateLine() {
        return interpolateLine(0.5);
    }

    protected Location[] interpolateLine(double step) {
        return lastLocation == null || lastLocation.equals(location) || lastLocation.distanceSquared(location) < step * step
                ? new Location[]{location}
                : VectorLine.withStepSize(lastLocation, location, step).toLocations();
    }

    /**
     * Advances the projectile by the time since the last call, measured in server ticks.
     *
     * <p>Ticks rather than wall clock because {@link #move()} integrates position from this
     * delta. Measured in milliseconds, how far a projectile travelled depended on how long the
     * server actually took between two calls, so the same shot fired twice under identical
     * conditions landed in different places and impacted on different ticks. Every caller drives
     * this from a default {@code @UpdateEvent}, which is one tick, so the delta was meant to be a
     * constant 50 ms all along -- the wall clock was measuring jitter, not signal.
     *
     * <p>The delta is still measured rather than assumed to be 1, because a caller is free to
     * tick on a longer period and the integration has to stay proportional to elapsed time.
     */
    public void tick() {
        final int time = Bukkit.getCurrentTick();
        this.elapsedTicks = time - (long) lastTick;
        if (!impacted) {
            onTick();

            final Optional<RayTraceResult> result = checkCollision();
            if (result.isPresent()) {
                final CollisionResult collisionResult = onCollide(result.get());
                switch (collisionResult) {
                    case IMPACT:
                        impact(result.get().getHitPosition().toLocation(location.getWorld()), result.get());
                        break;
                    case REFLECT_BLOCKS:
                        if (result.get().getHitBlock() != null) {
                            final Vector normal = Objects.requireNonNull(result.get().getHitBlockFace()).getOppositeFace().getDirection();
                            this.location = result.get().getHitPosition().toLocation(location.getWorld());
                            this.velocity = this.velocity.clone().subtract(normal.multiply(2 * this.velocity.clone().dot(normal)));
                        } else {
                            move();
                        }
                        break;
                    case CONTINUE:
                        move();
                        break;
                }
            } else {
                move();
            }
            this.lastTick = time;
            return;
        }

        move();
        onTick();
        this.lastTick = time;
    }

    protected void move() {
        this.lastLocation = this.location.clone();
        // Converted back to milliseconds at the boundary rather than changing UtilVelocity's
        // signature: the integration is genuinely in seconds, and a tick-valued argument to a
        // parameter named elapsedMillis is the sort of mismatch that compiles and is off by fifty.
        UtilVelocity.applyGravity(this.location, this.velocity, this.gravity, this.dragCoefficient,
                elapsedTicks * UtilTime.MILLIS_PER_TICK);
    }

    protected CollisionResult onCollide(RayTraceResult result) {
        return CollisionResult.IMPACT;
    }

    protected boolean canCollideWith(Entity entity) {
        if(entity instanceof LivingEntity livingEntity) {
            EntityCanHurtEntityEvent entityCanHurtEntityEvent = UtilServer.callEvent(new EntityCanHurtEntityEvent(caster, livingEntity));
            if(entityCanHurtEntityEvent.getResult() == EntityCanHurtEntityEvent.Result.DENY) {
                return false;
            }
        }
        return entity != caster && entity instanceof LivingEntity && !(entity instanceof ArmorStand);
    }

    private Optional<RayTraceResult> checkCollision() {
        if (isExpired()) {
            return Optional.of(new RayTraceResult(location.toVector()));
        }

        if (velocity == null || !velocity.toVector3d().isFinite() || velocity.lengthSquared() == 0) {
            return Optional.empty();
        }

        if (!location.toVector().toVector3d().isFinite()) {
            return Optional.empty();
        }

        final RayTraceResult rayTrace = location.getWorld().rayTrace(location,
                velocity,
                velocity.length() / 20,
                FluidCollisionMode.NEVER,
                true,
                hitboxSize,
                this::canCollideWith);
        if (rayTrace != null) {
            return Optional.of(rayTrace);
        }

        return Optional.empty();
    }

    public final void impact() {
        impact(location, new RayTraceResult(location.toVector()));
    }

    public final void impact(Location location, RayTraceResult result) {
        Preconditions.checkState(!impacted, "Projectile already impacted");
        this.location = location;
        this.lastLocation = location;
        impacted = true;
        impactTick = Bukkit.getCurrentTick();
        onImpact(location, result);
    }

    protected abstract void onTick();

    protected void onImpact(Location location, RayTraceResult result) {
        // Override
    }

    public void redirect(Vector vector) {
        this.velocity = vector == null ? new Vector() : vector;
    }

    public enum CollisionResult {
        IMPACT,
        CONTINUE,
        REFLECT_BLOCKS
    }

}
