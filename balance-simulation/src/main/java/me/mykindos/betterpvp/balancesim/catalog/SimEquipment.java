package me.mykindos.betterpvp.balancesim.catalog;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.champions.champions.skills.data.SkillWeapons;
import me.mykindos.betterpvp.champions.item.component.armor.RoleArmorComponent;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.combat.health.EntityHealthService;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.item.BaseItem;
import me.mykindos.betterpvp.core.item.ItemFactory;
import me.mykindos.betterpvp.core.item.ItemInstance;
import me.mykindos.betterpvp.core.item.ItemRegistry;
import me.mykindos.betterpvp.core.item.component.impl.socketables.Socketable;
import me.mykindos.betterpvp.core.item.component.impl.socketables.SocketableContainerComponent;
import me.mykindos.betterpvp.core.item.component.impl.socketables.SocketableRegistry;
import me.mykindos.betterpvp.core.item.component.impl.stat.ItemStat;
import me.mykindos.betterpvp.core.item.component.impl.stat.StatContainerComponent;
import me.mykindos.betterpvp.core.item.component.impl.stat.StatTypes;
import me.mykindos.betterpvp.core.item.model.ArmorItem;
import me.mykindos.betterpvp.core.item.model.WeaponItem;
import me.mykindos.betterpvp.core.item.runeslot.RuneSlotDistribution;
import me.mykindos.betterpvp.core.item.runeslot.RuneSlotDistributionRegistry;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the loadout axes against the <em>live</em> {@code ItemRegistry}, never against a
 * hardcoded list of item keys.
 *
 * <p>Two callers need the same answers and must not disagree: {@link BalanceCatalog} enumerates
 * the axes and computes a target's durability from them, and {@code SimCombatant} equips the
 * resulting items on a fake player. If those two derived the loadout independently, a
 * {@code target_hp} column could describe armour the entity was not actually wearing -- the exact
 * class of drift this project exists to remove -- so both go through here.
 *
 * <p>Everything is read from the registry rather than named: weapons are whatever is registered as
 * a melee {@code WeaponItem}, a weapon is a booster if {@code SkillWeapons.isBooster} says its
 * material is, and a role's armour set is whichever {@code ArmorItem}s carry a
 * {@code RoleArmorComponent} naming that role. Adding an item to the game therefore adds it to the
 * sweep with no change here.
 */
@Singleton
@CustomLog
public class SimEquipment {

    /** Armour set id for a bare target: role base health and nothing on top. */
    public static final String NO_ARMOR = "none";

    /** Armour set id for the role's own registered armour set. */
    public static final String ROLE_ARMOR = "role_set";

    /**
     * The material whose registry fallback {@code RoleManager.equipWeapons} hands out, and so
     * what a phase 1 row was measured with. Resolved through the registry rather than assumed to
     * be any particular item, so it follows whatever is registered as the iron sword fallback.
     */
    private static final Material DEFAULT_WEAPON_MATERIAL = Material.IRON_SWORD;

    /**
     * The slots an armour set occupies, in the order a set is built and stripped.
     *
     * <p>Public because tearing a set off is not the inverse of putting one on and cannot be left to
     * the caller's own list: {@code SimCombatant.unequipArmor} has to visit exactly the slots this
     * class fills, or a piece it missed stays worn and its health modifier with it.
     */
    public static final List<EquipmentSlot> ARMOR_SLOTS =
            List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private final ItemRegistry itemRegistry;
    private final ItemFactory itemFactory;
    private final EntityHealthService entityHealthService;

