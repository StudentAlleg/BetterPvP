package me.mykindos.betterpvp.core.client.gamer;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.chat.channels.ChatChannel;
import me.mykindos.betterpvp.core.chat.channels.IChatChannel;
import me.mykindos.betterpvp.core.chat.channels.ServerChatChannel;
import me.mykindos.betterpvp.core.chat.channels.events.PlayerChangeChatChannelEvent;
import me.mykindos.betterpvp.core.client.gamer.properties.GamerProperty;
import me.mykindos.betterpvp.core.client.gamer.properties.GamerPropertyUpdateEvent;
import me.mykindos.betterpvp.core.combat.damagelog.DamageLog;
import me.mykindos.betterpvp.core.combat.offhand.OffhandExecutor;
import me.mykindos.betterpvp.core.framework.customtypes.IMapListener;
import me.mykindos.betterpvp.core.framework.inviting.Invitable;
import me.mykindos.betterpvp.core.framework.sidebar.Sidebar;
import me.mykindos.betterpvp.core.properties.PropertyContainer;
import me.mykindos.betterpvp.core.utilities.UtilItem;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import me.mykindos.betterpvp.core.utilities.UtilTime;
import me.mykindos.betterpvp.core.utilities.model.Unique;
import me.mykindos.betterpvp.core.utilities.model.display.actionbar.ActionBar;
import me.mykindos.betterpvp.core.utilities.model.display.actionbar.ActionBarOverrides;
import me.mykindos.betterpvp.core.utilities.model.display.bossbar.BossBarOverlay;
import me.mykindos.betterpvp.core.utilities.model.display.bossbar.BossBarQueue;
import me.mykindos.betterpvp.core.utilities.model.display.experience.ExperienceBar;
import me.mykindos.betterpvp.core.utilities.model.display.experience.ExperienceLevel;
import me.mykindos.betterpvp.core.utilities.model.display.playerlist.PlayerList;
import me.mykindos.betterpvp.core.utilities.model.display.title.TitleQueue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A gamer represents a clients seasonal data.
 * Such as their blocks broken, their kills, deaths, etc.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = false, of = {"uuid"})
public class Gamer extends PropertyContainer implements Invitable, Unique, IMapListener {

    private static final long MILLIS_PER_TICK = 50L;

    /** Sentinel for "has never been damaged", chosen so no current tick can read as recent. */
    private static final int NEVER_DAMAGED = Integer.MIN_VALUE;

    /** The combat tag window, {@link DamageLog#EXPIRY} expressed in server ticks. */
    private static final long COMBAT_TICKS = DamageLog.EXPIRY / MILLIS_PER_TICK;

    private final long id;
    private final String uuid;
    private ActionBar actionBar = new ActionBar();
    private final ActionBarOverrides actionBarOverrides = new ActionBarOverrides();
    private TitleQueue titleQueue = new TitleQueue();
    private PlayerList playerList = new PlayerList();
    private ExperienceBar experienceBar = new ExperienceBar();
    private ExperienceLevel experienceLevel = new ExperienceLevel();
    private BossBarQueue bossBarQueue = new BossBarQueue();
    private BossBarOverlay bossBarOverlay = new BossBarOverlay();
    private Sidebar sidebar = null;
    private @NotNull IChatChannel chatChannel = ServerChatChannel.getInstance();
    private final NavigableMap<Integer, OffhandExecutor> offhandExecutors = new TreeMap<>(Comparator.reverseOrder());

    /**
     * The server tick this gamer was last damaged on, or {@link #NEVER_DAMAGED}.
     *
     * <p>Ticks rather than wall clock, for the reason {@code DelayData} documents for damage
     * delays: combat only advances while a tick is being processed, so "N milliseconds since the
     * last hit" really means "however many ticks happened to fit in N milliseconds", and that count
     * moves with server load. Every out-of-combat mechanic keyed off this -- Tranquility's regen,
     * Swordsmanship's and Deflection's charge accrual -- would otherwise come back sooner, in game
     * time, on a struggling server than on a healthy one. Ticks make the window exact.
     *
     * <p>Not a wall-clock epoch any more, so it is deliberately not readable as one: callers ask
     * {@link #isInCombat()} or {@link #hasBeenOutOfCombatFor(long)} rather than doing arithmetic on
     * the stamp.
     */
    private int lastDamagedTick = NEVER_DAMAGED;
    private long lastDeath = -1;
    private long lastSafe = -1;
    private long lastTip = -1;
    private long lastBlock = -1;
    private long lastMovement = -1;
    private double lastDealtDamageValue = 0;
    private String lastAdminMessenger;

    public Gamer(long id, String uuid) {
        this.id = id;
        this.uuid = uuid;
        this.properties.registerListener(this);
    }

    /**
     * The action bar to render this tick: the winning override if one is active,
     * otherwise the base action bar. Systems should keep adding components via
     * {@link #getActionBar()} — the base queue is preserved while overridden.
     */
    /**
     * Registers an offhand executor in the given priority slot, replacing whatever
     * occupied it. On an offhand press, executors are consulted highest priority
     * first until one consumes the press.
     */
    public void setOffhandExecutor(int priority, OffhandExecutor executor) {
        offhandExecutors.put(priority, executor);
    }

    public void removeOffhandExecutor(int priority) {
        offhandExecutors.remove(priority);
    }

    public ActionBar getDisplayedActionBar() {
        final ActionBar override = actionBarOverrides.peek();
        return override != null ? override : actionBar;
    }

