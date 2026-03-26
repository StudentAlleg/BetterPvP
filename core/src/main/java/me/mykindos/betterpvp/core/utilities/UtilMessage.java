package me.mykindos.betterpvp.core.utilities;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import me.mykindos.betterpvp.core.client.Rank;
import me.mykindos.betterpvp.core.utilities.localization.ITranslationService;
import me.mykindos.betterpvp.core.utilities.localization.LocalizationSettings;
import me.mykindos.betterpvp.core.utilities.model.tag.CoinsTag;
import me.mykindos.betterpvp.core.utilities.model.tag.DamageTag;
import me.mykindos.betterpvp.core.utilities.model.tag.ExperienceTag;
import me.mykindos.betterpvp.core.utilities.model.tag.HealthTag;
import me.mykindos.betterpvp.core.utilities.model.tag.ManaTag;
import me.mykindos.betterpvp.core.utilities.model.tag.ResistanceTag;
import me.mykindos.betterpvp.core.utilities.model.tag.TimeTag;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UtilMessage {

    private static final Pattern NAMED_PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_.-]+)}");

    private static final ITranslationService FALLBACK_TRANSLATION_SERVICE = new ITranslationService() {
        @Override
        public Locale resolveLocale(Audience audience) {
            return LocalizationSettings.DEFAULT_LOCALE;
        }

        @Override
        public String resolve(Locale locale, String key) {
            return key;
        }

        @Override
        public void addTranslations(Locale locale, Map<String, String> translations) {
        }

        @Override
        public Locale getDefaultLocale() {
            return LocalizationSettings.DEFAULT_LOCALE;
        }
    };

    private static ITranslationService translationService = FALLBACK_TRANSLATION_SERVICE;

    public static final TagResolver tagResolver = TagResolver.resolver(
            TagResolver.standard(),
            TagResolver.resolver("alt", Tag.styling(NamedTextColor.GREEN)),
            TagResolver.resolver("alt2", Tag.styling(NamedTextColor.YELLOW)),
            TagResolver.resolver("orange", Tag.styling(TextColor.color(0xFFA500))),
            TagResolver.resolver("val", Tag.styling(NamedTextColor.GREEN)),
            TagResolver.resolver("effect", Tag.styling(NamedTextColor.WHITE)),
            TagResolver.resolver("stat", Tag.styling(NamedTextColor.YELLOW)),
            TagResolver.resolver("coins", new CoinsTag()),
            TagResolver.resolver("damage", new DamageTag()),
            TagResolver.resolver("health", new HealthTag()),
            TagResolver.resolver("exp", new ExperienceTag()),
            TagResolver.resolver("mana", new ManaTag()),
            TagResolver.resolver("resistance", new ResistanceTag()),
            TagResolver.resolver("time", new TimeTag())
    );

    public static final MiniMessage miniMessage = MiniMessage.builder().tags(tagResolver).build();

    public static final TextComponent DIVIDER = Component.text("                                            ")
            .color(NamedTextColor.DARK_GRAY)
            .decorate(TextDecoration.STRIKETHROUGH);


    public static final Component StudioPrefix = Component.empty().append(Component.text("BPvP", NamedTextColor.RED));

    /**
     * Sends a message to a player with appropriate formatting
     *
     * @param sender  The player
     * @param prefix  The message
     * @param message Message to send to a player
     */
    public static void message(Audience sender, String prefix, Component message) {
        sender.sendMessage(getPrefix(prefix).append(normalize(message)));
    }

    /**
     * Sends a message to a CommandSender with appropriate formatting
     * Can also send to players
     *
     * @param sender  The CommandSender
     * @param prefix  The message
     * @param message Message to send to the CommandSender
     */
    public static void message(Audience sender, String prefix, String message) {
        message(sender, prefix, miniMessage.deserialize(message, tagResolver));
    }

    /**
     * Sends a message to a CommandSender with appropriate formatting
     * Can also send to players
     *
     * @param sender  The CommandSender
     * @param prefix  The message
     * @param message Message to send to the CommandSender
     * @param args    The args to interpolate in the string
     */
    public static void message(Audience sender, String prefix, String message, Object... args) {
        message(sender, prefix, String.format(message, args));
    }

    /**
     * Sends a message to a player with appropriate formatting
     * Additionally plays a sound to the player when they receive this message
     *
     * @param player  The player
     * @param prefix  The message
     * @param message Message to send to a player
     * @param sound   Whether or not to send a sound to the player as well
     */
    public static void message(Player player, String prefix, String message, boolean sound) {
        if (sound) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.0F);
        }

        message(player, prefix, message);
    }

    /**
     * Sends a message to a player, does not format the message
     *
     * @param player  The player receiving the message
     * @param message The message to be sent
     */
    public static void message(Audience player, String message) {
        player.sendMessage(Component.text(message));
    }

    /**
     * Sends a message to a player, does not format the message
     *
     * @param player  The player receiving the message
     * @param message The message to be sent
     */
    public static void message(Audience player, Component message) {
        player.sendMessage(message);
    }


    /**
     * Sends a message to a player, adds the required rank at the end of the message
     *
     * @param player  The player receiving the message
     * @param command The command being executed
     * @param message The message to be sent
     * @param rank    The rank required to use this command
     */
    public static void message(Audience player, String command, String message, Rank rank) {
        final TextComponent prefixCmpt = Component.text(command, rank.getColor());
        final TextComponent messageCmpt = Component.text(message, NamedTextColor.GRAY);
        final Component rankCmpt = rank.getTag(Rank.ShowTag.LONG, false);
        player.sendMessage(Component.join(JoinConfiguration.separator(Component.space()), prefixCmpt, messageCmpt, rankCmpt));
    }

    /**
     * Sends an array of strings to a player, does not format the strings
     *
     * @param player  The player receiving the message
     * @param message The strings to be sent
     */
    public static void message(Audience player, String[] message) {
        for (String string : message) {
            message(player, string);
        }
    }

    /**
     * Sends an array of strings to a player, does not format the strings
     *
     * @param player  The player receiving the message
     * @param message The strings to be sent
     */
    public static void message(Audience player, Component[] message) {
        for (Component string : message) {
            player.sendMessage(string);
        }
    }

    /**
     * Sends an array of strings to a player with appropriate formatting
     *
     * @param player  The player
     * @param prefix  The message
     * @param message Strings to send to a player
     */
    public static void message(Audience player, String prefix, String[] message) {
        for (String string : message) {
            message(player, prefix, string);
        }
    }

    /**
     * Sends a message utilizing <a href="https://docs.adventure.kyori.net/minimessage">MiniMessage</a> from Adventure API
     *
     * @param sender  The CommandSender to send the message to
     * @param message The message to send
     */
    public static void simpleMessage(Audience sender, String message) {
        sender.sendMessage(deserialize(message));
    }

    /**
     * Sends a message utilizing <a href="https://docs.adventure.kyori.net/minimessage">MiniMessage</a> from Adventure API
     *
     * @param sender  The CommandSender
     * @param prefix  The PREFIX
     * @param message Message to send to the CommandSender
     */
    public static void simpleMessage(Audience sender, String prefix, String message) {
        sender.sendMessage(getPrefix(prefix).append(deserialize(message)));
    }

    /**
     * Sends a message utilizing <a href="https://docs.adventure.kyori.net/minimessage">MiniMessage</a> from Adventure API
     *
     * @param sender  The CommandSender to send the message to
     * @param prefix  The message
     * @param message Message to send to the CommandSender
     * @param hover   Hover event to add to the message
     */
    public static void simpleMessage(Audience sender, String prefix, String message, Component hover) {
        simpleMessage(sender, prefix, deserialize(message), hover);
    }

    /**
     * Sends a message utilizing <a href="https://docs.adventure.kyori.net/minimessage">MiniMessage</a> from Adventure API
     *
     * @param sender  The CommandSender to send the message to
     * @param prefix  The message
     * @param message Message to send to the CommandSender
     * @param hover   Hover event to add to the message
     */
    public static void simpleMessage(Audience sender, String prefix, Component message, Component hover) {
        sender.sendMessage(getPrefix(prefix).hoverEvent(HoverEvent.showText(hover)).append(normalize(message)));
    }

    /**
     * Sends a message utilizing <a href="https://docs.adventure.kyori.net/minimessage">MiniMessage</a> from Adventure API
     *
     * @param sender  The CommandSender
     * @param prefix  The message
     * @param message Message to send to the CommandSender
     * @param args    The args to interpolate in the string
     */
    public static void simpleMessage(Audience sender, String prefix, String message, Object... args) {
        simpleMessage(sender, prefix, String.format(message, args));
    }

    /**
     * Sends a message utilizing <a href="https://docs.adventure.kyori.net/minimessage">MiniMessage</a> from Adventure API
     *
     * @param sender    The CommandSender
     * @param prefix    The message
     * @param component Message to send to the CommandSender
     */
    public static void simpleMessage(Audience sender, String prefix, Component component) {
        sender.sendMessage(getPrefix(prefix).append(normalize(component)));
    }

    /**
     * Sends a message utilizing <a href="https://docs.adventure.kyori.net/minimessage">MiniMessage</a> from Adventure API
     *
     * @param sender  The CommandSender
     * @param message Message to send to the CommandSender
     * @param args    The args to interpolate in the string
     */
    public static void simpleMessage(Audience sender, String message, Object... args) {
        sender.sendMessage(deserialize(String.format(message, args)));
    }


    public static void simpleBroadcast(String prefix, String message, Object... args) {
        Bukkit.getServer().broadcast(getPrefix(prefix).append(deserialize(String.format(message, args))));
    }

    public static void simpleBroadcast(String prefix, String message, Component hover) {
        Bukkit.getServer().broadcast(getPrefix(prefix).append(deserialize(message)).hoverEvent(HoverEvent.showText(hover)));
    }

    public static Component getMiniMessage(String message, Object... args) {
        return deserialize(String.format(message, args)).decoration(TextDecoration.ITALIC, false);
    }

    public static Component getMiniMessage(String message) {
        return deserialize(message).decoration(TextDecoration.ITALIC, false);
    }

    public static Component deserialize(String message) {
        String msg = message;
        if(msg.contains(String.valueOf(UtilFormat.COLOR_CHAR))) {
            msg = UtilFormat.stripColor(message);
        }

        return normalize(miniMessage.deserialize(msg, tagResolver));
    }

    public static Component deserialize(String message, Object... args) {
        return deserialize(String.format(message, args));
    }

    public static Component translate(Locale locale, String key, Map<String, ?> placeholders) {
        return deserialize(translateText(locale, key, placeholders));
    }

    public static Component translate(Locale locale, String key) {
        return translate(locale, key, Collections.emptyMap());
    }

    public static Component translate(Audience audience, String key, Map<String, ?> placeholders) {
        return translate(getLocale(audience), key, placeholders);
    }

    public static Component translate(Audience audience, String key) {
        return translate(audience, key, Collections.emptyMap());
    }

    public static Component translate(Player player, String key, Map<String, ?> placeholders) {
        return translate((Audience) player, key, placeholders);
    }

    public static Component translate(Player player, String key) {
        return translate((Audience) player, key);
    }

    public static String translateText(Locale locale, String key, Map<String, ?> placeholders) {
        final String localized = resolveTranslation(locale, key);
        return interpolateNamedPlaceholders(localized, placeholders);
    }

    public static String translateText(Locale locale, String key) {
        return translateText(locale, key, Collections.emptyMap());
    }

    public static String translateText(Audience audience, String key, Map<String, ?> placeholders) {
        return translateText(getLocale(audience), key, placeholders);
    }

    public static String translateText(Audience audience, String key) {
        return translateText(audience, key, Collections.emptyMap());
    }

    public static String translateDefaultText(String key, Map<String, ?> placeholders) {
        return translateText(translationService.getDefaultLocale(), key, placeholders);
    }

    public static String translateDefaultText(String key) {
        return translateDefaultText(key, Collections.emptyMap());
    }

    public static void messageKey(Audience sender, String prefix, String key) {
        message(sender, prefix, translate(sender, key));
    }

    public static void messageKey(Audience sender, String prefix, String key, Map<String, ?> placeholders) {
        message(sender, prefix, translate(sender, key, placeholders));
    }

    public static void simpleMessageKey(Audience sender, String prefix, String key) {
        simpleMessage(sender, prefix, translate(sender, key));
    }

    public static void simpleMessageKey(Audience sender, String prefix, String key, Map<String, ?> placeholders) {
        simpleMessage(sender, prefix, translate(sender, key, placeholders));
    }

    public static void simpleMessageKey(Audience sender, String key) {
        sender.sendMessage(translate(sender, key));
    }

    public static void simpleMessageKey(Audience sender, String key, Map<String, ?> placeholders) {
        sender.sendMessage(translate(sender, key, placeholders));
    }

    public static Map<String, Object> placeholders(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Named placeholders require key/value pairs");
        }

        final Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            final Object key = keyValuePairs[i];
            if (key == null) {
                throw new IllegalArgumentException("Placeholder key cannot be null");
            }

            map.put(String.valueOf(key), keyValuePairs[i + 1]);
        }

        return map;
    }

    /**
     * Creates a component with a click event that copies text to the clipboard when clicked, and a hover event that shows "Click to Copy"
     * @param commandText The text to show in the message, which will have the hover and click events
     * @return A component with a click event that copies text to the clipboard when clicked, and a hover event that shows "Click to Copy"
     */
    public static Component copyCommand(String commandText) {
        return copyCommand(commandText, commandText);
    }

    /**
     * Creates a component with a click event that copies text to the clipboard when clicked, and a hover event that shows "Click to Copy"
     * @param commandText The text to show in the message, which will have the hover and click events. This is usually the command being copied, but can be any text.
     * @param copyText The text that will be copied to the clipboard when the message is clicked. This is usually the command being copied, but can be any text.
     * @return A component with a click event that copies text to the clipboard when clicked, and a hover event that shows "Click to Copy"
     */
    public static Component copyCommand(String commandText, String copyText) {
        return UtilMessage.deserialize("<gold>" + commandText + "</gold>")
                .hoverEvent(HoverEvent.showText(Component.text("Click to Copy Command")))
                .clickEvent(ClickEvent.copyToClipboard(copyText));
    }

    /**
     * Creates a component with a click event that changes the page of a book when clicked, and a hover event that shows "Click to open page X"
     * @param entryText The text to show in the message, which will have the hover and click events. This is usually the name of the section of the book that this entry corresponds to, but can be any text.
     * @param pageNum The page number that the book will change to when the message is clicked. This is usually the page that the section of the book that this entry corresponds to starts on, but can be any page number.
     * @return A component with a click event that changes the page of a book when clicked, and a hover event that shows "Click to open page X"
     */
    public static Component tableOfContentsEntry(String entryText, int pageNum) {
        return Component.empty().append(UtilMessage.deserialize("<reset><black>" + entryText + ": " + pageNum).decoration(TextDecoration.BOLD, false)
                .hoverEvent(HoverEvent.showText(Component.text("Click to open page " + pageNum)))
                .clickEvent(ClickEvent.changePage(pageNum)));
    }

    public static Component normalize(Component component) {
        return component.applyFallbackStyle(NamedTextColor.GRAY);
    }

    public static void bindTranslationService(ITranslationService translationService) {
        UtilMessage.translationService = translationService;
    }

    private static String resolveTranslation(Locale locale, String key) {
        return translationService.resolve(locale, key);
    }

    private static String interpolateNamedPlaceholders(String template, Map<String, ?> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return template;
        }

        final Matcher matcher = NAMED_PLACEHOLDER_PATTERN.matcher(template);
        final StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            final String placeholderName = matcher.group(1);
            final Object value = placeholders.get(placeholderName);
            final String replacement = value == null ? matcher.group(0) : String.valueOf(value);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);

        return output.toString();
    }

    private static Locale getLocale(Audience sender) {
        return translationService.resolveLocale(sender);
    }

    public static Component getPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return Component.empty();
        }
        return miniMessage.deserialize("<blue>" + prefix + "> ");
    }

    /**
     * Broadcasts a message to all players on the server with formatting
     *
     * @param prefix  The PREFIX of the message
     * @param message The message to be broadcasted
     */
    public static void broadcast(String prefix, String message) {
        Bukkit.getServer().broadcast(getPrefix(prefix).append(deserialize(message)));
    }

    /**
     * Broadcasts a message to all players on the server with formatting
     *
     * @param prefix  The PREFIX of the message
     * @param message The message to be broadcasted
     * @param args    The args to interpolate in the string
     */
    public static void broadcast(String prefix, String message, Object... args) {
        Bukkit.getServer().broadcast(getPrefix(prefix).append(deserialize(message, args)));
    }

    /**
     * Broadcasts a message to all players on the server with formatting
     *
     * @param message The message to be broadcasted
     */
    public static void broadcast(String message) {
        Bukkit.getServer().broadcast(deserialize(message));
    }

    /**
     * Broadcasts a message to all players on the server with formatting
     *
     * @param message The message to be broadcasted
     */
    public static void broadcast(Component message) {
        Bukkit.getServer().broadcast(message);
    }

    /**
     * Broadcasts a pre-built component to all players on the server with a formatted prefix
     *
     * @param prefix  The PREFIX of the message
     * @param message The component to be broadcasted
     */
    public static void broadcast(String prefix, Component message) {
        Bukkit.getServer().broadcast(getPrefix(prefix).append(normalize(message)));
    }

}
