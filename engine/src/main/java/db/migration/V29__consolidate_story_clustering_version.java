package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V29__consolidate_story_clustering_version extends BaseJavaMigration {
    private static final String WINNER = "story-mvp-title-embedding-24h-v1.1.0";
    private static final String REASON = "GEM-76: consolidated after independent holdout and release gates";

    @Override
    public void migrate(Context context) throws Exception {
        if (!context.getConnection().getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
            return;
        }
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                    ALTER TABLE story_clustering_version_status_history
                    DROP CONSTRAINT ck_story_clustering_status_history_transition
                    """);
            statement.execute("""
                    ALTER TABLE story_clustering_version_status_history
                    ADD CONSTRAINT ck_story_clustering_status_history_transition CHECK (
                        (previous_status IS NULL AND new_status = 'SHADOW')
                        OR (previous_status = 'SHADOW' AND new_status IN ('ACTIVE', 'RETIRED'))
                        OR (previous_status = 'ACTIVE' AND new_status = 'RETIRED')
                        OR (previous_status = 'RETIRED' AND new_status = 'ACTIVE')
                    )
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION story_guard_clustering_version_update()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        IF (to_jsonb(NEW) - 'status' - 'updated_at')
                            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'updated_at') THEN
                            RAISE EXCEPTION 'Used clustering version definitions are immutable';
                        END IF;
                        IF NEW.status <> OLD.status AND NOT (
                            (OLD.status = 'SHADOW' AND NEW.status IN ('ACTIVE', 'RETIRED'))
                            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')
                            OR (OLD.status = 'RETIRED' AND NEW.status = 'ACTIVE')
                        ) THEN
                            RAISE EXCEPTION 'Invalid clustering version status transition: % -> %',
                                OLD.status, NEW.status;
                        END IF;
                        RETURN NEW;
                    END
                    $$
                    """);
        }
        retire(context, "version_key <> '" + WINNER + "'");
        try (var statement = context.getConnection().prepareStatement("""
                INSERT INTO story_clustering_version_status_history (
                    clustering_version_id, previous_status, new_status, reason, changed_at)
                SELECT id, status, 'ACTIVE', ?, CURRENT_TIMESTAMP
                FROM story_clustering_versions
                WHERE version_key = ? AND status <> 'ACTIVE'
                """)) {
            statement.setString(1, REASON);
            statement.setString(2, WINNER);
            statement.executeUpdate();
        }
        try (var statement = context.getConnection().prepareStatement("""
                UPDATE story_clustering_versions
                SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE version_key = ? AND status <> 'ACTIVE'
                """)) {
            statement.setString(1, WINNER);
            statement.executeUpdate();
        }
    }

    private void retire(Context context, String predicate) throws Exception {
        try (var statement = context.getConnection().prepareStatement("""
                INSERT INTO story_clustering_version_status_history (
                    clustering_version_id, previous_status, new_status, reason, changed_at)
                SELECT id, status, 'RETIRED', ?, CURRENT_TIMESTAMP
                FROM story_clustering_versions
                WHERE %s AND status <> 'RETIRED'
                """.formatted(predicate))) {
            statement.setString(1, REASON);
            statement.executeUpdate();
        }
        try (var statement = context.getConnection().createStatement()) {
            statement.executeUpdate("""
                    UPDATE story_clustering_versions
                    SET status = 'RETIRED', updated_at = CURRENT_TIMESTAMP
                    WHERE %s AND status <> 'RETIRED'
                    """.formatted(predicate));
        }
    }
}
