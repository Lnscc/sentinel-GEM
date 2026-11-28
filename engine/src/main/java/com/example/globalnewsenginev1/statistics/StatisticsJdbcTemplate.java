package com.example.globalnewsenginev1.statistics;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.sql.Statement;

final class StatisticsJdbcTemplate extends JdbcTemplate {
    StatisticsJdbcTemplate(JdbcTemplate template) {
        super(template.getDataSource());
        // pgjdbc cursor fetches bypass Statement.cancel(); each query must finish under its own deadline.
        setFetchSize(0);
        setMaxRows(template.getMaxRows());
        setQueryTimeout(template.getQueryTimeout());
        setExceptionTranslator(template.getExceptionTranslator());
    }

    @Override
    protected void applyStatementSettings(Statement statement) throws SQLException {
        super.applyStatementSettings(statement);
        StatisticsQueryBudget.configure(statement);
    }
}