    /**
     * Resolved axes, held for the duration of a sweep.
     *
     * <p>Not an optimisation for its own sake. {@code ItemRegistry.getKey} is a linear scan of
     * every registered item and {@link #skillTypeOf} materialises an {@code ItemStack} to ask
     * {@code SkillWeapons} about it, and the enumerator calls both once per skill per level per
     * weapon -- millions of times at the {@code FULL} tier, all on the main thread. Caching also
     * makes the sweep self-consistent: an item reload part-way through would otherwise let the
     * armour a target was measured in differ from the armour its {@code target_hp} was computed
     * from.
     */
    @Nullable
    private List<WeaponOption> meleeWeaponCache;
    @Nullable
    private WeaponOption defaultWeaponCache;
    @Nullable
    private List<WeaponOption> distinctMeleeWeaponCache;
    private final Map<String, SkillType> skillTypeCache = new HashMap<>();
    private final Map<Role, List<BaseItem>> armorCache = new EnumMap<>(Role.class);
    private final Map<String, List<RuneOption>> runeCache = new HashMap<>();
    private final Map<String, List<List<String>>> runeSetCache = new HashMap<>();
    private final Map<String, SimWeaponProfile> profileCache = new HashMap<>();
    @Nullable
    private Integer socketCeilingCache;

    /** Representative weapon key to every key sharing its profile, including the representative. */
    private final Map<String, List<String>> aliasCache = new HashMap<>();

    /**
     * Core's rune registry.
     *
     * <p>From Core's injector rather than injected, for the reason {@code SimStatePurge} documents:
     * {@code SocketableRegistry} takes {@code Core} as a constructor argument, and a just-in-time
     * binding in this sibling injector would build a second registry -- populated by its own reflection
     * pass, so holding a second set of {@code Socketable} instances. {@code hasRune} compares by
     * equality against the instance the <em>handler</em> injected, so a sweep would socket runes that
     * every handler then failed to recognise, and the rune axis would read as "runes do nothing".
     */
    private final SocketableRegistry socketableRegistry;

    /**
     * Core's rune slot distributions, the source of {@link #socketCeiling()}.
     *
     * <p>From Core's injector for the reason {@link #socketableRegistry} is: a just-in-time binding
     * in this sibling injector would construct a second registry, and this one reloads itself from
     * the database in its constructor. A duplicate would therefore issue its own query on
     * construction and then serve whatever the table held at that moment, drifting from the values
     * attunement actually rolls against.
     */
    private final RuneSlotDistributionRegistry runeSlotRegistry;

    @Inject
    public SimEquipment(ItemRegistry itemRegistry, ItemFactory itemFactory, EntityHealthService entityHealthService) {
        this.itemRegistry = itemRegistry;
        this.itemFactory = itemFactory;
        this.entityHealthService = entityHealthService;
        final var coreInjector = JavaPlugin.getPlugin(Core.class).getInjector();
        this.socketableRegistry = coreInjector.getInstance(SocketableRegistry.class);
        this.runeSlotRegistry = coreInjector.getInstance(RuneSlotDistributionRegistry.class);
    }

    /**
     * Drops the cached axes so the next sweep reads the registry afresh. Called once when a run
     * starts, which is the only point at which a config reload between runs could have changed
     * what is registered.
     */
    public void invalidate() {
        meleeWeaponCache = null;
        defaultWeaponCache = null;
        distinctMeleeWeaponCache = null;
        skillTypeCache.clear();
        armorCache.clear();
        runeCache.clear();
        runeSetCache.clear();
        profileCache.clear();
        aliasCache.clear();
        socketCeilingCache = null;
    }

    /**
     * One weapon of the sweep's weapon axis.
     *
     * @param key     the {@code ItemRegistry} namespaced key, as written to {@code sim_build.weapon}
     * @param booster whether holding it raises a SWORD/AXE/BOW skill's effective level by one
     */
    public record WeaponOption(String key, boolean booster) {
    }

    /**
     * Every registered melee weapon, ordered by key so a sweep enumerates the same space twice.
     *
     * <p>{@code Group.MELEE} is the filter rather than the item's class, because that is what
     * decides whether {@code WeaponItem} gives the item {@code MELEE_DAMAGE} and
     * {@code MELEE_ATTACK_SPEED} stats at all -- a ranged-only weapon has no melee profile to
     * measure.
     */
    public List<WeaponOption> meleeWeapons() {
        if (meleeWeaponCache != null) {
            return meleeWeaponCache;
        }
        final List<WeaponOption> weapons = new ArrayList<>();
        for (Map.Entry<NamespacedKey, BaseItem> entry : itemRegistry.getItems().entrySet()) {
            if (!(entry.getValue() instanceof WeaponItem weapon)) {
                continue;
            }
            if (!weapon.getGroups().contains(WeaponItem.Group.MELEE)) {
                continue;
            }
            weapons.add(new WeaponOption(entry.getKey().toString(),
                    SkillWeapons.isBooster(weapon.getModel().getType())));
        }
        weapons.sort(Comparator.comparing(WeaponOption::key));
        meleeWeaponCache = List.copyOf(weapons);
        return meleeWeaponCache;
    }

