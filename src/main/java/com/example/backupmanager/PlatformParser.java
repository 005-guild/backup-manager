package com.example.backupmanager;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;

final class PlatformParser {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private PlatformParser() {}

    record Normalized(String databaseName, String externalId, LocalDate backupDate,
                      boolean dateInferred, Instant eventTime, String status, String rawJson) {}

    static JsonNode rows(JsonNode body, String dataPath) {
        if (!dataPath.isBlank()) {
            JsonNode current = body;
            for (String part : dataPath.split("\\.")) current = current.path(part);
            if (!current.isArray()) throw new IllegalArgumentException("接口记录路径无效: " + dataPath);
            return current;
        }
        if (body.isArray()) return body;
        for (String path : List.of("data.records", "data.rows", "data.list", "data", "records", "rows", "list", "result.records", "result.rows", "result.list")) {
            JsonNode current = body;
            for (String part : path.split("\\.")) current = current.path(part);
            if (current.isArray()) return current;
        }
        throw new IllegalArgumentException("无法识别接口返回列表，请配置 BACKUP_DAILY_DATA_PATH");
    }

    static Normalized normalize(JsonNode row) {
        String name = row.path("dbname").asText("").trim();
        String status = row.path("status").asText("").trim();
        String time = row.path("time").asText("").trim();
        if (name.isEmpty() || status.isEmpty() || time.isEmpty()) throw new IllegalArgumentException("记录缺少 dbname、status 或 time");
        Instant updated = parseTime(time);
        String explicitDate = firstText(row, "backupDate", "backup_date", "date");
        boolean inferred = explicitDate.isBlank();
        LocalDate backupDate = inferred ? updated.atZone(ZONE).toLocalDate() : LocalDate.parse(explicitDate.substring(0, 10));
        String id = firstText(row, "id", "backupId", "taskId");
        if (id.isBlank()) {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256").digest((name + "|" + updated + "|" + status).getBytes(StandardCharsets.UTF_8));
                id = "derived:" + HexFormat.of().formatHex(bytes);
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        return new Normalized(name, id, backupDate, inferred, updated, status, row.toString());
    }

    private static String firstText(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode value = row.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
        }
        return "";
    }
    private static Instant parseTime(String text) {
        String normalized = text.replace(' ', 'T');
        try { return Instant.parse(normalized); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(normalized).toInstant(); } catch (DateTimeParseException ignored) {}
        return LocalDateTime.parse(normalized).atZone(ZONE).toInstant();
    }
}
