package me.mykindos.betterpvp.core.utilities.localization;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.inventory.inventoryaccess.component.i18n.Languages;

@Singleton
public class InvUILocalizationBridge {

    private final ITranslationService ITranslationService;

    @Inject
    public InvUILocalizationBridge(ITranslationService ITranslationService) {
        this.ITranslationService = ITranslationService;
    }

    public void initialize() {
        final Languages languages = Languages.getInstance();
        languages.setDefaultLanguage(ITranslationService.getDefaultLanguage());
        languages.setLanguageProvider(player -> LocalizationSettings.toLanguageCode(ITranslationService.resolveLocale(player)));
    }
}


