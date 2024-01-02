package me.mykindos.betterpvp.champions.champions.builds.menus;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.champions.builds.BuildManager;
import me.mykindos.betterpvp.champions.champions.builds.menus.buttons.ClassSelectionButton;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.champions.champions.skills.SkillManager;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.menu.Menu;
import me.mykindos.betterpvp.core.menu.Windowed;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.gui.AbstractGui;

import java.util.Iterator;

@Singleton
public class ClassSelectionMenu extends AbstractGui implements Windowed {

    @Inject
    public ClassSelectionMenu(BuildManager buildManager, SkillManager skillManager, RoleManager roleManager) {
        super(9, 3);

        int[] slots = new int[] {9, 10, 11, 12, 13, 14, 15, 16, 17};
        final Iterator<Role> iterator = roleManager.getRoles().iterator();
        for (int slot : slots) {
            final Role role = iterator.next();
            setItem(slot, new ClassSelectionButton(buildManager, skillManager, role, this));
        }

        setBackground(Menu.BACKGROUND_ITEM);
    }

    @NotNull
    @Override
    public Component getTitle() {
        return Component.text("Pick a Kit");
    }
}
