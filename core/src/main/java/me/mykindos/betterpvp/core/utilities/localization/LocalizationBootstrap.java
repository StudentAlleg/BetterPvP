package me.mykindos.betterpvp.core.utilities.localization;

import com.google.gson.stream.JsonReader;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.inventory.inventoryaccess.component.i18n.Languages;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@CustomLog
@Singleton
public final class LocalizationBootstrap {

    private final ITranslationService ITranslationService;

    @Inject
    public LocalizationBootstrap(ITranslationService ITranslationService) {
        this.ITranslationService = ITranslationService;
    }

    /**
     * Loads the default bundled language file from {@code lang/<lang>.json}.
     */
    public void loadDefaultBundledLanguage(@NotNull JavaPlugin plugin) {
        final String defaultLanguage = ITranslationService.getDefaultLanguage();
        loadBundledLanguage(plugin, defaultLanguage);
        Languages.getInstance().setDefaultLanguage(defaultLanguage);
    }

    /**
     * Loads a bundled language file from plugin resources.
     */
    public void loadBundledLanguage(@NotNull JavaPlugin plugin, @NotNull String languageCode) {
        final String normalizedLanguageCode = languageCode.toLowerCase(Locale.ROOT);
        final String resourcePath = "lang/" + normalizedLanguageCode + ".json";
        try (var stream = plugin.getResource(resourcePath)) {
            if (stream == null) {
                log.warn("Missing language resource '{}' for plugin {}", resourcePath, plugin.getName()).submit();
                return;
            }

            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                final Map<String, String> translations = readTranslations(reader);
                ITranslationService.addTranslations(LocalizationSettings.parseLocale(normalizedLanguageCode), translations);
                Languages.getInstance().addLanguage(normalizedLanguageCode, translations);
            }
        } catch (IOException e) {
            log.error("Failed to load language '{}' for plugin {}", languageCode, plugin.getName(), e);
        }
    }

    private Map<String, String> readTranslations(InputStreamReader reader) throws IOException {
        final Map<String, String> map = new HashMap<>();
        try (var jsonReader = new JsonReader(reader)) {
            jsonReader.beginObject();
            while (jsonReader.hasNext()) {
                map.put(jsonReader.nextName(), jsonReader.nextString());
            }
        }
        return map;
    }
}



