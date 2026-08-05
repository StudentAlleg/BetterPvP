package me.mykindos.betterpvp.balancesim.catalog;

import me.mykindos.betterpvp.core.item.component.impl.stat.ItemStat;
import me.mykindos.betterpvp.core.item.component.impl.stat.StatContainerComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where in its roll envelope an item's stats sit.
 *
 * <p>Every stat an item carries is configured as {@code {base, min, max}} -- {@code damage} and
 * {@code attack_speed} on a weapon, {@code health} on an armour piece -- and a dropped item rolls
 * somewhere inside that band. Nothing in the simulator rolled anything before this existed:
 * {@code ItemFactory.create} copies the configured stats through untouched, so every sweep ever
 * taken measured the {@link #BASE} corner and nothing else. {@code SimWeaponProfile} has recorded
 * all three figures since it was written and said so in its own javadoc; this is the axis that
 * finally exercises the other two.
 *
 * <h2>One tier for the whole item, not per stat</h2>
 * A real item rolls each stat independently, so a weapon's true space is the product of its stats'
 * envelopes -- damage x attack speed, and any other stat added later. This enumerates three
 * <em>corners</em> of that space instead: everything at minimum, everything as configured,
 * everything at maximum. Two reasons, and the second is the one that decides it:
 * <ul>
 *   <li>The corners are the extremes. {@link #MIN} is the worst instance of the item that can
 *       exist and {@link #MAX} the best, so together they bound what the item can do. An
 *       independent sweep would fill in the interior without widening the answer.</li>
 *   <li>Per-stat independence is multiplicative on an axis that is already multiplied by every
 *       other axis. Two stats at three points each is nine weapons where this is three, and a
 *       stat added to weapons later would raise it again with no change here -- silently, which
 *       is the failure mode this catalog is built to avoid.</li>
 * </ul>
 *
 * <p>So a {@link #MIN} row is "this weapon at its floor", not "this weapon with minimum damage and
 * an average swing". Anything asking how damage and attack speed trade off against each other needs
 * a different axis, and will not find it here.
 */
public enum SimStatRoll {

    /** Every stat at the bottom of its band: {@code ItemStat.getRangeMin()}. */
    MIN,

    /** Every stat as configured. What every sweep before this axis existed measured. */
    BASE,

    /** Every stat at the top of its band: {@code ItemStat.getRangeMax()}. */
    MAX;

    /** The roll a sweep that does not vary this axis uses, and the one stored rows were taken at. */
    public static final SimStatRoll DEFAULT = BASE;

    /** Lowercase name, as written to {@code sim_build.weapon_roll} and into armour set ids. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Rewrites a stat container so every stat in it sits at this roll.
     *
     * <p>Returns the container unchanged for {@link #BASE}, so the common path allocates nothing and
     * a sweep that does not vary the axis produces byte-identical items to one taken before the axis
     * existed. That matters for more than speed: it is what lets a stored {@code BASE} baseline stay
     * comparable to a new run rather than being invalidated by the axis being added.
     *
     * <p>Modifier stats are rolled alongside base stats. An item straight out of the registry has
     * none -- they are what reforging writes -- but rolling only half of a container would produce an
     * item whose lore and whose behaviour disagreed, which is worse than either extreme.
     */
    public StatContainerComponent apply(StatContainerComponent container) {
        if (this == BASE) {
            return container;
        }
        return new StatContainerComponent(rollAll(container.getBaseStats()),
                rollAll(container.getModifierStats()));
    }

    /**
     * Applies this roll to an item's stat container if it has one.
     *
     * <p>An item with no container is returned untouched rather than treated as an error. A weapon
     * in {@code Group.MELEE} with no melee profile should not exist, but {@code SimEquipment.profileOf}
     * already chooses to sweep such an item with zeroes on the row rather than fail the run, and this
     * agrees with it: one malformed item should not take down an otherwise valid sweep.
     */
    public Optional<StatContainerComponent> applyTo(Optional<StatContainerComponent> container) {
        return container.map(this::apply);
    }

    private List<ItemStat<?>> rollAll(List<ItemStat<?>> stats) {
        final List<ItemStat<?>> rolled = new ArrayList<>(stats.size());
        for (ItemStat<?> stat : stats) {
            rolled.add(roll(stat));
        }
        return rolled;
    }

    /**
     * Moves one stat to this roll's end of its band.
     *
     * <p>Generic so the wildcard on {@code ItemStat<?>} captures: {@code withValue} takes the stat's
     * own {@code T}, and {@code getRangeMin}/{@code getRangeMax} return it, so the value handed back
     * is always one the stat's own {@code StatType} accepts. That is what keeps
     * {@code ItemStat}'s {@code isValidValue} precondition satisfied without this method knowing what
     * kind of number it is holding.
     */
    private <T> ItemStat<T> roll(ItemStat<T> stat) {
        return switch (this) {
            case BASE -> stat;
            case MIN -> stat.withValue(stat.getRangeMin());
            case MAX -> stat.withValue(stat.getRangeMax());
        };
    }
}
