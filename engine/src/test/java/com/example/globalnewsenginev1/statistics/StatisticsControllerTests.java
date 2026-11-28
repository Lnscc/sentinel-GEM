package com.example.globalnewsenginev1.statistics;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockAsyncContext;
import jakarta.servlet.AsyncEvent;

import java.sql.Statement;
import java.sql.Connection;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
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
        perform(get("/statistics"))
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
        perform(get("/statistics").param("hours", "24")).andExpect(status().isOk());
        perform(get("/statistics").param("hours", "24").param("from", "2026-11-18T00:00:00Z"))
                .andExpect(status().isBadRequest());
        perform(get("/statistics").param("hours", "bad")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownAndInvalidParameters() throws Exception {
        perform(get("/statistics").param("unknown", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        when(service.report("bad", null)).thenThrow(new IllegalArgumentException("Invalid timestamp"));
        perform(get("/statistics").param("from", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void unavailableDatabaseConnectionReturns503BeforeQueriesRun() throws Exception {
        when(service.report(null, null)).thenThrow(new CannotCreateTransactionException("connection unavailable"));
        perform(get("/statistics")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("statistics_unavailable"));
    }

    @Test
    void databaseFailureReturnsUnavailableInsteadOfZeroValuesOrDatabaseDetails() throws Exception {
        when(service.report(null, null)).thenThrow(new DataAccessResourceFailureException("password=secret"));
        perform(get("/statistics"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("statistics_unavailable"))
                .andExpect(jsonPath("$.metrics").doesNotExist())
                .andExpect(jsonPath("$.message").value("Statistics could not be loaded. Please retry."));
    }

    @Test
    void detectedAsyncRequestErrorCancelsItsDatabaseStatement() throws Exception {
        Statement statement = mock(Statement.class);
        Connection connection = mock(Connection.class);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        doAnswer(invocation -> { cancelled.countDown(); return null; }).when(statement).cancel();
        when(service.report(null, null)).thenAnswer(invocation -> {
            StatisticsQueryBudget.configure(statement);
            started.countDown();
            // Spring interrupts its Callable before invoking onError. Model a JDBC call that
            // needs Statement.cancel rather than relying on interruptible Java latch cleanup.
            boolean interrupted = false;
            while (cancelled.getCount() > 0) {
                try {
                    assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            StatisticsQueryBudget.checkCurrent();
            throw new AssertionError("Aborted statistics work must stop");
        });
        var result = mvc.perform(get("/statistics")).andExpect(request().asyncStarted()).andReturn();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, new IOException("Client disconnected")));
        }
        verify(statement, timeout(2000)).cancel();
        // A disconnected socket cannot receive an error body; database cancellation is the contract here.
        assertThat(result.getAsyncResult(2000)).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mvc.perform(request);
        return result.andReturn().getRequest().isAsyncStarted()
                ? mvc.perform(asyncDispatch(result.andReturn())) : result;
    }
}
