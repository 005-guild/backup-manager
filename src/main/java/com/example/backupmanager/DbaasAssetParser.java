package com.example.backupmanager;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

final class DbaasAssetParser {
    private DbaasAssetParser() {}

    static DbaasAsset parse(JsonNode row) {
        if (row == null || !row.isObject()) throw new IllegalArgumentException("DBLIST 资产记录不是对象");
        String id = text(row, "ID");
        String name = text(row, "DBNAME");
        if (id == null || id.length() > 255 || name == null || name.length() > 255) {
            throw new IllegalArgumentException("DBLIST 资产记录缺少有效的 ID 或 DBNAME");
        }
        DatabaseMetadata metadata = new DatabaseMetadata(
            shorten(text(row, "DBID"), 100), shorten(text(row, "DBTAG"), 100),
            shorten(text(row, "VALID"), 40), shorten(text(row, "LEVEL"), 40),
            shorten(text(row, "APP_FRAMEWORK"), 80), shorten(text(row, "DBVERSION"), 80),
            shorten(text(row, "CMBSYSCODE"), 100),
            shorten(first(row, "DEVUSER", "DEVUSERS"), 100),
            shorten(text(row, "DBAUSER"), 100),
            shorten(text(row, "DB_SERVICE_UNIT"), 100),
            date(row, "DB_CREATE_DATE")
        );
        return new DbaasAsset(id, name, shorten(text(row, "LOGICDB_CODE"), 255),
            number(row, "LDBID"), shorten(text(row, "VALID"), 10), row.toString(), metadata);
    }

    private static String first(JsonNode row, String first, String second) {
        String value = text(row, first);
        return value == null ? text(row, second) : value;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        String result = value.asText().trim();
        return result.isEmpty() || "null".equalsIgnoreCase(result) ? null : result;
    }

    private static String shorten(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    private static Long number(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static LocalDate date(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.length() < 10) return null;
        try { return LocalDate.parse(value.substring(0, 10)); }
        catch (DateTimeParseException ignored) { return null; }
    }
}
