package me.mykindos.betterpvp.champions.stats.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import me.mykindos.betterpvp.champions.stats.repository.ChampionsCombatData;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.stats.filter.FilterType;
import org.jetbrains.annotations.Nullable;

@Getter
@AllArgsConstructor
public class ChampionsFilter implements FilterType {

    private final String name;
    private final @Nullable Role role;

    public static ChampionsFilter fromRole(Role role) {
        return new ChampionsFilter(role == null ? "No Role" : role.getName(), role);
    }

    public static ChampionsFilter getNone() {
        return new ChampionsFilter("NONE", null);
    }

    public static ChampionsFilter getGlobal() {
        return new ChampionsFilter("GLOBAL", null);
    }

    public static Boolean isGlobal(ChampionsFilter filter) {
        ChampionsFilter global = getGlobal();
        return filter.getName().equals(global.getName()) && filter.getRole() == global.getRole();
    }

    public static String[] uniqueNames() {
        return new String[]{"GLOBAL", "NONE", "No Role"};
    }

    @Override
    public boolean accepts(Object entry) {
        return getName() != "GLOBAL" && entry instanceof ChampionsCombatData data && data.getRole() == role;
    }
}
