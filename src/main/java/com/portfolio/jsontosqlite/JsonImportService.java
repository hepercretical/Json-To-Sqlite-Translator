package com.portfolio.jsontosqlite;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Coordinates JSON parsing, progress reporting, and the SQLite transaction. */
final class JsonImportService {
    /** Reads the JSON input, creates its schema, and commits the SQLite import. */
    ImportResult importJson(Path input, Path output, Consumer<ProgressUpdate> progressUpdates)
            throws IOException, SQLException {
        JsonElement root;
        try (var reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        }
        ProgressTracker progress = new ProgressTracker(collectUniqueKeys(root), progressUpdates);
        progress.publish();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + output.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            connection.setAutoCommit(false);
            try {
                SqliteDatabaseWriter.dropPreviousImport(connection);
                ImportPlan plan = new JsonTableBuilder(progress).build(root);
                ImportResult result = new SqliteDatabaseWriter().write(connection, plan, progress);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("Failed while importing JSON nodes.", exception);
            }
        }
    }

    /** Recursively gathers every distinct object key used by the input JSON. */
    private Set<String> collectUniqueKeys(JsonElement element) {
        Set<String> keys = new LinkedHashSet<>();
        collectUniqueKeys(element, keys);
        return keys;
    }

    /** Adds object keys while recursively traversing objects and arrays. */
    private void collectUniqueKeys(JsonElement element, Set<String> keys) {
        if (element.isJsonObject()) {
            for (var member : element.getAsJsonObject().entrySet()) {
                keys.add(member.getKey());
                collectUniqueKeys(member.getValue(), keys);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectUniqueKeys(item, keys);
            }
        }
    }
}
