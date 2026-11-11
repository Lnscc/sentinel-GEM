package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

public class V31__separate_story_hnsw_shadow_errors extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE story_hnsw_shadow_runs ADD COLUMN exact_error TEXT");
            statement.execute("ALTER TABLE story_hnsw_shadow_runs ADD COLUMN hnsw_error TEXT");
        }
    }
}
