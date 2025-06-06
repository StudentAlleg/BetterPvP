package me.mykindos.betterpvp.core.logging.repository;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.config.Config;
import me.mykindos.betterpvp.core.database.Database;
import me.mykindos.betterpvp.core.database.connection.TargetDatabase;
import me.mykindos.betterpvp.core.database.query.Statement;
import me.mykindos.betterpvp.core.database.query.values.StringStatementValue;
import me.mykindos.betterpvp.core.logging.CachedLog;

import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Singleton
@CustomLog
public class LogRepository {

    @Inject
    @Config(path = "tab.server", defaultValue = "Clans-1")
    private String server;

    private final Database database;

    @Inject
    public LogRepository(Database database) {
        this.database = database;
    }

    public List<CachedLog> getLogsWithContext(String key, String value) {
        return getLogsWithContextAndAction(key, value, null);
    }

    public List<CachedLog> getLogsWithContextAndAction(String key, String value, @Nullable String actionFilter) {
        List<CachedLog> logs = new ArrayList<>();
        Statement statement1;

        if (actionFilter != null) {
            statement1 = new Statement("CALL GetLogMessagesByContextAndAction(?, ?, ?, ?)",
                    new StringStatementValue(key),
                    new StringStatementValue(value),
                    new StringStatementValue(actionFilter),
                    new StringStatementValue(server)
            );
        } else {
            statement1 = new Statement("CALL GetLogMessagesByContextAndValue(?, ?, ?)",
                    new StringStatementValue(key),
                    new StringStatementValue(value),
                    new StringStatementValue(server)
            );
        }

        database.executeProcedure(statement1, -1, result -> {
            try {
                while (result.next()) {
                    String message = result.getString(1);
                    String action = result.getString(2);
                    long timestamp = result.getLong(3);
                    String contextRaw = result.getString(4);

                    HashMap<String, String> contextMap = new HashMap<>();
                    String[] contexts = contextRaw.split("\\|");
                    for (String context : contexts) {
                        String[] split = context.split("::");
                        contextMap.put(split[0], split[1]);
                    }

                    if (action == null) {
                        log.warn("Fetching a log with no action, excluding. {}", contextRaw).submit();
                        continue;
                    }
                    CachedLog log = new CachedLog(message, action, timestamp, contextMap);
                    logs.add(log);
                }
            } catch (SQLException e) {
                log.error("Error fetching log data", e).submit();
            }
        }, TargetDatabase.GLOBAL).join();

        return logs;
    }
}
