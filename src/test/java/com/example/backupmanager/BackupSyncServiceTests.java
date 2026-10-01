package com.example.backupmanager;

import static com.example.backupmanager.Compat.listOf;
import static com.example.backupmanager.Compat.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BackupSyncServiceTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private CatalogRepository catalog;
    private OceanProtectClient oceanProtect;
    private BackupRetentionService retention;
    private BackupSyncService service;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogRepository.class);
        oceanProtect = mock(OceanProtectClient.class);
        retention = mock(BackupRetentionService.class);
        when(retention.currentPolicy()).thenReturn(new BackupRetentionService.RetentionPolicy(7, 365));
        service = new BackupSyncService(catalog, mapper, oceanProtect, retention);
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

        List<JsonNode> copies = listOf(
            copy("monthly_new_db", "monthly-new", "2026-09-01T00:00:00Z", "available",
                "monthly-policy", "month"),
            copy("monthly_existing_db", "shared-copy", "2026-09-02T00:00:00Z", "available",
                "monthly-existing-policy", "month"),
            copy("yearly_new_db", "shared-copy", "2026-12-31T00:00:00Z", "available",
                "yearly-policy", "year"),
            mapper.readTree("{\"uuid\":\"ignored-copy\"}"),
            mapper.readTree("{\"resource_name\":\"ambiguous_db\",\"uuid\":\"ambiguous-copy\","
                + "\"display_timestamp\":\"2026-12-31T00:00:00Z\",\"status\":\"available\","
                + "\"generated_by\":\"sla\",\"sla_name\":\"combined-policy\","
                + "\"sla_properties\":{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"month\"}},"
                + "{\"schedule\":{\"trigger_action\":\"year\"}}]}}")
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

    @Test
    void independentSchedulesRunAtMidnightHalfPastMidnightAndOneAmShanghaiTime() throws Exception {
        assertSchedule("scheduledDailySync", "${app.daily-sync-cron:0 0 0 * * *}");
        assertSchedule("scheduledMonthlySync", "${app.monthly-sync-cron:0 30 0 * * *}");
        assertSchedule("scheduledYearlySync", "${app.yearly-sync-cron:0 0 1 * * *}");
    }

    @Test
    void scheduledEntriesInvokeOnlyTheirOwnSync() {
        BackupSyncService scheduled = spy(service);
        ReflectionTestUtils.setField(scheduled, "schedulerEnabled", true);
        ReflectionTestUtils.setField(scheduled, "dailyUrl", "https://example.test/daily");
        ReflectionTestUtils.setField(scheduled, "dailyToken", "test-token");
        when(oceanProtect.configured()).thenReturn(true);
        doReturn(Collections.emptyMap()).when(scheduled).syncDaily();
        doReturn(Collections.emptyMap()).when(scheduled).syncMonthly();
        doReturn(Collections.emptyMap()).when(scheduled).syncYearly();

        scheduled.scheduledDailySync();
        verify(scheduled).syncDaily();
        verify(scheduled, never()).syncMonthly();
        verify(scheduled, never()).syncYearly();

        clearInvocations(scheduled);
        scheduled.scheduledMonthlySync();
        verify(scheduled).syncMonthly();
        verify(scheduled, never()).syncDaily();
        verify(scheduled, never()).syncYearly();

        clearInvocations(scheduled);
        scheduled.scheduledYearlySync();
        verify(scheduled).syncYearly();
        verify(scheduled, never()).syncDaily();
        verify(scheduled, never()).syncMonthly();
    }

    @Test
    void monthlySyncPersistsOnlyMonthlyCopiesAndOwnRun() throws Exception {
        when(oceanProtect.configured()).thenReturn(true);
        when(catalog.startSync("monthly")).thenReturn(401L);
        when(catalog.getOrCreateDatabase("monthly_db", LocalDate.of(2026, 9, 1))).thenReturn(501L);
        JsonNode monthly = copy("monthly_db", "monthly-copy", "2026-09-01T00:00:00Z",
            "available", "monthly-policy", "month");
        JsonNode yearly = copy("yearly_db", "yearly-copy", "2026-12-31T00:00:00Z",
            "available", "yearly-policy", "year");
        when(oceanProtect.fetchCopies(eq("monthly"), any(OceanProtectClient.CopyConsumer.class)))
            .thenAnswer(invocation -> {
                OceanProtectClient.CopyConsumer consumer = invocation.getArgument(1);
                consumer.accept(monthly);
                consumer.accept(yearly);
                return new OceanProtectClient.FetchSummary(2, 1, 2);
            });

        service.syncMonthly();

        verify(oceanProtect).fetchCopies(eq("monthly"), any(OceanProtectClient.CopyConsumer.class));
        verify(catalog).startSync("monthly");
        verify(catalog, never()).startSync("yearly");
        verify(catalog).upsertBackup(eq(501L), eq("monthly"), eq("monthly-copy"),
            eq(LocalDate.of(2026, 9, 1)), eq(false), eq(Instant.parse("2026-09-01T00:00:00Z")),
            eq("successed"), anyString());
        verify(catalog, never()).hasBackup(eq("yearly"), anyString());
        verify(catalog).finishSync(eq(401L), eq("success"), eq(1), eq(1), anyString());
    }

    @Test
    void yearlySyncPersistsOnlyYearlyCopiesAndOwnRun() throws Exception {
        when(oceanProtect.configured()).thenReturn(true);
        when(catalog.startSync("yearly")).thenReturn(402L);
        when(catalog.getOrCreateDatabase("yearly_db", LocalDate.of(2026, 12, 31))).thenReturn(502L);
        JsonNode monthly = copy("monthly_db", "monthly-copy", "2026-09-01T00:00:00Z",
            "available", "monthly-policy", "month");
        JsonNode yearly = copy("yearly_db", "yearly-copy", "2026-12-31T00:00:00Z",
            "available", "yearly-policy", "year");
        when(oceanProtect.fetchCopies(eq("yearly"), any(OceanProtectClient.CopyConsumer.class)))
            .thenAnswer(invocation -> {
                OceanProtectClient.CopyConsumer consumer = invocation.getArgument(1);
                consumer.accept(monthly);
                consumer.accept(yearly);
                return new OceanProtectClient.FetchSummary(2, 1, 2);
            });

        service.syncYearly();

        verify(oceanProtect).fetchCopies(eq("yearly"), any(OceanProtectClient.CopyConsumer.class));
        verify(catalog).startSync("yearly");
        verify(catalog, never()).startSync("monthly");
        verify(catalog).upsertBackup(eq(502L), eq("yearly"), eq("yearly-copy"),
            eq(LocalDate.of(2026, 12, 31)), eq(false), eq(Instant.parse("2026-12-31T00:00:00Z")),
            eq("successed"), anyString());
        verify(catalog, never()).hasBackup(eq("monthly"), anyString());
        verify(catalog).finishSync(eq(402L), eq("success"), eq(1), eq(1), anyString());
    }

    @Test
    void monthlyFailureDoesNotCreateOrFinishYearlyRun() throws Exception {
        when(oceanProtect.configured()).thenReturn(true);
        when(catalog.startSync("monthly")).thenReturn(403L);
        when(oceanProtect.fetchCopies(eq("monthly"), any(OceanProtectClient.CopyConsumer.class)))
            .thenThrow(new IllegalStateException("OceanProtect temporarily unavailable"));

        assertThrows(IllegalStateException.class, service::syncMonthly);

        verify(catalog).finishSync(403L, "failed", 0, 0, "OceanProtect temporarily unavailable");
        verify(catalog, never()).startSync("yearly");
    }

    @Test
    void monthlySyncSkipsCopiesOutsideOneYearRetentionWindow() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate expiredDate = today.minusDays(366);
        LocalDate currentDate = today.minusDays(30);
        when(oceanProtect.configured()).thenReturn(true);
        when(catalog.startSync("monthly")).thenReturn(404L);
        when(catalog.getOrCreateDatabase("current_monthly_db", currentDate)).thenReturn(504L);
        JsonNode expired = copy("expired_monthly_db", "expired-monthly",
            shanghaiTimestamp(expiredDate), "available", "monthly-policy", "month");
        JsonNode current = copy("current_monthly_db", "current-monthly",
            shanghaiTimestamp(currentDate), "available", "monthly-policy", "month");
        when(oceanProtect.fetchCopies(eq("monthly"), any(OceanProtectClient.CopyConsumer.class)))
            .thenAnswer(invocation -> {
                OceanProtectClient.CopyConsumer consumer = invocation.getArgument(1);
                consumer.accept(expired);
                consumer.accept(current);
                return new OceanProtectClient.FetchSummary(2, 1, 2);
            });

        Map<String, Object> result = service.syncMonthly();

        assertEquals(2, result.get("matched"));
        assertEquals(1, result.get("saved"));
        assertEquals(1, result.get("expired"));
        verify(catalog, never()).getOrCreateDatabase(eq("expired_monthly_db"), any());
        verify(catalog, never()).hasBackup(eq("monthly"), eq("expired-monthly"));
        verify(catalog).upsertBackup(eq(504L), eq("monthly"), eq("current-monthly"),
            eq(currentDate), eq(false), any(Instant.class), eq("successed"), anyString());
        verify(catalog).finishSync(eq(404L), eq("success"), eq(2), eq(1), anyString());
    }

    @Test
    void dailySyncSkipsCopiesOutsideSevenDayRetentionWindow() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate expiredDate = today.minusDays(8);
        LocalDate currentDate = today.minusDays(2);
        String url = "https://example.test/daily";
        ReflectionTestUtils.setField(service, "dailyUrl", url);
        ReflectionTestUtils.setField(service, "dailyToken", "test-token");
        ReflectionTestUtils.setField(service, "pageSize", 2);
        ReflectionTestUtils.setField(service, "maxPages", 2);
        when(catalog.startSync("daily")).thenReturn(405L);
        when(catalog.getOrCreateDatabase("current_daily_db", currentDate)).thenReturn(505L);
        RestTemplate http = (RestTemplate) ReflectionTestUtils.getField(service, "http");
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        String firstPage = mapper.writeValueAsString(mapOf("data", listOf(
            mapOf("id", "expired-daily", "dbname", "expired_daily_db", "status", "successed",
                "time", shanghaiTimestamp(expiredDate), "backupDate", expiredDate.toString()),
            mapOf("id", "current-daily", "dbname", "current_daily_db", "status", "successed",
                "time", shanghaiTimestamp(currentDate), "backupDate", currentDate.toString()))));
        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.syncDaily();

        server.verify();
        assertEquals(2, result.get("fetched"));
        assertEquals(1, result.get("saved"));
        assertEquals(1, result.get("expired"));
        verify(catalog, never()).getOrCreateDatabase(eq("expired_daily_db"), any());
        verify(catalog, never()).hasBackup(eq("daily"), eq("expired-daily"));
        verify(catalog).upsertBackup(eq(505L), eq("daily"), eq("current-daily"),
            eq(currentDate), eq(false), any(Instant.class), eq("successed"), anyString());
        verify(catalog).finishSync(eq(405L), eq("success"), eq(2), eq(1), anyString());
    }

    private String shanghaiTimestamp(LocalDate date) {
        return date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString();
    }

    private void assertSchedule(String methodName, String expectedCron) throws Exception {
        Scheduled scheduled = BackupSyncService.class.getMethod(methodName).getAnnotation(Scheduled.class);
        assertEquals(expectedCron, scheduled.cron());
        assertEquals("Asia/Shanghai", scheduled.zone());
    }

    private JsonNode copy(String database, String externalId, String timestamp, String status,
                          String slaName, String action) throws Exception {
        return mapper.readTree(String.format(
            "{\"resource_name\":\"%s\",\"uuid\":\"%s\",\"display_timestamp\":\"%s\","
                + "\"status\":\"%s\",\"generated_by\":\"sla\",\"sla_name\":\"%s\","
                + "\"sla_properties\":{\"policy_list\":[{\"schedule\":{\"trigger_action\":\"%s\"}}]}}",
            database, externalId, timestamp, status, slaName, action));
    }
}
