package me.mykindos.betterpvp.clans.commands;

import com.google.inject.Inject;
import lombok.CustomLog;
import me.mykindos.betterpvp.clans.Clans;
import me.mykindos.betterpvp.core.command.Command;
import me.mykindos.betterpvp.core.command.CommandManager;
import me.mykindos.betterpvp.core.command.SubCommand;
import me.mykindos.betterpvp.core.command.loader.CommandLoader;
import org.reflections.Reflections;

import java.util.Set;

@CustomLog
public class ClansCommandLoader extends CommandLoader {


    @Inject
    public ClansCommandLoader(Clans plugin, CommandManager commandManager) {
        super(plugin, commandManager);
    }

    public void loadCommands(String packageName) {

        final Reflections reflections = new Reflections(packageName);

        final Set<Class<? extends Command>> classes = reflections.getSubTypesOf(Command.class);
        loadAll(classes);

        final Set<Class<?>> subCommandClasses = reflections.getTypesAnnotatedWith(SubCommand.class);
        loadSubCommands(subCommandClasses);

        plugin.saveConfig();
        log.info("Loaded {} commands for Clans", count).submit();
    }

}
