package me.mykindos.betterpvp.core.utilities.localization;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LocalizationSettings {

    public static final Locale DEFAULT_LOCALE = Locale.US;
    public static final String DEFAULT_LANGUAGE = toLanguageCode(DEFAULT_LOCALE);

    public static @NotNull Locale parseLocale(@NotNull String languageCode) {
        final Locale locale = Locale.forLanguageTag(languageCode.replace('_', '-'));
        if (locale.getLanguage().isBlank()) {
            return DEFAULT_LOCALE;
        }

        return locale;
    }

    public static @NotNull String toLanguageCode(@NotNull Locale locale) {
        final String language = locale.getLanguage();
        if (language == null || language.isBlank()) {
            return DEFAULT_LANGUAGE;
        }

        final StringBuilder languageCode = new StringBuilder(language.toLowerCase(Locale.ROOT));
        if (!locale.getCountry().isBlank()) {
            languageCode.append('_').append(locale.getCountry().toLowerCase(Locale.ROOT));
        }
        if (!locale.getVariant().isBlank()) {
            languageCode.append('_').append(locale.getVariant().toLowerCase(Locale.ROOT));
        }

        return languageCode.toString();
    }
}