    public long timeSinceLastBlock() {
        if (lastBlock == -1) {
            return -1;
        }

        return System.currentTimeMillis() - lastBlock;
    }

    public boolean canBlock() {
        final Player player = getPlayer();
        if (player != null) {
            final ItemStack main = player.getInventory().getItemInMainHand();
            final ItemStack off = player.getInventory().getItemInOffHand();
            return main.getMaxItemUseDuration(player) > 0 || off.getMaxItemUseDuration(player) > 0;
        }

        return false;
    }

    public boolean isHoldingRightClick() {
        final Player player = getPlayer();
        if (player == null) {
            return false;
        }
        if (canBlock()) {


            // If they're holding a cosmetic shield, give them a grace period for them to raise their hand
            // Otherwise, this would return false
            final ItemStack main = player.getInventory().getItemInMainHand();
            final ItemStack off = player.getInventory().getItemInOffHand();
            if (UtilItem.isUndroppable(main) || UtilItem.isUndroppable(off)) {
                final long t = timeSinceLastBlock();
                return t >= 0 && t <= 250;
            }

            return player.isBlocking() || player.isHandRaised() || lastBlock != -1;
        }

        final long t = timeSinceLastBlock();
        return t >= 0 && t <= 250;
    }

    public @Nullable Player getPlayer() {
        return Bukkit.getPlayer(UUID.fromString(uuid));
    }

    public boolean isOnline() {
        return getPlayer() != null;
    }

    public int getBalance() {
        return getIntProperty(GamerProperty.BALANCE);
    }

    public void setSidebar(@Nullable Sidebar newSidebar) {
        if (this.sidebar != null) {
            if (!this.sidebar.closed()) {
                final Player player = getPlayer();
                if (player != null && player.isOnline()) {
                    this.sidebar.removePlayer(player);
                }
                this.sidebar.close();
            }
        }

        this.sidebar = newSidebar;
    }

    @Override
    public void saveProperty(String key, Object object) {
        properties.put(key, object);
    }

    @Override
    public void onMapValueChanged(String key, Object newValue, Object oldValue) {
        UtilServer.runTask(JavaPlugin.getPlugin(Core.class), () -> UtilServer.callEvent(new GamerPropertyUpdateEvent(this, key, newValue, oldValue)));
    }

    public void setLastTipNow() {
        setLastTip(System.currentTimeMillis());
    }

    public void setLastMovementNow() {
        setLastMovement(System.currentTimeMillis());
    }

    public boolean isMoving() {
        return !UtilTime.elapsed(getLastMovement(), 100);
    }

    public void setLastSafeNow() {
        setLastSafe(System.currentTimeMillis());
    }

    public void updateRemainingProtection() {
        long remainingProtection = getLongProperty(GamerProperty.REMAINING_PVP_PROTECTION);
        remainingProtection = remainingProtection - (System.currentTimeMillis() - getLastSafe());
        saveProperty(GamerProperty.REMAINING_PVP_PROTECTION, remainingProtection);
    }

    @Override
    public UUID getUniqueId() {
        return UUID.fromString(uuid);
    }

    /**
     * Stamps this gamer as having just been damaged.
     *
     * <p>Takes no argument: the stamp is always "now", and the representation is this class's
     * business. Callers previously passed {@code System.currentTimeMillis()}, which is the one
     * value that is now wrong.
     */
    public void markDamaged() {
        if (!UtilTime.elapsed(lastDeath, 10_000)) {
            //don't set lastDamaged if the player has recently died
            return;
        }
        this.lastDamagedTick = Bukkit.getCurrentTick();
    }

    /** Forgets any combat, so the gamer reads as never having been damaged. Used on respawn. */
    public void clearCombat() {
        this.lastDamagedTick = NEVER_DAMAGED;
    }

    /**
     * How many ticks have passed since this gamer was last damaged.
     *
     * @return the elapsed ticks, or {@link Long#MAX_VALUE} if they have never been damaged
     */
    public long getTicksSinceDamaged() {
        if (lastDamagedTick == NEVER_DAMAGED) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, (long) Bukkit.getCurrentTick() - lastDamagedTick);
    }

    /**
     * Whether this gamer has gone at least {@code ticks} server ticks without taking damage.
     *
     * @param ticks the out-of-combat window, in server ticks
     */
    public boolean hasBeenOutOfCombatFor(long ticks) {
        return getTicksSinceDamaged() >= ticks;
    }

    public boolean isInCombat() {
        return getTicksSinceDamaged() < COMBAT_TICKS;
    }

    public long getRemainingCombatMillis() {
        final long since = getTicksSinceDamaged();
        if (since >= COMBAT_TICKS) {
            return 0L;
        }

        return (COMBAT_TICKS - since) * MILLIS_PER_TICK;
    }

    public void setChatChannel(@NotNull ChatChannel chatChannel) {
        Player player = getPlayer();

        if (player != null) {
            PlayerChangeChatChannelEvent event = UtilServer.callEvent(new PlayerChangeChatChannelEvent(this, chatChannel));
            if (event.isCancelled()) {
                return;
            }

            if (event.getNewChannel() == null) {
                this.chatChannel = ServerChatChannel.getInstance();
                return;
            }

            UtilMessage.message(player, "core.prefix.chat", "core.client.gamer.channel",
                    Component.text(event.getNewChannel().getChannel().name().toLowerCase(), NamedTextColor.GREEN));
            this.chatChannel = event.getNewChannel();
        }

    }

}
