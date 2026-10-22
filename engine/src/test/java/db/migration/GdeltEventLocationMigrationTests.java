package db.migration;

import com.example.globalnewsenginev1.gdelt.staging.parser.GdeltParserTests;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class GdeltEventLocationMigrationTests {

    @Test
    void addsAndBackfillsActionLocationFields() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:event-location-migration-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE gdelt_event_payloads (id BIGINT PRIMARY KEY, raw_tsv TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE gdelt_events (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO gdelt_event_payloads VALUES (1, ?)", GdeltParserTests.eventRow());
        jdbc.update("INSERT INTO gdelt_events VALUES (1)");

        try (Connection connection = dataSource.getConnection()) {
            new V28__add_event_action_location().migrate(connection);
        }

        assertThat(jdbc.queryForMap("SELECT * FROM gdelt_events WHERE id = 1"))
                .containsEntry("ACTION_GEO_TYPE", 4)
                .containsEntry("ACTION_GEO_NAME", "Berlin, Germany")
                .containsEntry("ACTION_GEO_COUNTRY_CODE", "GM")
                .containsEntry("ACTION_GEO_ADM1_CODE", "GM16")
                .containsEntry("ACTION_GEO_ADM2_CODE", "11000")
                .containsEntry("ACTION_GEO_LATITUDE", 52.52)
                .containsEntry("ACTION_GEO_LONGITUDE", 13.405)
                .containsEntry("ACTION_GEO_FEATURE_ID", "-1746443");
    }
}
