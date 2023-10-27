package me.mykindos.betterpvp.champions.champions.roles;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.core.components.champions.ISkill;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.config.ExtendedYamlConfiguration;
import me.mykindos.betterpvp.core.framework.manager.Manager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.reflections.Reflections;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Singleton
public class RoleManager extends Manager<Role> {

    @Getter
    private final RoleRepository repository;

    private final Champions champions;

    @Inject
    public RoleManager(RoleRepository repository, Champions champions) {
        this.repository = repository;
        this.champions = champions;
    }

    /**
     * Check if a player has a specific role equipped
     * @param player The player
     * @param role The role
     * @return True if the player has the target role equipped
     */
    public boolean hasRole(Player player, String role){
        return Objects.equals(objects.getOrDefault(player.getUniqueId().toString(), null).getName(), role);
    }

    public Role getRole(Player player) {
        return objects.getOrDefault(player.getUniqueId().toString(), null);
    }

    public Optional<Role> getRole(String name) {
        return repository.getRoles().stream().filter(o -> o.getName().equalsIgnoreCase(name)).findFirst();
    }

    public Set<Role> getRoles() {
        return repository.getRoles();
    }
    /**
     * Check if a player has any role equipped
     * @param player The player
     * @return True if the player has a role
     */
    public boolean hasRole(Player player) {
        return objects.containsKey(player.getUniqueId().toString());
    }

    public void loadRoles() {
        ExtendedYamlConfiguration config = champions.getConfig();
        String path = "class";
        ConfigurationSection customRoleSection = config.getConfigurationSection(path);
        if (customRoleSection == null) {
            customRoleSection = config.createSection(path);
        }
        champions.saveConfig();

        Reflections roleScan = new Reflections(Champions.class.getPackageName());
        Set<Class<? extends ISkill>> skillClasses = roleScan.getSubTypesOf(ISkill.class);
        skillClasses.removeIf(clazz -> clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers()) || clazz.isEnum());
        skillClasses.removeIf(clazz -> clazz.isAnnotationPresent(Deprecated.class));

        for (String key : customRoleSection.getKeys(false)) {
            //final ConfigurationSection section = customRoleSection.getConfigurationSection(key);
            final Role loaded = new Role(key);
            loaded.loadConfig(config);
            champions.saveConfig();
            if (loaded.isEnabled()) {
                loaded.loadSkills(config, skillClasses, champions);
                repository.getRoles().add(loaded);
            }
        }
    }

}
