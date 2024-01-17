package me.mykindos.betterpvp.champions.champions.skills.skills.mage.passives;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.ChampionsManager;
import me.mykindos.betterpvp.champions.champions.skills.types.ActiveToggleSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.EnergySkill;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.effects.EffectType;
import me.mykindos.betterpvp.core.framework.customtypes.KeyValue;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.utilities.UtilBlock;
import me.mykindos.betterpvp.core.utilities.UtilFormat;
import me.mykindos.betterpvp.core.utilities.UtilLocation;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilPlayer;
import me.mykindos.betterpvp.core.utilities.events.EntityProperty;
import me.mykindos.betterpvp.core.world.blocks.WorldBlockHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
@BPvPListener
public class ArcticArmour extends ActiveToggleSkill implements EnergySkill {

    private final WorldBlockHandler blockHandler;

    private int baseRadius;
    private int radiusIncreasePerLevel;
    private double baseDuration;
    private double durationIncreasePerLevel;
    private int resistanceStrength;
    private int slownessStrength;

    @Inject
    public ArcticArmour(Champions champions, ChampionsManager championsManager, WorldBlockHandler blockHandler) {
        super(champions, championsManager);
        this.blockHandler = blockHandler;
    }

    @Override
    public String getName() {
        return "Arctic Armour";
    }

    @Override
    public String[] getDescription(int level) {
        return new String[]{
                "Drop your Sword / Axe to toggle",
                "",
                "Create a freezing area around",
                "you in a <val>" + getRadius(level )+ "</val> Block radius",
                "",
                "Allies inside this area receive <effect>Resistance " + UtilFormat.getRomanNumeral(resistanceStrength + 1) + "</effect>, and",
                "enemies inside this area receive <effect>Slowness " + UtilFormat.getRomanNumeral(slownessStrength + 1) + "</effect>",
                "",
                "Energy / Second: <val>" + getEnergy(level)
        };
    }

    public int getRadius(int level) {
        return baseRadius + level * radiusIncreasePerLevel;
    }

    public double getDuration(int level) {
        return baseDuration + level * durationIncreasePerLevel;
    }

    @Override
    public String getDefaultClassString() {
        return "mage";
    }
    @UpdateEvent(delay = 1000)
    public void audio() {
        for (UUID uuid : active) {
            Player cur = Bukkit.getPlayer(uuid);
            if (cur != null) {
                cur.getWorld().playSound(cur.getLocation(), Sound.WEATHER_RAIN, 0.3F, 0.0F);
            }
        }
    }

    @UpdateEvent(delay = 125)
    public void snowAura() {
        Iterator<UUID> iterator = active.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                iterator.remove();
                continue;
            }

            int level = getLevel(player);
            final int distance = getRadius(level);
            if (level <= 0) {
                iterator.remove();
                continue;
            }

            if (!championsManager.getEnergy().use(player, getName(), getEnergy(level) / 2, true)) {
                iterator.remove();
                continue;
            }

            // Apply resistance and slow effects
            final List<KeyValue<Player, EntityProperty>> nearby = UtilPlayer.getNearbyPlayers(player, distance);
            nearby.add(new KeyValue<>(player, EntityProperty.FRIENDLY));
            for (KeyValue<Player, EntityProperty> nearbyEnt : nearby) {
                final Player target = nearbyEnt.getKey();
                final boolean friendly = nearbyEnt.getValue() == EntityProperty.FRIENDLY;

                if (friendly) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20, resistanceStrength));
                    championsManager.getEffects().addEffect(target, EffectType.RESISTANCE, resistanceStrength + 1, 1000);
                } else {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 20, slownessStrength));
                }
            }

            // Apply cue effects
            // Spin particles around the player in the radius
            final int angle = (int) ((System.currentTimeMillis() / 10) % 360);
            playEffects(player, distance, -angle);
            playEffects(player, distance, angle);
            playEffects(player, distance, -angle + 180);
            playEffects(player, distance, angle + 180);

            convertWaterToIce(player, getDuration(level), distance);
        }
    }

    private void playEffects(final Player player, float radius, float angle) {
        final Location reference = player.getLocation();
        reference.setPitch(0);

        final Location relative = UtilLocation.fromAngleDistance(reference, radius, angle);
        final Optional<Location> closestSurface = UtilLocation.getClosestSurfaceBlock(relative, 3, true);
        closestSurface.ifPresent(loc -> loc.add(0, 2.2, 0));
        final Location result = closestSurface.orElse(relative.add(0, 1.2, 0));
        Particle.CLOUD.builder().extra(0).location(result).receivers(60).spawn();
    }

    private void convertWaterToIce(Player player, double duration, int radius) {
        // Sort by height descending
        final HashMap<Block, Double> inRadius = UtilBlock.getInRadius(player.getLocation(), radius);
        Collection<Block> blocks = inRadius.keySet().stream()
                .sorted((b1, b2) -> b2.getLocation().getBlockY() - b1.getLocation().getBlockY())
                .toList();

        for (Block block : blocks) {
            if (block.getLocation().getY() > player.getLocation().getY()) {
                continue;
            }

            final boolean water = UtilBlock.isWater(block);
            if (!water && block.getType() != Material.ICE) {
                continue;
            }

            final Block top = block.getRelative(0, 1, 0);
            if (UtilBlock.isWater(top)) {
                continue;
            }

            final long expiryOffset = (long) (100 * (inRadius.get(block) * radius));
            final long delay = (long) Math.pow((1 - inRadius.get(block)) * radius, 2);
            blockHandler.scheduleRestoreBlock(block, Material.ICE, delay, ((long) duration * 1000) + expiryOffset, false);

            final double chance = Math.random();
            if (chance < 0.025) {
                Particle.SNOWFLAKE.builder().extra(0).location(block.getLocation()).receivers(60).spawn();
            }
        }
    }

    @Override
    public SkillType getType() {
        return SkillType.PASSIVE_B;
    }

    @Override
    public float getEnergy(int level) {
        return (float) (energy - ((level - 1) * energyDecreasePerLevel));
    }

    @Override
    public void toggle(Player player, int level) {
        if (active.contains(player.getUniqueId())) {
            active.remove(player.getUniqueId());
            UtilMessage.message(player, "Champions", "Arctic Armour: <red>Off");
        } else {
            active.add(player.getUniqueId());
            UtilMessage.message(player, "Champions", "Arctic Armour: <green>On");
        }
    }

    @Override
    public void loadSkillConfig() {
        baseRadius = getConfig("baseRadius", 2, Integer.class);
        radiusIncreasePerLevel = getConfig("radiusIncreasePerLevel", 1, Integer.class);
        baseDuration = getConfig("baseDuration", 2.0, Double.class);
        durationIncreasePerLevel = getConfig("durationIncreasePerLevel", 0.0, Double.class);

        resistanceStrength = getConfig("resistanceStrength", 0, Integer.class);
        slownessStrength = getConfig("slownessStrength", 0, Integer.class);
    }
}