package com.example.globalnewsenginev1.stories.snapshot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(-100)
class StoryLocationBackfill implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final StorySnapshotRepository repository;
    private final StoryLocationMaterializer materializer;
    private final TransactionTemplate transaction;

    StoryLocationBackfill(JdbcTemplate jdbc, StorySnapshotRepository repository,
                          StoryLocationMaterializer materializer,
                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.materializer = materializer;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        transaction.executeWithoutResult(status -> jdbc.queryForList(
                        """
                        SELECT DISTINCT story.clustering_version_id
                        FROM stories story
                        JOIN story_clustering_versions version ON version.id = story.clustering_version_id
                        WHERE version.status IN ('SHADOW', 'ACTIVE')
                        ORDER BY story.clustering_version_id
                        """,
                        Long.class)
                .forEach(versionId -> {
                    repository.lockVersion(versionId);
                    materializer.refresh(versionId);
                }));
    }
}
