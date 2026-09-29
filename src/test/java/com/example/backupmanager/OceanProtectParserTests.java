package com.example.backupmanager;

import static com.example.backupmanager.Compat.setOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OceanProtectParserTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesMonthlyCopyWithStringPropertiesAndMapsAvailableStatus() throws Exception {
        JsonNode row = mapper.readTree(
            "{\"uuid\":\"bf51fa6d-a14d-4264-b428-c75542dfc030\",\"resource_name\":\"db01\","
                + "\"sla_name\":\"月度备份策略\","
                + "\"sla_properties\":\"{\\\"policy_list\\\":[{\\\"schedule\\\":{\\\"trigger_action\\\":\\\"month\\\"}}]}\","
                + "\"display_timestamp\":\"2026-09-01T01:00:00Z\",\"generated_by\":\"sla\",\"status\":\"available\"}");

        OceanProtectParser.ParseResult result = OceanProtectParser.parse(row, mapper);

        assertTrue(result.parsed());
        assertEquals("monthly", result.kind());
        assertEquals("月度备份策略", result.slaName());
        assertEquals("db01", result.normalized().databaseName());
        assertEquals("bf51fa6d-a14d-4264-b428-c75542dfc030", result.normalized().externalId());
        assertEquals(LocalDate.of(2026, 9, 1), result.normalized().backupDate());
        assertEquals(Instant.parse("2026-09-01T01:00:00Z"), result.normalized().eventTime());
        assertEquals("successed", result.normalized().status());
        assertFalse(result.normalized().dateInferred());
    }

    @Test
    void parsesYearlyCopyWithObjectPropertiesAndPreservesUnknownStatus() throws Exception {
        JsonNode row = mapper.readTree(
            "{\"uuid\":\"copy-2\",\"resource_name\":\"finance\",\"sla_name\":\"年度策略\","
                + "\"sla_properties\":{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"year\"}}]},"
                + "\"display_timestamp\":\"2026-12-31T23:15:00+08:00\",\"generated_by\":\"bAcKuP\","
                + "\"status\":\"locked\"}");

        OceanProtectParser.ParseResult result = OceanProtectParser.parse(row, mapper);

        assertTrue(result.parsed());
        assertEquals("yearly", result.kind());
        assertEquals(LocalDate.of(2026, 12, 31), result.normalized().backupDate());
        assertEquals("locked", result.normalized().status());
    }

    @Test
    void exposesKindsDeclaredByAnSlaObject() throws Exception {
        JsonNode sla = mapper.readTree(
            "{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"month\"}},"
                + "{\"schedule\":{\"trigger_action\":\"week\"}},"
                + "{\"schedule\":{\"trigger_action\":\"YEAR\"}}]}");

        assertEquals(setOf("monthly", "yearly"), OceanProtectParser.kindsFromSla(sla, mapper));
    }

    @Test
    void mixedSchedulesAreAlwaysAmbiguousEvenWhenSlaNameSuggestsOneKind() throws Exception {
        JsonNode monthAndYear = mixedScheduleCopy("月备策略");
        JsonNode dailyAndMonth = dailyAndMonthlyCopy("月备策略");

        assertTrue(OceanProtectParser.parse(monthAndYear, mapper).ambiguous());
        assertTrue(OceanProtectParser.parse(dailyAndMonth, mapper).ambiguous());
    }

    @Test
    void nonBackupPoliciesDoNotContributeKindsOrCreateAmbiguity() throws Exception {
        JsonNode sla = mapper.readTree(
            "{\"policy_list\":["
                + "{\"type\":\"replication\",\"action\":\"full\",\"schedule\":{\"trigger_action\":\"year\"}},"
                + "{\"type\":\"backup\",\"action\":\"replication\",\"schedule\":{\"trigger_action\":\"year\"}},"
                + "{\"action\":\"ARCHIVING\",\"schedule\":{\"trigger_action\":\"year\"}},"
                + "{\"type\":\"BACKUP\",\"action\":\"full\",\"schedule\":{\"trigger_action\":\"month\"}}]}");
        JsonNode row = copyWithProperties("filtered-policies", sla);

        assertEquals(setOf("monthly"), OceanProtectParser.kindsFromSla(sla, mapper));
        assertEquals("monthly", OceanProtectParser.parse(row, mapper).kind());
    }

    @Test
    void rejectsUnsupportedGeneratedByAndArchivedOrReplicatedCopies() throws Exception {
        JsonNode unsupported = monthlyCopy("manual", false, false);
        JsonNode missing = monthlyCopy(null, false, false);
        JsonNode archived = monthlyCopy("sla", true, false);
        JsonNode replicated = monthlyCopy("Backup", false, true);

        assertTrue(OceanProtectParser.parse(unsupported, mapper).ignored());
        assertTrue(OceanProtectParser.parse(missing, mapper).ignored());
        assertTrue(OceanProtectParser.parse(archived, mapper).ignored());
        assertTrue(OceanProtectParser.parse(replicated, mapper).ignored());
    }

    @Test
    void malformedPropertiesAreIgnoredWithoutThrowing() throws Exception {
        JsonNode row = mapper.readTree(
            "{\"uuid\":\"bad-json\",\"resource_name\":\"db01\",\"sla_name\":\"月度策略\","
                + "\"sla_properties\":\"{broken\",\"display_timestamp\":\"2026-09-01T01:00:00Z\","
                + "\"generated_by\":\"sla\",\"status\":\"available\"}");

        OceanProtectParser.ParseResult result = assertDoesNotThrow(() -> OceanProtectParser.parse(row, mapper));

        assertTrue(result.ignored());
        assertNull(result.normalized());
    }

    @Test
    void missingRequiredFieldAndInvalidTimestampAreIgnoredPerItem() throws Exception {
        JsonNode missingUuid = mapper.readTree(
            "{\"resource_name\":\"db01\",\"sla_name\":\"年度策略\","
                + "\"sla_properties\":{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"year\"}}]},"
                + "\"display_timestamp\":\"2026-12-31T00:00:00Z\",\"generated_by\":\"sla\","
                + "\"status\":\"available\"}");
        JsonNode badTime = mapper.readTree(
            "{\"uuid\":\"copy-bad-time\",\"resource_name\":\"db01\",\"sla_name\":\"年度策略\","
                + "\"sla_properties\":{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"year\"}}]},"
                + "\"display_timestamp\":\"not-a-time\",\"generated_by\":\"sla\",\"status\":\"available\"}");

        assertTrue(OceanProtectParser.parse(missingUuid, mapper).ignored());
        assertTrue(OceanProtectParser.parse(badTime, mapper).ignored());
    }

    @Test
    void weeklyOnlyCopyIsIgnored() throws Exception {
        JsonNode row = mapper.readTree(
            "{\"uuid\":\"weekly-copy\",\"resource_name\":\"db01\",\"sla_name\":\"周备策略\","
                + "\"sla_properties\":{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"week\"}}]},"
                + "\"display_timestamp\":\"2026-09-01T01:00:00Z\",\"generated_by\":\"sla\","
                + "\"status\":\"available\"}");

        OceanProtectParser.ParseResult result = OceanProtectParser.parse(row, mapper);

        assertTrue(result.ignored());
        assertFalse(result.ambiguous());
    }

    private JsonNode mixedScheduleCopy(String slaName) throws Exception {
        return mapper.readTree(String.format(
            "{\"uuid\":\"mixed-copy\",\"resource_name\":\"db01\",\"sla_name\":\"%s\","
                + "\"sla_properties\":{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"month\"}},"
                + "{\"schedule\":{\"trigger_action\":\"year\"}}]},"
                + "\"display_timestamp\":\"2026-09-01T01:00:00Z\",\"generated_by\":\"sla\","
                + "\"status\":\"available\"}", slaName));
    }

    private JsonNode dailyAndMonthlyCopy(String slaName) throws Exception {
        return mapper.readTree(String.format(
            "{\"uuid\":\"mixed-daily-monthly-copy\",\"resource_name\":\"db01\",\"sla_name\":\"%s\","
                + "\"sla_properties\":{\"policy_list\":["
                + "{\"action\":\"full\",\"schedule\":{\"interval\":1,\"interval_unit\":\"d\"}},"
                + "{\"action\":\"full\",\"schedule\":{\"trigger_action\":\"month\"}}]},"
                + "\"display_timestamp\":\"2026-09-01T01:00:00Z\",\"generated_by\":\"sla\","
                + "\"status\":\"available\"}", slaName));
    }

    private JsonNode copyWithProperties(String slaName, JsonNode properties) throws Exception {
        JsonNode row = monthlyCopy("sla", false, false);
        ((ObjectNode) row).put("sla_name", slaName);
        ((ObjectNode) row).set("sla_properties", properties);
        return row;
    }

    private JsonNode monthlyCopy(String generatedBy, boolean archived, boolean replicated) throws Exception {
        ObjectNode row = (ObjectNode) mapper.readTree(
            "{\"uuid\":\"monthly-copy\",\"resource_name\":\"db01\",\"sla_name\":\"月备策略\","
                + "\"sla_properties\":{\"policy_list\":[{\"action\":\"full\","
                + "\"schedule\":{\"trigger_action\":\"month\"}}]},"
                + "\"display_timestamp\":\"2026-09-01T01:00:00Z\",\"status\":\"available\"}");
        if (generatedBy != null) row.put("generated_by", generatedBy);
        row.put("is_archived", archived);
        row.put("is_replicated", replicated);
        return row;
    }
}
