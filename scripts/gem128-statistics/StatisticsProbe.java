package com.example.globalnewsenginev1.statistics;

import com.example.globalnewsenginev1.stories.EmbeddingUsageMetrics;
import com.example.globalnewsenginev1.articles.api.ArticleController;
import com.example.globalnewsenginev1.articles.query.ArticleQueryService;
import com.example.globalnewsenginev1.articles.health.ArticleExtractionHealthService;
import com.example.globalnewsenginev1.stories.api.StoryController;
import com.example.globalnewsenginev1.stories.query.StoryQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Isolated harness: production controller/service/transaction path, fixed fixture clock. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableTransactionManagement
@Import({StatisticsController.class, StatisticsExceptionHandler.class, ArticleController.class,
        ArticleQueryService.class, ArticleExtractionHealthService.class,
        StoryController.class, StoryQueryService.class})
public class StatisticsProbe {
    public static void main(String[] args) {
        SpringApplication.run(StatisticsProbe.class, args);
    }

    @Bean
    DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getenv("GEM128_JDBC_URL"));
        source.setUser("postgres");
        source.setPassword("gem128-local-only");
        source.setCurrentSchema("gem128_probe");
        return source;
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean
    StatisticsService statisticsService(DataSource source) {
        return new StatisticsService(new JdbcTemplate(source),
                new EmbeddingUsageMetrics(new SimpleMeterRegistry()), "visible",
                Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC));
    }
}
