package com.example.globalnewsenginev1.statistics;

import org.springframework.dao.QueryTimeoutException;

import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Connection;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** The budget and statements belong to one HTTP request, never to a pooled connection. */
final class StatisticsQueryBudget implements AutoCloseable {
    static final int SECONDS = 45;
    private static final ThreadLocal<StatisticsQueryBudget> CURRENT = new ThreadLocal<>();
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "statistics-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Statement> statements = ConcurrentHashMap.newKeySet();
    private final long deadline;
    private final ScheduledFuture<?> timer;
    private volatile boolean cancelled;

    StatisticsQueryBudget() {
        this(Duration.ofSeconds(SECONDS));
    }

    StatisticsQueryBudget(Duration duration) {
        deadline = System.nanoTime() + duration.toNanos();
        timer = DEADLINES.schedule(this::cancel, duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    void bind() {
        CURRENT.set(this);
        checkCurrent();
    }

    static void checkCurrent() {
        StatisticsQueryBudget budget = CURRENT.get();
        if (budget != null && (budget.cancelled || System.nanoTime() >= budget.deadline)) {
            throw new QueryTimeoutException("Statistics request budget exhausted");
        }
    }

    static void configure(Statement statement) throws SQLException {
        StatisticsQueryBudget budget = CURRENT.get();
        if (budget == null) return;
        checkCurrent();
        long remaining = budget.deadline - System.nanoTime();
        statement.setQueryTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining)));
        budget.statements.add(statement);
        // A deadline can fire between the first check and registering the statement.
        checkCurrent();
        Connection connection = statement.getConnection();
        if (!connection.getAutoCommit()
                && "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            try (Statement settings = connection.createStatement()) {
                budget.statements.add(settings);
                try {
                    checkCurrent();
                    settings.setQueryTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(budget.deadline - System.nanoTime())));
                    long milliseconds = Math.max(1, TimeUnit.NANOSECONDS.toMillis(budget.deadline - System.nanoTime()));
                    settings.execute("SET LOCAL statement_timeout = '" + milliseconds + "ms'");
                } finally {
                    budget.statements.remove(settings);
                }
            }
            checkCurrent();
        }
    }

    void cancel() {
        cancelled = true;
        // Driver cancellation performs network I/O; never let it delay another request's deadline.
        Thread.startVirtualThread(this::cancelStatements);
    }

    private void cancelStatements() {
        for (Statement statement : statements) {
            try {
                if (!statement.isClosed()) statement.cancel();
            } catch (SQLException ignored) {
                // A statement may have completed or closed while cancellation was delivered.
            }
        }
    }

    @Override
    public void close() {
        timer.cancel(false);
        statements.clear();
        CURRENT.remove();
    }
}