    /**
     * The weapon a combatant gets when the sweep is not varying the weapon axis: the registry's
     * fallback for {@link #DEFAULT_WEAPON_MATERIAL}, which is what {@code RoleManager.equipWeapons}
     * puts in a real player's inventory.
     *
     * @throws IllegalStateException if no item is registered as that fallback, which would mean
     *                               the registry had not finished loading when the sweep started
     */
    public WeaponOption defaultWeapon() {
        if (defaultWeaponCache != null) {
            return defaultWeaponCache;
        }
        final BaseItem item = itemRegistry.getFallbackItem(DEFAULT_WEAPON_MATERIAL);
        final NamespacedKey key = item == null ? null : itemRegistry.getKey(item);
        if (key == null) {
            throw new IllegalStateException("No registered fallback item for " + DEFAULT_WEAPON_MATERIAL
                    + "; the item registry is not ready for a simulation sweep");
        }
        defaultWeaponCache = new WeaponOption(key.toString(), SkillWeapons.isBooster(item.getModel().getType()));
        return defaultWeaponCache;
    }

    /** Every melee weapon that carries the booster {@code +1}. */
    public List<WeaponOption> boosterWeapons() {
        return meleeWeapons().stream().filter(WeaponOption::booster).toList();
    }

    /**
     * One representative per distinct {@link SimWeaponProfile}, ordered by key.
     *
     * <p>This is the reduction the full sweep is built on. The weapon axis multiplies every other
     * axis, so a duplicate weapon is not paid once but once per build per target per iteration -- and
     * duplicates are the common case, because a weapon tier is typically several models sharing one
     * stat block. Two weapons with the same profile produce the same duel by construction: everything
     * a duel can read off a weapon is in the profile (see {@link SimWeaponProfile} for what is in the
     * key and why).
     *
     * <p>The representative is the lowest key of its group, which is arbitrary but stable -- the same
     * requirement the armour set resolution has, and for the same reason. A run that picked a
     * different representative each time would produce build fingerprints that do not join across
     * runs, which is the one thing the patch-diff dashboard needs.
     *
     * <p>Nothing is dropped: {@link #weaponAliases} names every key a representative stands for, and
     * the catalog writes that list onto the build so the reduction is legible from the row rather
     * than only from this code.
     */
    public List<WeaponOption> distinctMeleeWeapons() {
        if (distinctMeleeWeaponCache != null) {
            return distinctMeleeWeaponCache;
        }

        // Insertion-ordered over a key-sorted input, so representatives come out key-sorted too.
        final Map<SimWeaponProfile, String> representatives = new LinkedHashMap<>();
        final Map<String, List<String>> aliases = new HashMap<>();
        final List<WeaponOption> distinct = new ArrayList<>();
        for (WeaponOption weapon : meleeWeapons()) {
            final SimWeaponProfile profile = profileOf(weapon.key());
            final String existing = representatives.get(profile);
            if (existing == null) {
                representatives.put(profile, weapon.key());
                aliases.computeIfAbsent(weapon.key(), key -> new ArrayList<>()).add(weapon.key());
                distinct.add(weapon);
                continue;
            }
            aliases.get(existing).add(weapon.key());
        }

        aliasCache.clear();
        aliases.forEach((key, group) -> aliasCache.put(key, List.copyOf(group)));
        distinctMeleeWeaponCache = List.copyOf(distinct);

        final int folded = meleeWeapons().size() - distinct.size();
        // Logged even at zero, for the reason the catalog logs its exclusion count: "0 folded" is the
        // only thing that distinguishes a registry of genuinely distinct weapons from a profile that
        // has stopped telling them apart, and neither is recoverable from a build total afterwards.
        log.info("Weapon axis: {} registered melee weapons reduce to {} distinct profiles ({} folded"
                + " into a representative and recorded as aliases)",
                meleeWeapons().size(), distinct.size(), folded).submit();
        // Every fold is named rather than only counted. A weapon disappearing from the sweep is
        // exactly the kind of reduction that should be arguable from the log: if two weapons that a
        // designer considers different were folded, the profile behind it is printed beside them.
        representatives.forEach((profile, key) -> {
            final List<String> group = aliasCache.get(key);
            if (group.size() > 1) {
                log.info("  {} stands for {} -- {}", key, group.subList(1, group.size()),
                        profile.describe()).submit();
            }
        });
        return distinctMeleeWeaponCache;
    }

