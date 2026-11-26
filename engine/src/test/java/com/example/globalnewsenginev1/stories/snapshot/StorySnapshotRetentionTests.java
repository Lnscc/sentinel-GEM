package com.example.globalnewsenginev1.stories.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorySnapshotRetentionTests {

    @ParameterizedTest
    @MethodSource("timeouts")
    void timedOutBatchYieldsItsVersionAndConsumesTheSharedPollBudget(RuntimeException timeout) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT id FROM story_clustering_versions ORDER BY id", Long.class))
                .thenReturn(List.of(1L, 2L, 3L));
        var retention = retention(jdbc, 2);
        doThrow(timeout).when(retention).compactBatch(eq(1L), any());
        doReturn(new StorySnapshotRetention.Batch(20, 1, 1, false, 0, 1))
                .when(retention).compactBatch(eq(2L), any());

        retention.compactSnapshots();

        verify(retention).compactBatch(eq(1L), any());
        verify(retention).compactBatch(eq(2L), any());
        verify(retention, never()).compactBatch(eq(3L), any());
        verify(retention, times(2)).compactBatch(anyLong(), any());
    }

    @Test
    void emptyVersionsDoNotConsumeTheBudgetForLaterWork() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT id FROM story_clustering_versions ORDER BY id", Long.class))
                .thenReturn(List.of(1L, 2L, 3L));
        var retention = retention(jdbc, 1);
        doReturn(null).when(retention).compactBatch(anyLong(), any());
        doReturn(new StorySnapshotRetention.Batch(30, 1, 1, false, 0, 1))
                .when(retention).compactBatch(eq(3L), any());

        retention.compactSnapshots();

        verify(retention).compactBatch(eq(1L), any());
        verify(retention).compactBatch(eq(2L), any());
        verify(retention).compactBatch(eq(3L), any());
        verify(retention, times(3)).compactBatch(anyLong(), any());
    }

    @Test
    void nextPollRetriesTheVersionThatYieldedAfterTimeout() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT id FROM story_clustering_versions ORDER BY id", Long.class))
                .thenReturn(List.of(1L));
        var retention = retention(jdbc, 1);
        doThrow(new QueryTimeoutException("archive preparation timed out"))
                .doReturn(new StorySnapshotRetention.Batch(10, 1, 1, false, 0, 1))
                .when(retention).compactBatch(eq(1L), any());

        retention.compactSnapshots();
        retention.compactSnapshots();

        verify(retention, times(2)).compactBatch(eq(1L), any());
    }

    private static Stream<RuntimeException> timeouts() {
        return Stream.of(new QueryTimeoutException("statement timeout"),
                new CannotAcquireLockException("lock timeout"),
                new TransactionTimedOutException("transaction timeout"));
    }

    private StorySnapshotRetention retention(JdbcTemplate jdbc, int maxBatches) {
        return spy(new StorySnapshotRetention(jdbc, mock(PlatformTransactionManager.class),
                Duration.ZERO, 1, maxBatches, Duration.ofSeconds(2),
                Duration.ofMillis(250), Duration.ofSeconds(5)));
    }
}
