package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackupSyncServiceTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private CatalogRepository catalog;
    private OceanProtectClient oceanProtect;
    private BackupSyncService service;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogRepository.class);
        oceanProtect = mock(OceanProtectClient.class);
        service = new BackupSyncService(catalog, mapper, oceanProtect);
    }

    @Test
    void oneOceanProtectFetchPersistsMonthlyAndYearlyCopiesAndFinishesBothRuns() throws Exception {
        when(oceanProtect.configured()).thenReturn(true);
        when(catalog.startSync("monthly")).thenReturn(101L);
        when(catalog.startSync("yearly")).thenReturn(102L);
        when(catalog.getOrCreateDatabase("monthly_new_db", LocalDate.of(2026, 9, 1))).thenReturn(201L);
        when(catalog.getOrCreateDatabase("monthly_existing_db", LocalDate.of(2026, 9, 2))).thenReturn(202L);
        when(catalog.getOrCreateDatabase("yearly_new_db", LocalDate.of(2026, 12, 31))).thenReturn(203L);
        when(catalog.hasBackup("monthly", "monthly-new")).thenReturn(false);
        when(catalog.hasBackup("monthly", "shared-copy")).thenReturn(true);
        when(catalog.hasBackup("yearly", "shared-copy")).thenReturn(false);

        List<JsonNode> copies = List.of(
            copy("monthly_new_db", "monthly-new", "2026-09-01T00:00:00Z", "available",
                "monthly-policy", "month"),
            copy("monthly_existing_db", "shared-copy", "2026-09-02T00:00:00Z", "available",
                "monthly-existing-policy", "month"),
            copy("yearly_new_db", "shared-copy", "2026-12-31T00:00:00Z", "available",
                "yearly-policy", "year"),
            mapper.readTree("{\"uuid\":\"ignored-copy\"}"),
            mapper.readTree("""
                {
                  "resource_name": "ambiguous_db",
                  "uuid": "ambiguous-copy",
                  "display_timestamp": "2026-12-31T00:00:00Z",
                  "status": "available",
                  "generated_by": "sla",
                  "sla_name": "combined-policy",
                  "sla_properties": {
                    "policy_list": [
                      {"schedule": {"trigger_action": "month"}},
                      {"schedule": {"trigger_action": "year"}}
                    ]
                  }
                }
                """)
        );
        when(oceanProtect.fetchCopies(any())).thenAnswer(invocation -> {
            OceanProtectClient.CopyConsumer consumer = invocation.getArgument(0);
            for (JsonNode copy : copies) consumer.accept(copy);
            return new OceanProtectClient.FetchSummary(5, 2, 4);
        });

        Map<String, Object> result = service.syncOceanProtect();

        assertEquals(5, result.get("fetched"));
        assertEquals(2, result.get("slaCount"));
        assertEquals(4, result.get("pages"));
        assertEquals(2, result.get("monthlyFetched"));
        assertEquals(1, result.get("monthlySaved"));
        assertEquals(1, result.get("yearlyFetched"));
        assertEquals(1, result.get("yearlySaved"));
        assertEquals(1, result.get("ignored"));
        assertEquals(1, result.get("ambiguous"));

        verify(oceanProtect, times(1)).fetchCopies(any());
        verify(catalog).hasBackup("monthly", "shared-copy");
        verify(catalog).hasBackup("yearly", "shared-copy");
        verify(catalog).upsertBackup(eq(201L), eq("monthly"), eq("monthly-new"),
            eq(LocalDate.of(2026, 9, 1)), eq(false), eq(Instant.parse("2026-09-01T00:00:00Z")),
            eq("successed"), anyString());
        verify(catalog).upsertBackup(eq(202L), eq("monthly"), eq("shared-copy"),
            eq(LocalDate.of(2026, 9, 2)), eq(false), eq(Instant.parse("2026-09-02T00:00:00Z")),
            eq("successed"), anyString());
        verify(catalog).upsertBackup(eq(203L), eq("yearly"), eq("shared-copy"),
            eq(LocalDate.of(2026, 12, 31)), eq(false), eq(Instant.parse("2026-12-31T00:00:00Z")),
            eq("successed"), anyString());
        verify(catalog, never()).hasBackup(anyString(), eq("ignored-copy"));
        verify(catalog, never()).hasBackup(anyString(), eq("ambiguous-copy"));
        verify(catalog, never()).getOrCreateDatabase(eq("ambiguous_db"), any());

        String note = "跳过 1 条，策略类型不明确 1 条";
        verify(catalog).finishSync(101L, "success", 2, 1, note);
        verify(catalog).finishSync(102L, "success", 1, 1, note);
    }

    @Test
    void clientFailureMarksBothRunsFailedAndExposesOnlyTheGenericSyncError() throws Exception {
        when(oceanProtect.configured()).thenReturn(true);
        when(catalog.startSync("monthly")).thenReturn(301L);
        when(catalog.startSync("yearly")).thenReturn(302L);
        IllegalStateException clientError = new IllegalStateException("OceanProtect token expired");
        when(oceanProtect.fetchCopies(any())).thenThrow(clientError);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, service::syncOceanProtect);

        assertEquals("月备/年备同步失败，请查看同步记录", thrown.getMessage());
        assertEquals(clientError, thrown.getCause());
        verify(catalog).finishSync(301L, "failed", 0, 0, "OceanProtect token expired");
        verify(catalog).finishSync(302L, "failed", 0, 0, "OceanProtect token expired");
    }

    private JsonNode copy(String database, String externalId, String timestamp, String status,
                          String slaName, String action) throws Exception {
        return mapper.readTree("""
            {
              "resource_name": "%s",
              "uuid": "%s",
              "display_timestamp": "%s",
              "status": "%s",
              "generated_by": "sla",
              "sla_name": "%s",
              "sla_properties": {
                "policy_list": [
                  {"schedule": {"trigger_action": "%s"}}
                ]
              }
            }
            """.formatted(database, externalId, timestamp, status, slaName, action));
    }
}