    /**
     * Every weapon key that shares {@code weaponKey}'s profile, {@code weaponKey} first.
     *
     * <p>A single-element list for a weapon that dedupes with nothing, and for any weapon at all when
     * {@link #distinctMeleeWeapons()} has not been called -- an un-reduced sweep measured each weapon
     * in its own right, and claiming it stood for others would be false.
     */
    public List<String> weaponAliases(String weaponKey) {
        return aliasCache.getOrDefault(weaponKey, List.of(weaponKey));
    }

    /**
     * The measurable identity of a weapon: its melee stats, slot, booster status and rune options.
     *
     * <p>Read off the registered {@code BaseItem}'s own {@code StatContainerComponent}, which is what
     * {@code WeaponItem.reload} writes the configured {@code damage.*} and {@code attack_speed.*}
     * values into -- so the figures recorded on a build row are the ones the item is configured with,
     * not a second reading of the same YAML.
     *
     * <p>An item with no stat container yields zeroes rather than throwing. That is a weapon in
     * {@code Group.MELEE} carrying no melee profile, which should not happen; measuring it as a
     * distinct profile means it is swept in its own right and its zeroes are visible on the row,
     * whereas throwing would take down an otherwise valid sweep for one malformed item.
     *
     * @throws IllegalArgumentException if the key is not registered
     */
    public SimWeaponProfile profileOf(String weaponKey) {
        return profileCache.computeIfAbsent(weaponKey, key -> {
            final BaseItem item = itemRegistry.getItem(key);
            if (item == null) {
                throw new IllegalArgumentException("No registered item for weapon key " + key);
            }
            final SkillType slot = skillTypeOf(key);
            final List<String> runes = weaponRunes(key).stream().map(RuneOption::key).toList();
            final boolean booster = SkillWeapons.isBooster(item.getModel().getType());

            final Optional<StatContainerComponent> stats = item.getComponent(StatContainerComponent.class);
            if (stats.isEmpty()) {
                log.warn("Melee weapon {} has no stat container; it will be swept as its own profile"
                        + " and recorded with zero damage", key).submit();
                return new SimWeaponProfile(0, 0, 0, 0, 0, 0, booster,
                        slot == null ? SimWeaponProfile.UNKNOWN_SLOT : slot.name(), runes);
            }

            final Optional<ItemStat<Double>> damage = stats.get().getStat(StatTypes.MELEE_DAMAGE);
            final Optional<ItemStat<Double>> speed = stats.get().getStat(StatTypes.MELEE_ATTACK_SPEED);
            return new SimWeaponProfile(
                    damage.map(ItemStat::getValue).orElse(0.0),
                    damage.map(ItemStat::getRangeMin).orElse(0.0),
                    damage.map(ItemStat::getRangeMax).orElse(0.0),
                    speed.map(ItemStat::getValue).orElse(0.0),
                    speed.map(ItemStat::getRangeMin).orElse(0.0),
                    speed.map(ItemStat::getRangeMax).orElse(0.0),
                    booster,
                    slot == null ? SimWeaponProfile.UNKNOWN_SLOT : slot.name(),
                    runes);
        });
    }

