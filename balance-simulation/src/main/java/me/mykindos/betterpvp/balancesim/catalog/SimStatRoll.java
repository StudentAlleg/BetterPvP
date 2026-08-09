package me.mykindos.betterpvp.balancesim.catalog;

import me.mykindos.betterpvp.core.item.component.impl.stat.ItemStat;
import me.mykindos.betterpvp.core.item.component.impl.stat.StatContainerComponent;
import me.mykindos.betterpvp.core.item.component.impl.stat.StatType;

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
     * Rewrites a stat container so every stat in it sits at this roll, <em>as modifier stats</em>.
     *
     * <p>Returns the container unchanged for {@link #BASE}, so the common path allocates nothing and
     * a sweep that does not vary the axis produces byte-identical items to one taken before the axis
     * existed. That matters for more than speed: it is what lets a stored {@code BASE} baseline stay
     * comparable to a new run rather than being invalidated by the axis being added.
     *
     * <h2>Why the roll has to land in the modifier half</h2>
     * This method used to rewrite the base stats, which does not survive the trip onto an entity and
     * is why the axis measured nothing for its first three runs. {@code StatContainerSerializer}
     * writes <em>only</em> modifier stats to the item's {@code PersistentDataContainer}, and on the
     * way back it reads the base stats off the registered {@code BaseItem} rather than off the
     * stack -- deliberately, so that editing a config value applies to items already in circulation.
     * A rolled base stat is therefore discarded by {@code createItemStack} and replaced with the
     * configured one by {@code fromItemStack}, which is the round trip every combatant's weapon and
     * armour makes: {@code MeleeDamageStatHandler} re-reads the stat off the held stack on every
     * swing, and {@code EntityHealthService} reads armour the same way.
     *
     * <p>The symptom was total and silent. {@code champions:thornfang} rolls 5 / 6 / 7 and measured
     * {@code dmg_per_hit} 6.000 at all three; {@code role_set_max} targets had byte-identical HP to
     * {@code role_set} ones. Every {@code min} and {@code max} row was a duplicate of its {@code
     * base} row, so the axis tripled the weapon space and doubled the armoured target space while
     * adding no information -- and triplicated rows average to exactly what the base rows alone
     * would, so no aggregate looked wrong.
     *
     * <p>A modifier of the same {@code StatType} <em>replaces</em> its base stat rather than adding
     * to it ({@code StatContainerComponent.getStats}), so the rolled value is the value the game
     * reads, not a bonus on top of it. Base stats are left untouched so the item still describes
     * where it came from.
     *
     * <p>This does mean a rolled item looks reforged rather than freshly dropped. That is invisible
     * here -- these stacks exist for the duration of a duel and no player ever sees one -- and it is
     * the same representation a reforged item in a real inventory has, so it goes through the live
     * pipeline along exactly the paths a real item does.
     */
    public StatContainerComponent apply(StatContainerComponent container) {
        if (this == BASE) {
            return container;
        }

        final List<ItemStat<?>> modifiers = new ArrayList<>();
        final List<StatType<?>> overridden = new ArrayList<>();
        // Existing modifiers first, and they win: a modifier already overrides its base stat, so
        // emitting a rolled copy of that base stat too would put two stats of one type in the
        // container and leave which one the game reads down to list order.
        for (ItemStat<?> stat : container.getModifierStats()) {
            modifiers.add(roll(stat));
            overridden.add(stat.getType());
        }
        for (ItemStat<?> stat : container.getBaseStats()) {
            if (!overridden.contains(stat.getType())) {
                modifiers.add(roll(stat));
            }
        }
        return new StatContainerComponent(container.getBaseStats(), modifiers);
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
