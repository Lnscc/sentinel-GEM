package db.migration;

import com.example.globalnewsenginev1.gdelt.staging.model.GdeltStageEvent;
import com.example.globalnewsenginev1.gdelt.staging.parser.GdeltEventParser;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class V28__add_event_action_location extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        migrate(context.getConnection());
    }

    public void migrate(Connection connection) throws Exception {
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_type INTEGER");
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_name TEXT");
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_country_code VARCHAR(16)");
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_adm1_code VARCHAR(32)");
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_adm2_code VARCHAR(32)");
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_latitude DOUBLE PRECISION");
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_longitude DOUBLE PRECISION");
        execute(connection, "ALTER TABLE gdelt_events ADD COLUMN action_geo_feature_id VARCHAR(255)");

        GdeltEventParser parser = new GdeltEventParser();
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("""
                     SELECT event.id, payload.raw_tsv
                     FROM gdelt_events event
                     JOIN gdelt_event_payloads payload ON payload.id = event.id
                     ORDER BY event.id
                     """);
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE gdelt_events
                     SET action_geo_type = ?, action_geo_name = ?, action_geo_country_code = ?,
                         action_geo_adm1_code = ?, action_geo_adm2_code = ?, action_geo_latitude = ?,
                         action_geo_longitude = ?, action_geo_feature_id = ?
                     WHERE id = ?
                     """)) {
            while (rows.next()) {
                GdeltStageEvent event = parser.parse(rows.getString("raw_tsv"));
                update.setObject(1, event.actionGeoType());
                update.setString(2, event.actionGeoName());
                update.setString(3, event.actionGeoCountryCode());
                update.setString(4, event.actionGeoAdm1Code());
                update.setString(5, event.actionGeoAdm2Code());
                update.setObject(6, event.actionGeoLatitude());
                update.setObject(7, event.actionGeoLongitude());
                update.setString(8, event.actionGeoFeatureId());
                update.setLong(9, rows.getLong("id"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