    /**
     * Materialises a weapon for equipping. Goes through {@code ItemFactory.create} rather than a
     * bare {@code ItemStack}, because the stat handlers and {@code SkillWeapons.getTypeFrom} both
     * read the custom-item key out of the PDC, and only a factory-created stack carries it.
     *
     * @throws IllegalArgumentException if the key is not registered
     */
    public ItemStack weaponStack(String key) {
        return weaponStack(key, List.of(), SimStatRoll.DEFAULT);
    }

    /**
     * Materialises a weapon with runes socketed into it.
     *
     * <p>Socketed through the item's real {@code SocketableContainerComponent}, which is what makes the
     * rune take effect: every handler -- {@code BrutalityRuneHandler} and the rest -- reads the
     * container off the damager's held {@code ItemStack} via {@code ComponentLookupService} and asks
     * {@code hasRune}. Nothing here applies a rune's effect, and nothing here reads a rune's numbers;
     * the sim only decides which weapon carries which rune.
     *
     * <p>Sockets are sized to the rune list rather than taken from the item's own socket count. A
     * registered weapon ships with however many sockets its config gives it -- often zero, since sockets
     * are something a player upgrades into -- and the sweep's question is what a rune is worth, not what
     * a fresh drop happens to allow. The build's fingerprint records which runes were fitted, so a row
     * is never ambiguous about it.
     *
     * <p>The roll is applied to the created instance rather than to the registered {@code BaseItem}:
     * the registry holds one shared instance per item, and moving its stats would change every
     * weapon the sweep hands out afterwards -- including the ones already equipped on a resident
     * combatant, since {@code MeleeDamageStatHandler} re-reads the stat off the held stack on every
     * swing. Rolling the copy keeps a {@code MIN} build and a {@code MAX} build fightable at the
     * same time, which they must be: duels run concurrently.
     *
     * @param runeKeys rune keys as written to {@code sim_build.runes}, in catalog order
     * @param roll     where in its band each of the weapon's stats sits
     * @throws IllegalArgumentException if the weapon key or any rune key is not registered
     */
    public ItemStack weaponStack(String key, List<String> runeKeys, SimStatRoll roll) {
        final BaseItem item = itemRegistry.getItem(key);
        if (item == null) {
            throw new IllegalArgumentException("No registered item for weapon key " + key);
        }
        ItemInstance instance = itemFactory.create(item);
        instance = rolled(instance, roll);
        if (!runeKeys.isEmpty()) {
            final List<Socketable> socketables = new ArrayList<>(runeKeys.size());
            for (String runeKey : runeKeys) {
                socketables.add(rune(runeKey));
            }
            instance = instance.withComponent(
                    new SocketableContainerComponent(socketables.size(), socketables.size(), socketables));
        }
        return instance.createItemStack();
    }

    /** Moves an instance's stats to {@code roll}, or returns it untouched if it has no stats. */
    private static ItemInstance rolled(ItemInstance instance, SimStatRoll roll) {
        if (roll == SimStatRoll.BASE) {
            return instance;
        }
        return instance.getComponent(StatContainerComponent.class)
                .map(container -> instance.withComponent(roll.apply(container)))
                .orElse(instance);
    }

    /**
     * One rune of the sweep's rune axis.
     *
     * @param key    the rune's {@code NamespacedKey}, as written to {@code sim_build.runes}
     * @param name   the rune's stable identity string, only used in logs
     */
    public record RuneOption(String key, String name) {
    }

    /**
     * Every rune set a weapon can be carrying, from empty up to a full complement of sockets.
     *
     * <p>Combinations, not permutations: a set is enumerated once regardless of socket order, because
     * socket order is not part of a build's identity -- {@code BalanceCatalog.fingerprint} sorts the
     * rune keys before hashing, so two orderings are one build and enumerating both would measure the
     * same fight twice under one fingerprint.
     *
     * <p>Sets are ordered by size and then by the weapon's own rune order, so the empty set comes
     * first and a sweep enumerates the same space twice. That ordering is load-bearing for the same
     * reason {@code BalanceCatalog.interleaveByRole} exists: a rune sweep is long, and a prefix of it
     * should be a usable answer about the cheap sets rather than an arbitrary slice.
     *
     * @see #socketCeiling() for why the bound is not the weapon's own socket count
     */
    public List<List<String>> weaponRuneSets(String weaponKey) {
        return runeSetCache.computeIfAbsent(weaponKey,
                key -> runeSets(weaponRunes(key).stream().map(RuneOption::key).toList(), socketCeiling()));
    }

