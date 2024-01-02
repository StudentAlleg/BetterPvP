package me.mykindos.betterpvp.champions.stats.impl;

import com.google.inject.Inject;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.framework.manager.Manager;
import org.jetbrains.annotations.Nullable;

public class ChampionsFilterManager extends Manager<ChampionsFilter> {
    @Inject
    RoleManager roleManager;


    public void load() {
        //Non role constants
        addObject("GLOBAL", new ChampionsFilter("GLOBAL", null));
        addObject("NONE", new ChampionsFilter("NONE", null));
        addObject("No Role", new ChampionsFilter("No Role", null));
        roleManager.getRoles().forEach(role -> addObject(role.getName(), ChampionsFilter.fromRole(role)));
    }

    public ChampionsFilter getFromRole(@Nullable Role role) {
        if (role == null) {
            return getObject("No Role").orElseThrow();
        }
        return getObject(role.getName()).orElseThrow();
    }

    public boolean isGlobal(ChampionsFilter filter) {
        return getObject("GLOBAL").orElseThrow() == filter;
    }

    public boolean isNone(ChampionsFilter filter) {
        return getObject("NONE").orElseThrow() == filter;
    }
}
