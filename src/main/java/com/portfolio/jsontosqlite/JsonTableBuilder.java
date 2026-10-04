package com.portfolio.jsontosqlite;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Analyzes JSON records and creates the relational table descriptions. */
final class JsonTableBuilder {
    private static final String VALUE_FIELD = "value";
    private static final String JSON_RECORDS_TABLE = "json_records";
    private static final String JSON_ARRAY_TABLES_TABLE = "json_array_tables";
    private static final String JSON_NODES_TABLE = "json_nodes";
    private final ProgressTracker progress;

    /** Creates a builder that reports discovered keys through the supplied tracker. */
    JsonTableBuilder(ProgressTracker progress) {
        this.progress = progress;
    }

    /** Builds root and nested table descriptions from the parsed JSON tree. */
    ImportPlan build(JsonElement root) {
        JsonElement recordSource = root;
        String recordPath = "$";
        if (root.isJsonObject() && root.getAsJsonObject().size() == 1) {
            var wrapper = root.getAsJsonObject().entrySet().iterator().next();
            if (wrapper.getValue().isJsonArray()) {
                recordSource = wrapper.getValue();
                recordPath += "." + escapeKey(wrapper.getKey()) + "[]";
                progress.markKey(wrapper.getKey());
            }
        }
        List<JsonElement> records = new ArrayList<>();
        if (recordSource.isJsonArray()) {
            for (JsonElement element : recordSource.getAsJsonArray()) {
                records.add(element);
            }
        } else {
            records.add(recordSource);
        }

        RecordTable rootTable = new RecordTable(JSON_RECORDS_TABLE, recordPath, null);
        Map<String, RecordTable> childTables = new LinkedHashMap<>();
        for (JsonElement jsonRecord : records) {
            collectFields(jsonRecord, rootTable, "", childTables);
        }
        for (JsonElement jsonRecord : records) {
            collectRecordValues(jsonRecord, rootTable, "", childTables);
        }
        removeConstantFields(rootTable);
        for (RecordTable table : childTables.values()) {
            removeConstantFields(table);
        }
        assignInternalColumns(rootTable, false);
        for (RecordTable table : childTables.values()) {
            assignInternalColumns(table, true);
        }
        return new ImportPlan(records, rootTable, childTables);
    }

    /** Marks object keys in one record as processed by the progress tracker. */
    void markRecordKeys(JsonElement element) {
        if (!element.isJsonObject()) {
            return;
        }
        for (var member : element.getAsJsonObject().entrySet()) {
            progress.markKey(member.getKey());
            if (member.getValue().isJsonObject()) {
                markRecordKeys(member.getValue());
            }
        }
    }

    /** Discovers scalar columns and nested array tables for one JSON value. */
    private void collectFields(JsonElement element, RecordTable table, String fieldPath,
                               Map<String, RecordTable> childTables) {
        if (element.isJsonObject()) {
            collectObjectFields(element, table, fieldPath, childTables);
            return;
        }
        if (element.isJsonArray()) {
            collectArrayFields(element, table, fieldPath, childTables);
            return;
        }
        table.fields.add(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath);
    }

    /** Traverses object members and records an empty object as a scalar field. */
    private void collectObjectFields(JsonElement element, RecordTable table, String fieldPath,
                                     Map<String, RecordTable> childTables) {
        if (element.getAsJsonObject().size() == 0) {
            table.fields.add(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath);
            return;
        }
        for (var member : element.getAsJsonObject().entrySet()) {
            collectFields(member.getValue(), table, joinFieldPath(fieldPath, member.getKey()), childTables);
        }
    }

    /** Creates a table for an array and recursively discovers its fields. */
    private void collectArrayFields(JsonElement element, RecordTable table, String fieldPath,
                                    Map<String, RecordTable> childTables) {
        String arrayPath = table.path + (fieldPath.isEmpty() ? "" : "." + fieldPath) + "[]";
        RecordTable childTable = childTables.computeIfAbsent(arrayPath, path ->
                new RecordTable(createArrayTableName(arrayPath, childTables.values()), path, table.name));
        for (JsonElement item : element.getAsJsonArray()) {
            collectFields(item, childTable, "", childTables);
        }
    }

    /** Assigns internal row, parent, and array-index columns to a table. */
    private void assignInternalColumns(RecordTable table, boolean childTable) {
        if (table.fields.isEmpty()) {
            table.fields.add(VALUE_FIELD);
        }
        Set<String> usedNames = new LinkedHashSet<>(table.fields);
        table.rowIdColumn = uniqueInternalName(usedNames, "__row_id");
        usedNames.add(table.rowIdColumn);
        if (childTable) {
            table.parentIdColumn = uniqueInternalName(usedNames, "__parent_id");
            usedNames.add(table.parentIdColumn);
            table.arrayIndexColumn = uniqueInternalName(usedNames, "__array_index");
        }
    }

