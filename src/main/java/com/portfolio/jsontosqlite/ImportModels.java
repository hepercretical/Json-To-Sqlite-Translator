package com.portfolio.jsontosqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Holds the in-memory table description assembled from JSON records. */
final class RecordTable {
    final String name;
    final String path;
    final String parentName;
    final Set<String> fields = new LinkedHashSet<>();
    final List<Map<String, String>> rows = new ArrayList<>();
    String rowIdColumn;
    String parentIdColumn;
    String arrayIndexColumn;

    /** Creates a table description for a JSON object or nested array. */
    RecordTable(String name, String path, String parentName) {
        this.name = name;
        this.path = path;
        this.parentName = parentName;
    }
}

/** Carries the root and nested tables produced during schema analysis. */
record ImportPlan(List<com.google.gson.JsonElement> records, RecordTable rootTable,
                  Map<String, RecordTable> childTables) { }

/** Reports the number of root records and nested rows written to SQLite. */
record ImportResult(long recordCount, long relatedRowCount) { }

/** Reports progress through the unique JSON keys discovered in the input. */
record ProgressUpdate(int completedKeys, int totalKeys) { }

/** Tracks key progress and sends updates to the progress dialog. */
final class ProgressTracker {
    private final int totalKeys;
    private final Consumer<ProgressUpdate> updates;
    private final Set<String> completedKeys = new LinkedHashSet<>();

    /** Creates a tracker for the discovered keys and its update callback. */
    ProgressTracker(Set<String> uniqueKeys, Consumer<ProgressUpdate> updates) {
        totalKeys = uniqueKeys.size();
        this.updates = updates;
    }

    /** Marks a key as processed and publishes progress only on its first occurrence. */
    void markKey(String key) {
        if (completedKeys.add(key)) {
            publish();
        }
    }

    /** Publishes the current completed-key count to the registered callback. */
    void publish() {
        updates.accept(new ProgressUpdate(completedKeys.size(), totalKeys));
    }
}

/** Reuses prepared SQLite insert statements while nested records are written. */
final class ImportContext implements AutoCloseable {
    final Connection connection;
    final Map<String, RecordTable> childTables;
    final ProgressTracker progress;
    private final Map<String, PreparedStatement> insertStatements = new LinkedHashMap<>();

    /** Creates an insertion context for one database transaction. */
    ImportContext(Connection connection, Map<String, RecordTable> childTables, ProgressTracker progress) {
        this.connection = connection;
        this.childTables = childTables;
        this.progress = progress;
    }

    /** Gets or creates the prepared insert statement for a table. */
    PreparedStatement insertStatement(RecordTable table) throws SQLException {
        PreparedStatement existing = insertStatements.get(table.name);
        if (existing != null) {
            return existing;
        }
        List<String> columns = new ArrayList<>();
        if (table.parentIdColumn != null) {
            columns.add(table.parentIdColumn);
            columns.add(table.arrayIndexColumn);
        }
        columns.addAll(table.fields);
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(SqliteDatabaseWriter.quoteIdentifier(table.name)).append(" (");
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                sql.append(", ");
                placeholders.append(", ");
            }
            sql.append(SqliteDatabaseWriter.quoteIdentifier(columns.get(index)));
            placeholders.append('?');
        }
        sql.append(") VALUES (").append(placeholders).append(')');
        PreparedStatement insert = connection.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS);
        insertStatements.put(table.name, insert);
        return insert;
    }

    /** Closes every cached prepared statement and preserves the first close failure. */
    @Override
    public void close() throws SQLException {
        SQLException closeFailure = null;
        for (PreparedStatement insert : insertStatements.values()) {
            try {
                insert.close();
            } catch (SQLException exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