    /**
     * Every combination of {@code runes} of size {@code 0..ceiling}, smallest first.
     *
     * <p>Package-private rather than private so the sizes can be tested directly. This decides how
     * large every sweep that varies runes is -- an off-by-one either drops whole combination sizes
     * from the catalog or multiplies the duel count past what will finish, and neither is visible
     * afterwards from a build total nobody can check against an expected figure.
     *
     * @param ceiling most runes a weapon may carry; clamped to the rune count, so a weapon accepting
     *                fewer runes than it has sockets yields sets up to what it actually accepts
     */
    static List<List<String>> runeSets(List<String> runes, int ceiling) {
        final List<List<String>> sets = new ArrayList<>();
        for (int size = 0; size <= Math.min(ceiling, runes.size()); size++) {
            combinations(runes, size, 0, new ArrayList<>(), sets);
        }
        return List.copyOf(sets);
    }

    private static void combinations(List<String> runes,
                                     int size,
                                     int from,
                                     List<String> chosen,
                                     List<List<String>> out) {
        if (chosen.size() == size) {
            out.add(List.copyOf(chosen));
            return;
        }
        // Stop as soon as too few runes remain to reach the target size, so the recursion never
        // walks a branch that cannot produce a set.
        for (int index = from; index <= runes.size() - (size - chosen.size()); index++) {
            chosen.add(runes.get(index));
            combinations(runes, size, index + 1, chosen, out);
            chosen.remove(chosen.size() - 1);
        }
    }

    /**
     * The most runes any weapon can be carrying at once.
     *
     * <p>Deliberately <em>not</em> the socket count on the registered item. Every {@code WeaponItem}
     * is constructed with {@code new SocketableContainerComponent(0, 0)} and nothing in the item
     * config raises it, so a weapon as the registry ships it holds no runes at all -- reading the
     * ceiling off the item would make the rune axis a single empty set and the sweep would report,
     * truthfully and uselessly, that runes do nothing.
     *
     * <p>Sockets are something an item acquires: {@code AttunementButton} rolls {@code sockets} and
     * {@code maxSockets} from the {@link RuneSlotDistribution} for the item's purity, and
     * {@code SocketableImbuementRecipe} grants one directly. So the number of runes a weapon
     * <em>allows</em> is the highest {@code maxSockets} any purity can roll, which is what this
     * reads -- from the live registry, so a distribution edited in
     * {@code purity_rune_slot_distributions} reaches the sweep without a change here.
     *
     * <p>Weights of zero are excluded rather than counted. {@code PITIFUL} lists {@code "4": 0},
     * which is a socket count that purity can never actually produce; treating it as reachable would
     * add a whole combination size to every weapon's axis on the strength of an entry that exists
     * only to keep the weight maps the same shape.
     */
    public int socketCeiling() {
        if (socketCeilingCache != null) {
            return socketCeilingCache;
        }
        int ceiling = 0;
        for (RuneSlotDistribution distribution : runeSlotRegistry.getAllDistributions().values()) {
            for (Map.Entry<Integer, Integer> entry : distribution.getMaxSocketWeights().entrySet()) {
                if (entry.getValue() > 0) {
                    ceiling = Math.max(ceiling, entry.getKey());
                }
            }
        }
        socketCeilingCache = ceiling;
        log.info("Rune axis: a weapon may hold up to {} runes at once (highest maxSockets any purity"
                + " can roll); registered weapons ship with 0 sockets and acquire them by attunement",
                ceiling).submit();
        return ceiling;
    }