    /** Stores flattened values for a record and discovers values for nested arrays. */
    private void collectRecordValues(JsonElement element, RecordTable table, String fieldPath,
                                     Map<String, RecordTable> childTables) {
        Map<String, String> values = new LinkedHashMap<>();
        flattenFields(element, "", values);
        table.rows.add(values);
        collectNestedArrayValues(element, table, fieldPath, childTables);
    }

    /** Recursively maps nested array values into their corresponding child tables. */
    private void collectNestedArrayValues(JsonElement element, RecordTable parentTable, String fieldPath,
                                          Map<String, RecordTable> childTables) {
        if (element.isJsonObject()) {
            for (var member : element.getAsJsonObject().entrySet()) {
                collectNestedArrayValues(member.getValue(), parentTable,
                        joinFieldPath(fieldPath, member.getKey()), childTables);
            }
            return;
        }
        if (!element.isJsonArray()) {
            return;
        }
        String arrayPath = parentTable.path + (fieldPath.isEmpty() ? "" : "." + fieldPath) + "[]";
        RecordTable childTable = childTables.get(arrayPath);
        if (childTable == null) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            collectRecordValues(item, childTable, "", childTables);
        }
    }

    /** Removes fields whose values are identical in every row of a table. */
    private void removeConstantFields(RecordTable table) {
        if (table.rows.size() < 2) {
            return;
        }
        table.fields.removeIf(field -> {
            Set<String> uniqueValues = new LinkedHashSet<>();
            for (Map<String, String> row : table.rows) {
                uniqueValues.add(row.get(field));
                if (uniqueValues.size() > 1) {
                    return false;
                }
            }
            return uniqueValues.size() == 1;
        });
    }

    /** Produces an unused internal column name by prefixing underscores as needed. */
    private String uniqueInternalName(Set<String> usedNames, String preferredName) {
        StringBuilder name = new StringBuilder(preferredName);
        while (usedNames.contains(name.toString())) {
            name.insert(0, '_');
        }
        return name.toString();
    }

    /** Converts an array path into a readable, unique SQLite table name. */
    private String createArrayTableName(String arrayPath, Iterable<RecordTable> existingTables) {
        String readablePath = arrayPath.replace("$", "").replace("[]", "")
                .replace("\\.", " dot ").replace("\\[", " ").replace("\\]", " ")
                .replace("\\\\", " ").replace('.', ' ');
        StringBuilder name = new StringBuilder("json_");
        for (int index = 0; index < readablePath.length(); index++) {
            char character = readablePath.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                if (!name.isEmpty() && name.charAt(name.length() - 1) != '_') {
                    name.append('_');
                }
                continue;
            }
            if (Character.isUpperCase(character) && !name.isEmpty()
                    && name.charAt(name.length() - 1) != '_'
                    && Character.isLowerCase(name.charAt(name.length() - 1))) {
                name.append('_');
            }
            name.append(Character.toLowerCase(character));
        }
        String baseName = name.toString().replaceAll("_+", "_");
        if (baseName.endsWith("_")) {
            baseName = baseName.substring(0, baseName.length() - 1);
        }
        Set<String> usedNames = new LinkedHashSet<>(List.of(JSON_RECORDS_TABLE, JSON_ARRAY_TABLES_TABLE, JSON_NODES_TABLE));
        for (RecordTable table : existingTables) {
            usedNames.add(table.name);
        }
        String uniqueName = baseName;
        int suffix = 2;
        while (usedNames.contains(uniqueName)) {
            uniqueName = baseName + "_" + suffix++;
        }
        return uniqueName;
    }

    /** Flattens object leaves into field-path/value pairs while ignoring arrays. */
    void flattenFields(JsonElement element, String fieldPath, Map<String, String> values) {
        if (element.isJsonObject()) {
            if (element.getAsJsonObject().size() == 0) {
                values.put(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath, element.toString());
                return;
            }
            for (var member : element.getAsJsonObject().entrySet()) {
                flattenFields(member.getValue(), joinFieldPath(fieldPath, member.getKey()), values);
            }
        } else if (!element.isJsonArray()) {
            values.put(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath, scalarValue(element));
        }
    }

    /** Converts a JSON primitive or null to the string stored in SQLite. */
    private String scalarValue(JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        }
        if (element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return element.toString();
    }

    /** Escapes JSON key syntax so field paths remain unambiguous. */
    static String escapeKey(String key) {
        if (key.isEmpty()) {
            return "\\0";
        }
        return key.replace("\\", "\\\\").replace(".", "\\.")
                .replace("[", "\\[").replace("]", "\\]").replace("_", "__");
    }

    /** Joins a parent field path and child key using the established encoding. */
    static String joinFieldPath(String parentPath, String key) {
        String escapedKey = escapeKey(key);
        return parentPath.isEmpty() ? escapedKey : parentPath + "_" + escapedKey;
    }
}
