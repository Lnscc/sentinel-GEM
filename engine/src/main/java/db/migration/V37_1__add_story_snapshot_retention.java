package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

public class V37_1__add_story_snapshot_retention extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            boolean postgres = context.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL");
            String bigintArray = postgres ? "BIGINT[]" : "BIGINT ARRAY";
            statement.execute("""
                    CREATE TABLE story_snapshot_retention (
                        snapshot_id BIGINT PRIMARY KEY REFERENCES story_snapshots(id),
                        article_id_deltas %1$s NOT NULL,
                        kept_pair_ids %1$s,
                        completed_at TIMESTAMP WITH TIME ZONE
                    )
                    """.formatted(bigintArray));
            String liveMembers = """
                    SELECT member.snapshot_id, member.clustering_version_id, input.article_id
                    FROM story_snapshot_members member
                    JOIN story_article_inputs input ON input.id = member.article_input_id
                    """;
            String archive = postgres ? """
                    WHERE NOT EXISTS (
                        SELECT 1 FROM story_snapshot_retention archive WHERE archive.snapshot_id = member.snapshot_id
                    )
                    UNION ALL
                    SELECT archive.snapshot_id, snapshot.clustering_version_id, member.article_id
                    FROM story_snapshot_retention archive
                    JOIN story_snapshots snapshot ON snapshot.id = archive.snapshot_id
                    CROSS JOIN LATERAL (
                        SELECT SUM(delta) OVER (ORDER BY ordinal)::BIGINT AS article_id
                        FROM unnest(archive.article_id_deltas) WITH ORDINALITY ids(delta, ordinal)
                    ) member
                    """ : "";
            statement.execute("CREATE VIEW story_snapshot_statistics_members AS " + liveMembers + archive);
            if (!postgres) return;

            statement.execute("""
                    CREATE INDEX idx_story_pair_decisions_snapshot_left_input
                    ON story_pair_decisions (snapshot_id, left_article_input_id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_story_pair_decisions_snapshot_right_input
                    ON story_pair_decisions (snapshot_id, right_article_input_id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_story_assignment_decisions_snapshot_input
                    ON story_assignment_decisions (snapshot_id, article_input_id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_story_processing_runs_snapshot ON story_processing_runs (snapshot_id)
                    """);
            statement.execute("""
                    CREATE FUNCTION story_retention_delete_allowed(affected_snapshot_id BIGINT)
                    RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
                        SELECT current_setting('stories.snapshot_retention', true) = 'on'
                            AND EXISTS (SELECT 1 FROM story_snapshot_retention
                                        WHERE snapshot_id = affected_snapshot_id AND completed_at IS NULL)
                    $$
                    """);
            statement.execute("""
                    CREATE FUNCTION story_guard_pair_decision()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' AND story_retention_delete_allowed(OLD.snapshot_id) THEN
                            RETURN OLD;
                        END IF;
                        RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
                    END
                    $$
                    """);
            statement.execute("DROP TRIGGER trg_story_pair_decisions_append_only ON story_pair_decisions");
            statement.execute("""
                    CREATE TRIGGER trg_story_pair_decisions_append_only
                    BEFORE UPDATE OR DELETE ON story_pair_decisions
                    FOR EACH ROW EXECUTE FUNCTION story_guard_pair_decision()
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION story_guard_snapshot_member()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE
                        affected_snapshot_id BIGINT;
                    BEGIN
                        affected_snapshot_id := CASE
                            WHEN TG_OP = 'DELETE' THEN OLD.snapshot_id
                            ELSE NEW.snapshot_id
                        END;
                        IF TG_OP = 'DELETE' AND story_retention_delete_allowed(affected_snapshot_id) THEN
                            RETURN OLD;
                        END IF;
                        IF EXISTS (SELECT 1 FROM story_processing_runs WHERE snapshot_id = affected_snapshot_id) THEN
                            RAISE EXCEPTION 'Snapshot members are immutable after the first run';
                        END IF;
                        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
                    END
                    $$
                    """);
        }
    }
}
