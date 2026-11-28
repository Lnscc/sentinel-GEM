package com.example.globalnewsenginev1.statistics;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StatisticsQueryBudgetPostgresIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadlineOrDetectedAbortCancelsOnlyOwnedQueryAndReleasesTransaction(boolean abort) throws Exception {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:5432/gne"));
        postgres.setUser(System.getProperty("it.postgres.username", "gne"));
        postgres.setPassword(System.getProperty("it.postgres.password", "gne"));
        try (Connection ignored = postgres.getConnection()) {
            // Reuse the repository's explicit isolated PostgreSQL integration-test connection.
        } catch (SQLException exception) {
            assumeTrue(false, "Statistics budget test requires the local compose database");
        }
        HikariConfig config = new HikariConfig();
        config.setDataSource(postgres);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(2);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcTemplate regular = new JdbcTemplate(pool);
            StatisticsJdbcTemplate statistics = new StatisticsJdbcTemplate(regular);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
            CountDownLatch siblingStarted = new CountDownLatch(1);
            var sibling = CompletableFuture.supplyAsync(() -> {
                siblingStarted.countDown();
                return regular.queryForObject("SELECT 42 FROM pg_sleep(1)", Integer.class);
            });
            assertThat(siblingStarted.await(2, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            try (StatisticsQueryBudget budget = new StatisticsQueryBudget(abort ? Duration.ofSeconds(5) : Duration.ofMillis(300))) {
                budget.bind();
                if (abort) Thread.startVirtualThread(() -> {
                    try {
                        Thread.sleep(300);
                        budget.cancel();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThatThrownBy(() -> transaction.execute(status -> {
                    statistics.execute("SELECT pg_sleep(10)");
                    return null;
                })).isInstanceOf(DataAccessException.class);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(sibling.get(3, TimeUnit.SECONDS)).isEqualTo(42);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(regular.queryForObject("SELECT 42", Integer.class)).isEqualTo(42);
            assertThat(regular.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("0");
        }
    }
}