    /**
     * Every registered rune that can be socketed into {@code weaponKey}, ordered by key.
     *
     * <p>Compatibility is the rune's own {@code canApply}, so a rune declared for armour or for bows
     * only is not offered for a melee sword -- and a rune added to the game joins the sweep with no
     * change here, the same way a weapon does.
     *
     * <p>Ordered by key rather than by registration, because {@code SocketableRegistry} hands back a
     * {@code Set} built by reflection over a package: iteration order is a hash order that can differ
     * between JVM runs, and a sweep has to enumerate the same space twice for two runs to be diffable.
     */
    public List<RuneOption> weaponRunes(String weaponKey) {
        return runeCache.computeIfAbsent(weaponKey, key -> {
            final BaseItem item = itemRegistry.getItem(key);
            if (item == null) {
                throw new IllegalArgumentException("No registered item for weapon key " + key);
            }
            // Asked of a created instance rather than the BaseItem: SocketableGroups tests the item's
            // group and its ItemStack's material, and an instance is what a combatant will actually hold.
            final ItemInstance instance = itemFactory.create(item);
            final List<RuneOption> runes = new ArrayList<>();
            for (Socketable socketable : socketableRegistry.getAllRunes()) {
                if (socketable.canApply(instance)) {
                    runes.add(new RuneOption(socketable.getKey().toString(), socketable.getName()));
                }
            }
            runes.sort(Comparator.comparing(RuneOption::key));
            return List.copyOf(runes);
        });
    }

    /**
     * Resolves a rune key back to the live {@code Socketable} singleton.
     *
     * <p>The singleton, not a copy: {@code SocketableContainerComponent.hasRune} is a list
     * {@code contains}, and the handlers pass their own injected instance to it.
     */
    private Socketable rune(String runeKey) {
        final NamespacedKey key = NamespacedKey.fromString(runeKey);
        if (key == null) {
            throw new IllegalArgumentException("Malformed rune key " + runeKey);
        }
        return socketableRegistry.getRune(key).orElseThrow(() ->
                new IllegalArgumentException("No registered rune for key " + runeKey));
    }

    /**
     * The role's armour set, one piece per slot in helmet-to-boots order, or an empty list for
     * {@link #NO_ARMOR}.
     *
     * <p>Where several registered items would fit a slot for the same role, the lowest key wins.
     * That is arbitrary but stable, which is what matters: the same sweep must produce the same
     * set on every run for {@code target_hp} to be comparable across them.
     */
    public List<ItemStack> armorStacks(Role role, String armorSetId) {
        final SimStatRoll roll = armorRollOf(armorSetId);
        if (roll == null) {
            return List.of();
        }
        // Fresh stacks every call: these are equipped onto a combatant, and handing two duels the
        // same instance would let one duel's durability or socket state follow the other's.
        final List<ItemStack> stacks = new ArrayList<>(ARMOR_SLOTS.size());
        for (BaseItem item : armorSet(role)) {
            stacks.add(rolled(itemFactory.create(item), roll).createItemStack());
        }
        return stacks;
    }

    /**
     * The armour set id a target wearing its role's set at {@code roll} is recorded under.
     *
     * <p>The roll is folded into the id rather than carried as a separate column because
     * {@code armorSetId} is already a free-form discriminator -- {@code "none"} against
     * {@code "role_set"} -- and it is already the thing written to {@code sim_result.target_armor}
     * and read by every dashboard that groups by armour. A new column would have to be joined in
     * everywhere that string is already understood, and a stored row taken before this axis existed
     * still reads correctly as the base roll it was.
     */
    public static String armorSetId(SimStatRoll roll) {
        return roll == SimStatRoll.DEFAULT ? ROLE_ARMOR : ROLE_ARMOR + "_" + roll.id();
    }

    /**
     * The roll an armour set id names, or null when it names no armour at all.
     *
     * <p>An unrecognised id is {@code null} -- no armour -- which is what {@link #armorStacks} did
     * with anything that was not {@code "role_set"} before the roll axis existed.
     */
    @Nullable
    public static SimStatRoll armorRollOf(String armorSetId) {
        if (ROLE_ARMOR.equals(armorSetId)) {
            return SimStatRoll.DEFAULT;
        }
        for (SimStatRoll roll : SimStatRoll.values()) {
            if (armorSetId(roll).equals(armorSetId)) {
                return roll;
            }
        }
        return null;
    }

