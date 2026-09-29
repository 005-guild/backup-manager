package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OceanProtectParserTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesMonthlyCopyWithStringPropertiesAndMapsAvailableStatus() throws Exception {
        var row = mapper.readTree("""
            {
              "uuid":"bf51fa6d-a14d-4264-b428-c75542dfc030",
              "resource_name":"db01",
              "sla_name":"月度备份策略",
              "sla_properties":"{\\\"policy_list\\\":[{\\\"schedule\\\":{\\\"trigger_action\\\":\\\"month\\\"}}]}",
              "display_timestamp":"2026-09-01T01:00:00Z",
              "generated_by":"sla",
              "status":"available"
            }
            """);

        var result = OceanProtectParser.parse(row, mapper);

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
        var row = mapper.readTree("""
            {
              "uuid":"copy-2",
              "resource_name":"finance",
              "sla_name":"年度策略",
              "sla_properties":{"policy_list":[{"schedule":{"trigger_action":"year"}}]},
              "display_timestamp":"2026-12-31T23:15:00+08:00",
              "generated_by":"bAcKuP",
              "status":"locked"
            }
            """);

        var result = OceanProtectParser.parse(row, mapper);

        assertTrue(result.parsed());
        assertEquals("yearly", result.kind());
        assertEquals(LocalDate.of(2026, 12, 31), result.normalized().backupDate());
        assertEquals("locked", result.normalized().status());
    }

    @Test
    void exposesKindsDeclaredByAnSlaObject() throws Exception {
        var sla = mapper.readTree("""
            {"policy_list":[
              {"schedule":{"trigger_action":"month"}},
              {"schedule":{"trigger_action":"week"}},
              {"schedule":{"trigger_action":"YEAR"}}
            ]}
            """);

        assertEquals(Set.of("monthly", "yearly"), OceanProtectParser.kindsFromSla(sla, mapper));
    }

    @Test
    void mixedSchedulesAreAlwaysAmbiguousEvenWhenSlaNameSuggestsOneKind() throws Exception {
        var monthAndYear = mixedScheduleCopy("月备策略");
        var dailyAndMonth = dailyAndMonthlyCopy("月备策略");

        assertTrue(OceanProtectParser.parse(monthAndYear, mapper).ambiguous());
        assertTrue(OceanProtectParser.parse(dailyAndMonth, mapper).ambiguous());
    }

    @Test
    void nonBackupPoliciesDoNotContributeKindsOrCreateAmbiguity() throws Exception {
        var sla = mapper.readTree("""
            {"policy_list":[
              {"type":"replication","action":"full","schedule":{"trigger_action":"year"}},
              {"type":"backup","action":"replication","schedule":{"trigger_action":"year"}},
              {"action":"ARCHIVING","schedule":{"trigger_action":"year"}},
              {"type":"BACKUP","action":"full","schedule":{"trigger_action":"month"}}
            ]}
            """);
        var row = copyWithProperties("filtered-policies", sla);

        assertEquals(Set.of("monthly"), OceanProtectParser.kindsFromSla(sla, mapper));
        assertEquals("monthly", OceanProtectParser.parse(row, mapper).kind());
    }

    @Test
    void rejectsUnsupportedGeneratedByAndArchivedOrReplicatedCopies() throws Exception {
        var unsupported = monthlyCopy("manual", false, false);
        var missing = monthlyCopy(null, false, false);
        var archived = monthlyCopy("sla", true, false);
        var replicated = monthlyCopy("Backup", false, true);

        assertTrue(OceanProtectParser.parse(unsupported, mapper).ignored());
        assertTrue(OceanProtectParser.parse(missing, mapper).ignored());
        assertTrue(OceanProtectParser.parse(archived, mapper).ignored());
        assertTrue(OceanProtectParser.parse(replicated, mapper).ignored());
    }

    @Test
    void malformedPropertiesAreIgnoredWithoutThrowing() throws Exception {
        var row = mapper.readTree("""
            {
              "uuid":"bad-json",
              "resource_name":"db01",
              "sla_name":"月度策略",
              "sla_properties":"{broken",
              "display_timestamp":"2026-09-01T01:00:00Z",
              "generated_by":"sla",
              "status":"available"
            }
            """);

        var result = assertDoesNotThrow(() -> OceanProtectParser.parse(row, mapper));

        assertTrue(result.ignored());
        assertNull(result.normalized());
    }

    @Test
    void missingRequiredFieldAndInvalidTimestampAreIgnoredPerItem() throws Exception {
        var missingUuid = mapper.readTree("""
            {
              "resource_name":"db01",
              "sla_name":"年度策略",
              "sla_properties":{"policy_list":[{"schedule":{"trigger_action":"year"}}]},
              "display_timestamp":"2026-12-31T00:00:00Z",
              "generated_by":"sla",
              "status":"available"
            }
            """);
        var badTime = mapper.readTree("""
            {
              "uuid":"copy-bad-time",
              "resource_name":"db01",
              "sla_name":"年度策略",
              "sla_properties":{"policy_list":[{"schedule":{"trigger_action":"year"}}]},
              "display_timestamp":"not-a-time",
              "generated_by":"sla",
              "status":"available"
            }
            """);

        assertTrue(OceanProtectParser.parse(missingUuid, mapper).ignored());
        assertTrue(OceanProtectParser.parse(badTime, mapper).ignored());
    }

    @Test
    void weeklyOnlyCopyIsIgnored() throws Exception {
        var row = mapper.readTree("""
            {
              "uuid":"weekly-copy",
              "resource_name":"db01",
              "sla_name":"周备策略",
              "sla_properties":{"policy_list":[{"schedule":{"trigger_action":"week"}}]},
              "display_timestamp":"2026-09-01T01:00:00Z",
              "generated_by":"sla",
              "status":"available"
            }
            """);

        var result = OceanProtectParser.parse(row, mapper);

        assertTrue(result.ignored());
        assertFalse(result.ambiguous());
    }

    private JsonNode mixedScheduleCopy(String slaName) throws Exception {
        return mapper.readTree("""
            {
              "uuid":"mixed-copy",
              "resource_name":"db01",
              "sla_name":"%s",
              "sla_properties":{"policy_list":[
                {"schedule":{"trigger_action":"month"}},
                {"schedule":{"trigger_action":"year"}}
              ]},
              "display_timestamp":"2026-09-01T01:00:00Z",
              "generated_by":"sla",
              "status":"available"
            }
            """.formatted(slaName));
    }

    private JsonNode dailyAndMonthlyCopy(String slaName) throws Exception {
        return mapper.readTree("""
            {
              "uuid":"mixed-daily-monthly-copy",
              "resource_name":"db01",
              "sla_name":"%s",
              "sla_properties":{"policy_list":[
                {"action":"full","schedule":{"interval":1,"interval_unit":"d"}},
                {"action":"full","schedule":{"trigger_action":"month"}}
              ]},
              "display_timestamp":"2026-09-01T01:00:00Z",
              "generated_by":"sla",
              "status":"available"
            }
            """.formatted(slaName));
    }

    private JsonNode copyWithProperties(String slaName, JsonNode properties) throws Exception {
        var row = monthlyCopy("sla", false, false);
        ((com.fasterxml.jackson.databind.node.ObjectNode) row).put("sla_name", slaName);
        ((com.fasterxml.jackson.databind.node.ObjectNode) row).set("sla_properties", properties);
        return row;
    }

    private JsonNode monthlyCopy(String generatedBy, boolean archived, boolean replicated) throws Exception {
        var row = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
            {
              "uuid":"monthly-copy",
              "resource_name":"db01",
              "sla_name":"月备策略",
              "sla_properties":{"policy_list":[{"action":"full","schedule":{"trigger_action":"month"}}]},
              "display_timestamp":"2026-09-01T01:00:00Z",
              "status":"available"
            }
            """);
        if (generatedBy != null) row.put("generated_by", generatedBy);
        row.put("is_archived", archived);
        row.put("is_replicated", replicated);
        return row;
    }
}
