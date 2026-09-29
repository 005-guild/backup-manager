package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlatformParserTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sampleUpdateTimeIsOnlyInferredDate() throws Exception {
        JsonNode row = mapper.readTree(
            "{\"time\":\"2026-08-10 00:00:01.2040\",\"dbname\":\"laa1db15\",\"status\":\"DISPATCHING\"}");
        PlatformParser.Normalized result = PlatformParser.normalize(row);
        assertEquals(LocalDate.of(2026, 8, 10), result.backupDate());
        assertTrue(result.dateInferred());
        assertEquals("DISPATCHING", result.status());
    }

    @Test void explicitBackupDateIsUsedInsteadOfUpdateTime() throws Exception {
        JsonNode row = mapper.readTree(
            "{\"id\":\"task-1\",\"time\":\"2026-08-11 01:00:00\",\"backupDate\":\"2026-08-10\","
                + "\"dbname\":\"db\",\"status\":\"successed\"}");
        PlatformParser.Normalized result = PlatformParser.normalize(row);
        assertEquals(LocalDate.of(2026, 8, 10), result.backupDate());
        assertFalse(result.dateInferred());
        assertEquals("task-1", result.externalId());
    }

    @Test void nestedRowsAreExtracted() throws Exception {
        JsonNode json = mapper.readTree("{\"data\":{\"rows\":[{\"dbname\":\"db\"}]}}");
        assertEquals(1, PlatformParser.rows(json, "data.rows").size());
    }
}
