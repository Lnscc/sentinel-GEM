package com.example.globalnewsenginev1.statistics;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StatisticsControllerTests {
    private final StatisticsService service = mock(StatisticsService.class);
    private final MockMvc mvc = standaloneSetup(new StatisticsController(service))
            .setControllerAdvice(new StatisticsExceptionHandler()).build();

    @Test
    void exposesStructuredReportWithoutCaching() throws Exception {
        Instant now = Instant.parse("2026-11-18T12:00:00Z");
        when(service.report(null, null)).thenReturn(new StatisticsReport(now,
                new StatisticsReport.Period(now.minusSeconds(86400), now), "visible", "ACTIVE", List.of(), List.of()));
        mvc.perform(get("/statistics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.visibleVersion").value("visible"))
                .andExpect(jsonPath("$.visibleVersionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.period.from").value("2026-11-17T12:00:00Z"))
                .andExpect(jsonPath("$.metrics").isArray())
                .andExpect(jsonPath("$.recentRuns").isArray());
    }

    @Test
    void acceptsRelativePeriodAndRejectsAmbiguousOrInvalidParameters() throws Exception {
        when(service.recent(24)).thenReturn(new StatisticsReport(Instant.EPOCH,
                new StatisticsReport.Period(Instant.EPOCH, Instant.EPOCH), "visible", "ACTIVE", List.of(), List.of()));
        mvc.perform(get("/statistics").param("hours", "24")).andExpect(status().isOk());
        mvc.perform(get("/statistics").param("hours", "24").param("from", "2026-11-18T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/statistics").param("hours", "bad")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownAndInvalidParameters() throws Exception {
        mvc.perform(get("/statistics").param("unknown", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        when(service.report("bad", null)).thenThrow(new IllegalArgumentException("Invalid timestamp"));
        mvc.perform(get("/statistics").param("from", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void unavailableDatabaseConnectionReturns503BeforeQueriesRun() throws Exception {
        when(service.report(null, null)).thenThrow(new CannotCreateTransactionException("connection unavailable"));
        mvc.perform(get("/statistics")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("statistics_unavailable"));
    }

    @Test
    void databaseFailureReturnsUnavailableInsteadOfZeroValuesOrDatabaseDetails() throws Exception {
        when(service.report(null, null)).thenThrow(new DataAccessResourceFailureException("password=secret"));
        mvc.perform(get("/statistics"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("statistics_unavailable"))
                .andExpect(jsonPath("$.metrics").doesNotExist())
                .andExpect(jsonPath("$.message").value("Statistics could not be loaded. Please retry."));
    }
}
