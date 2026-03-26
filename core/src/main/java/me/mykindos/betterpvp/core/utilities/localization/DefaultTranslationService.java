package me.mykindos.betterpvp.core.utilities.localization;

import com.google.inject.Singleton;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class DefaultTranslationService implements ITranslationService {

    private final Map<String, Map<String, String>> translations = new ConcurrentHashMap<>();

    @Override
    public @NotNull Locale resolveLocale(Audience audience) {
        if (audience instanceof Player player) {
            return player.locale();
        }

        return LocalizationSettings.DEFAULT_LOCALE;
    }

    @Override
    public @NotNull String resolve(@NotNull Locale locale, @NotNull String key) {
        final String normalizedLocale = normalizeLangCode(locale);

        final Map<String, String> localizedTranslations = translations.get(normalizedLocale);
        if (localizedTranslations != null) {
            final String localized = localizedTranslations.get(key);
            if (localized != null) {
                return localized;
            }
        }

        final Map<String, String> fallbackTranslations = translations.get(LocalizationSettings.DEFAULT_LANGUAGE);
        if (fallbackTranslations == null) {
            return key;
        }

        return fallbackTranslations.getOrDefault(key, key);
    }

    @Override
    public void addTranslations(@NotNull Locale locale, @NotNull Map<String, String> translations) {
        this.translations.computeIfAbsent(normalizeLangCode(locale), ignored -> new ConcurrentHashMap<>())
                .putAll(translations);
    }

    @Override
    public @NotNull Locale getDefaultLocale() {
        return LocalizationSettings.DEFAULT_LOCALE;
    }

    private @NotNull String normalizeLangCode(@NotNull Locale locale) {
        return LocalizationSettings.toLanguageCode(locale);
    }
}

