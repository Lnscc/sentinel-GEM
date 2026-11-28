package com.example.globalnewsenginev1.statistics;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.sql.Statement;
import java.sql.Connection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.h2.jdbcx.JdbcDataSource;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StatisticsQueryBudgetTests {
    @Test
    void neverInheritsCursorFetchingWhichBypassesPgJdbcStatementCancellation() {
        JdbcTemplate shared = new JdbcTemplate(new JdbcDataSource());
        shared.setFetchSize(100);
        assertThat(new StatisticsJdbcTemplate(shared).getFetchSize()).isZero();
    }

    @Test
    void deadlineCancelsOnlyItsOwnStatementsAndRejectsFurtherWork() throws Exception {
        Statement own = statement();
        Statement other = statement();
        try (StatisticsQueryBudget otherBudget = new StatisticsQueryBudget(Duration.ofSeconds(5))) {
            otherBudget.bind();
            StatisticsQueryBudget.configure(other);
            try (StatisticsQueryBudget budget = new StatisticsQueryBudget(Duration.ofMillis(500))) {
                budget.bind();
                StatisticsQueryBudget.configure(own);
                verify(own).setQueryTimeout(1);
                verify(own, timeout(2000)).cancel();
                assertThatThrownBy(StatisticsQueryBudget::checkCurrent).isInstanceOf(QueryTimeoutException.class);
                Statement next = mock(Statement.class);
                assertThatThrownBy(() -> StatisticsQueryBudget.configure(next)).isInstanceOf(QueryTimeoutException.class);
                verifyNoInteractions(next);
            }
            verify(other, never()).cancel();
        }
    }

    @Test
    void detectedAbortCancelsBlockedQueryAndWorkerReleasesItsScope() throws Exception {
        Statement statement = statement();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        doAnswer(invocation -> { cancelled.countDown(); return null; }).when(statement).cancel();
        StatisticsQueryBudget budget = new StatisticsQueryBudget();
        Thread worker = Thread.startVirtualThread(() -> {
            try (budget) {
                budget.bind();
                StatisticsQueryBudget.configure(statement);
                registered.countDown();
                assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(StatisticsQueryBudget::checkCurrent).isInstanceOf(QueryTimeoutException.class);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        assertThat(registered.await(2, TimeUnit.SECONDS)).isTrue();
        budget.cancel();
        worker.join(2000);
        assertThat(worker.isAlive()).isFalse();
        verify(statement).setQueryTimeout(intThat(seconds -> seconds > 0 && seconds <= 45));
        verify(statement).cancel();
        StatisticsQueryBudget.checkCurrent();
    }

    @Test
    void completedRequestCancelsItsTimerAndClosedStatementsAreIgnored() throws Exception {
        Statement closed = statement();
        when(closed.isClosed()).thenReturn(true);
        try (StatisticsQueryBudget budget = new StatisticsQueryBudget(Duration.ofSeconds(5))) {
            budget.bind();
            StatisticsQueryBudget.configure(closed);
            budget.cancel();
            assertThatThrownBy(StatisticsQueryBudget::checkCurrent).isInstanceOf(QueryTimeoutException.class);
        }
        verify(closed, never()).cancel();
        StatisticsQueryBudget.checkCurrent();
    }

    private Statement statement() throws Exception {
        Statement statement = mock(Statement.class);
        Connection connection = mock(Connection.class);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return statement;
    }
}
