package me.mykindos.betterpvp.core.utilities.localization;

import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

public interface ITranslationService {

    @NotNull Locale resolveLocale(Audience audience);

    @NotNull String resolve(@NotNull Locale locale, @NotNull String key);

    void addTranslations(@NotNull Locale locale, @NotNull Map<String, String> translations);

    @NotNull Locale getDefaultLocale();

    default @NotNull String getDefaultLanguage() {
        return LocalizationSettings.toLanguageCode(getDefaultLocale());
    }
}

