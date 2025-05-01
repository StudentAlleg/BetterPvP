package me.mykindos.betterpvp.core.client.achievements.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.achievements.types.IAchievementType;

@RequiredArgsConstructor
@Getter
public abstract class Achievement implements IAchievement {

    private final IAchievementType type;
    private final Client client;
}
