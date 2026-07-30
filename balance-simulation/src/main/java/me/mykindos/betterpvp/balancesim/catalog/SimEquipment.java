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
import me.mykindos.betterpvp.core.item.model.ArmorItem;
import me.mykindos.betterpvp.core.item.model.WeaponItem;
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
import java.util.List;
import java.util.Map;

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

    private static final List<EquipmentSlot> ARMOR_SLOTS =
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
    private final Map<String, SkillType> skillTypeCache = new HashMap<>();
    private final Map<Role, List<BaseItem>> armorCache = new EnumMap<>(Role.class);
    private final Map<String, List<RuneOption>> runeCache = new HashMap<>();

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

    @Inject
    public SimEquipment(ItemRegistry itemRegistry, ItemFactory itemFactory, EntityHealthService entityHealthService) {
        this.itemRegistry = itemRegistry;
        this.itemFactory = itemFactory;
        this.entityHealthService = entityHealthService;
        this.socketableRegistry = JavaPlugin.getPlugin(Core.class).getInjector()
                .getInstance(SocketableRegistry.class);
    }

    /**
     * Drops the cached axes so the next sweep reads the registry afresh. Called once when a run
     * starts, which is the only point at which a config reload between runs could have changed
     * what is registered.
     */
    public void invalidate() {
        meleeWeaponCache = null;
        defaultWeaponCache = null;
        skillTypeCache.clear();
        armorCache.clear();
        runeCache.clear();
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
     * Materialises a weapon for equipping. Goes through {@code ItemFactory.create} rather than a
     * bare {@code ItemStack}, because the stat handlers and {@code SkillWeapons.getTypeFrom} both
     * read the custom-item key out of the PDC, and only a factory-created stack carries it.
     *
     * @throws IllegalArgumentException if the key is not registered
     */
    public ItemStack weaponStack(String key) {
        return weaponStack(key, List.of());
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
     * @param runeKeys rune keys as written to {@code sim_build.runes}, in catalog order
     * @throws IllegalArgumentException if the weapon key or any rune key is not registered
     */
    public ItemStack weaponStack(String key, List<String> runeKeys) {
        final BaseItem item = itemRegistry.getItem(key);
        if (item == null) {
            throw new IllegalArgumentException("No registered item for weapon key " + key);
        }
        ItemInstance instance = itemFactory.create(item);
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

    /**
     * One rune of the sweep's rune axis.
     *
     * @param key    the rune's {@code NamespacedKey}, as written to {@code sim_build.runes}
     * @param name   the rune's stable identity string, only used in logs
     */
    public record RuneOption(String key, String name) {
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
        if (!ROLE_ARMOR.equals(armorSetId)) {
            return List.of();
        }
        // Fresh stacks every call: these are equipped onto a combatant, and handing two duels the
        // same instance would let one duel's durability or socket state follow the other's.
        final List<ItemStack> stacks = new ArrayList<>(ARMOR_SLOTS.size());
        for (BaseItem item : armorSet(role)) {
            stacks.add(itemFactory.create(item).createItemStack());
        }
        return stacks;
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