    /**
     * The registered armour pieces for a role, one per slot, in helmet-to-boots order.
     *
     * <p>Where several registered items would fit a slot for the same role, the lowest key wins.
     * That is arbitrary but stable, which is what matters: the same sweep must produce the same
     * set on every run for {@code target_hp} to be comparable across them.
     */
    private List<BaseItem> armorSet(Role role) {
        final List<BaseItem> cached = armorCache.get(role);
        if (cached != null) {
            return cached;
        }

        final Map<EquipmentSlot, NamespacedKey> chosenKeys = new EnumMap<>(EquipmentSlot.class);
        final Map<EquipmentSlot, BaseItem> chosen = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<NamespacedKey, BaseItem> entry : itemRegistry.getItems().entrySet()) {
            final BaseItem item = entry.getValue();
            if (!(item instanceof ArmorItem)) {
                continue;
            }
            final boolean forRole = item.getComponent(RoleArmorComponent.class)
                    .map(component -> component.getRoles().contains(role))
                    .orElse(false);
            if (!forRole) {
                continue;
            }

            final EquipmentSlot slot = item.getModel().getType().getEquipmentSlot();
            if (!ARMOR_SLOTS.contains(slot)) {
                continue;
            }
            final NamespacedKey existing = chosenKeys.get(slot);
            if (existing == null || entry.getKey().toString().compareTo(existing.toString()) < 0) {
                chosenKeys.put(slot, entry.getKey());
                chosen.put(slot, item);
            }
        }

        final List<BaseItem> set = new ArrayList<>(ARMOR_SLOTS.size());
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            final BaseItem item = chosen.get(slot);
            if (item != null) {
                set.add(item);
            }
        }
        if (set.isEmpty()) {
            log.warn("No registered armour set for role {}; targets will be measured bare", role).submit();
        }
        armorCache.put(role, List.copyOf(set));
        return armorCache.get(role);
    }

    /**
     * A target's durability: role base health plus the summed {@code StatTypes.HEALTH} of its
     * armour.
     *
     * <p>Armour is effective HP, not a mitigation multiplier -- {@code ArmorItem} contributes a
     * HEALTH stat and nothing in the codebase ever registers a {@code ModifierType.ARMOR} damage
     * modifier (design open question 1). The sum is delegated to {@code EntityHealthService}, the
     * same code the game uses to compute a live entity's max health, so the number on the row and
     * the number the entity fights with come from one place.
     */
    public double durability(Role role, String armorSetId) {
        final List<ItemStack> armor = armorStacks(role, armorSetId);
        if (armor.isEmpty()) {
            return role.getHealth();
        }
        return role.getHealth() + entityHealthService.getHealth(armor.toArray(new ItemStack[0]));
    }

    /**
     * The skill slot a weapon counts as when held, or null if it is not one the skill system
     * recognises.
     *
     * <p>Asked of {@code SkillWeapons} on a real factory-created stack rather than derived from
     * the key here, because that is the accessor {@code Skill.getLevel} itself consults: a booster
     * only raises a skill's effective level when {@code isHolding(player, skill.getType())} is also
     * true, so a booster axe does nothing for a sword skill. Enumerating a booster variant for a
     * slot the weapon cannot serve would produce a build that is identical to the non-booster one
     * yet recorded as distinct.
     */
    @Nullable
    public SkillType skillTypeOf(String weaponKey) {
        // Not computeIfAbsent: "this weapon serves no skill slot" is a null answer worth caching,
        // and computeIfAbsent would re-materialise the ItemStack on every miss.
        if (!skillTypeCache.containsKey(weaponKey)) {
            skillTypeCache.put(weaponKey, SkillWeapons.getTypeFrom(weaponStack(weaponKey)));
        }
        return skillTypeCache.get(weaponKey);
    }

    /** The equipment slot a piece belongs in, or null if it is not armour. */
    @Nullable
    public static EquipmentSlot slotOf(ItemStack stack) {
        final EquipmentSlot slot = stack.getType().getEquipmentSlot();
        return ARMOR_SLOTS.contains(slot) ? slot : null;
    }
}
