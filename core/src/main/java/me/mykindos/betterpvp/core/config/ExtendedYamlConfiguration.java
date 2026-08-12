package me.mykindos.betterpvp.core.config;

import lombok.CustomLog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@CustomLog
public class ExtendedYamlConfiguration extends YamlConfiguration {

    @NotNull
    public static ExtendedYamlConfiguration loadConfiguration(@NotNull File file) {
        ExtendedYamlConfiguration config = new ExtendedYamlConfiguration();

        try {
            config.load(file);
        } catch (FileNotFoundException ignored) {
            log.error("Could not find config file {}", file);
        } catch (IOException | InvalidConfigurationException ex) {
            log.error("Cannot load " + file, ex);
        }

        return config;
    }

    @Override
    @Nullable
    public ConfigurationSection getConfigurationSection(@NotNull String path) {
        Object val = get(path);
        return (val instanceof ConfigurationSection) ? (ConfigurationSection) val : null;
    }

    public String getOrSaveString(@NotNull String path, String defaultValue) {
        if (isSet(path)) {
            return getString(path);
        } else {
            set(path, defaultValue);
            return defaultValue;
        }
    }

    public int getOrSaveInt(@NotNull String path, int defaultValue) {
        if (isSet(path)) {
            return getInt(path);
        } else {
            set(path, defaultValue);
            return defaultValue;
        }
    }

    public boolean getOrSaveBoolean(@NotNull String path, boolean defaultValue) {
        if (isSet(path)) {
            return getBoolean(path);
        } else {
            set(path, defaultValue);
            return defaultValue;
        }
    }

    public List<Float> getOrSaveFloatList(@NotNull String path, @NotNull List<Float> defaultValue) {
        if (isSet(path)) {
            return getFloatList(path);
        } else {
            set(path, defaultValue);
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    @NotNull
    public <T> T getOrSaveObject(@NotNull String path, @NotNull Object defaultValue, Class<T> type) {
        if (!isSet(path)) {
            set(path, defaultValue);
        }

        if (type == List.class) {
            // A list config accepts BOTH a YAML sequence and a comma-separated scalar, and means
            // the same thing by either. The sequence is the natural form for a long list and is
            // what tooling emits; the scalar is what every @Config(defaultValue = "A,B,C") on a
            // List field writes when the key is absent, so without this branch a defaulted list
            // config reads back null and requireNonNull throws.
            //
            // It also fixes a silent failure that is much worse than a crash. Bukkit's getString
            // returns null for a sequence, so a String-typed field pointed at a YAML list resolves
            // to null rather than to the list -- and a caller that treats null as "unset" then runs
            // as though the operator had configured nothing at all.
            final List<?> list = getList(path);
            if (list != null) {
                return (T) list;
            }
            final String scalar = getString(path);
            final String raw = scalar != null ? scalar : String.valueOf(defaultValue);
            if (raw.isBlank()) {
                return (T) new ArrayList<String>();
            }
            final List<String> split = new ArrayList<>();
            for (String part : raw.split(",")) {
                final String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    split.add(trimmed);
                }
            }
            return (T) split;
        }

        if (type == Double.class) {
            return (T) Double.valueOf(getDouble(path, defaultValue instanceof String string ? Double.valueOf(string) : ((Double) defaultValue)));
        }

        if (type == Integer.class) {
            return (T) Integer.valueOf(getInt(path, defaultValue instanceof String string ? Integer.valueOf(string) : ((Integer) defaultValue)));
        }

        if (type == Byte.class) {
            return (T) Byte.valueOf((byte) getInt(path, defaultValue instanceof String string ? Byte.valueOf(string) : ((Byte) defaultValue)));
        }

        if (type == Float.class) {
            return (T) Float.valueOf(Double.valueOf(getDouble(path, defaultValue instanceof String string ? Double.parseDouble(string) : ((Float) defaultValue).doubleValue())).floatValue());
        }

        if (type == Long.class) {
            return (T) Long.valueOf(getLong(path, defaultValue instanceof String string ? Long.valueOf(string) : ((Long) defaultValue)));
        }

        if (type == Short.class) {
            return (T) Short.valueOf((short) getInt(path, defaultValue instanceof String string ? Short.valueOf(string) : ((Short) defaultValue)));
        }

        var result = getObject(path, type);
        return result == null ? (T) defaultValue : result;
    }

    public ConfigurationSection getOrCreateSection(String path) {
        ConfigurationSection section = getConfigurationSection(path);
        if (section == null) {
            section = createSection(path);
        }
        return section;
    }

}
