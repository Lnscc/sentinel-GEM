package com.example.globalnewsenginev1.statistics;

import com.example.globalnewsenginev1.stories.EmbeddingUsageMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.example.globalnewsenginev1.statistics.StatisticsReport.*;

@Service
public class StatisticsService {
    private final JdbcTemplate jdbc;
    private final EmbeddingUsageMetrics usage;
    private final String visibleVersion;
    private final Clock clock;

    @Autowired
    public StatisticsService(JdbcTemplate jdbc, EmbeddingUsageMetrics usage,
                             @Value("${stories.visible-version}") String visibleVersion) {
        this(jdbc, usage, visibleVersion, Clock.systemUTC());
    }

    StatisticsService(JdbcTemplate jdbc, EmbeddingUsageMetrics usage, String visibleVersion, Clock clock) {
        this.jdbc = new StatisticsJdbcTemplate(jdbc);
        this.usage = usage;
        this.visibleVersion = visibleVersion;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 45)
    public StatisticsReport report(String from, String to) {
        return report(from, to, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 45)
    public StatisticsReport recent(int hours) {
        if (hours < 1 || hours > 744) throw new IllegalArgumentException("hours must be between 1 and 744");
        return report(null, null, hours);
    }

    private StatisticsReport report(String from, String to, Integer hours) {
        Instant now = clock.instant();
        Instant end = parse(to, now);
        if (end.isAfter(now) || end.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("to must be between 1970-01-01T00:00:00Z and now");
        }
        Instant start = parse(from, end.minus(Duration.ofHours(hours == null ? 24 : hours)));
        if (!start.isBefore(end) || start.isBefore(Instant.EPOCH)
                || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("Expected from < to <= now, with a maximum period of 31 days");
        }
        Period period = new Period(start, end);
        List<Metric> metrics = new ArrayList<>();
        Map<String, Object> stock = jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM articles) AS articles,
                       (SELECT COUNT(DISTINCT domain) FROM articles) AS sources,
                       COALESCE(SUM(domain_rows), 0) AS signals,
                       COALESCE(SUM(pending_payload_rows), 0) AS pending,
                       COALESCE(SUM(open_processing_errors), 0) AS errors
                FROM gdelt_pipeline_health_view
                """);
        inventory(metrics, "articles", "inventory", "Artikel", value(stock, "articles"), "Artikel",
                "Alle dauerhaft gespeicherten Artikel.", now, null);
        inventory(metrics, "sources", "inventory", "Quellen", value(stock, "sources"), "Domains",
                "Unterschiedliche Artikel-Domains; keine Verlage oder rechtlich unabhängigen Quellen.", now, null);
        inventory(metrics, "signals", "inventory", "GDELT-Signale", value(stock, "signals"), "Signale",
                "Persistierte GDELT-Domänenzeilen für Events, Mentions und GKG; keine Payload-Historie.", now, null);
        inventory(metrics, "pendingPayloads", "processing", "Ausstehende Payloads", value(stock, "pending"), "Payloads",
                "Vorhandene Payloads ohne zugehörige Domänenzeile laut Pipeline-Health-View.", now, null);
        inventory(metrics, "processingErrors", "processing", "Offene Verarbeitungsfehler", value(stock, "errors"), "Fehler",
                "Nicht aufgelöste GDELT-Verarbeitungsfehler laut Pipeline-Health-View.", now, null);

        List<Long> versions = jdbc.query("SELECT id FROM story_clustering_versions WHERE version_key = ?",
                (rs, row) -> rs.getLong(1), visibleVersion);
        Long version = versions.isEmpty() ? null : versions.getFirst();
        String versionStatus = version == null ? null
                : jdbc.queryForObject("SELECT status FROM story_clustering_versions WHERE id = ?", String.class, version);
        if (!"ACTIVE".equals(versionStatus)) version = null;
        stories(metrics, version, now);
        processing(metrics, version, period, now);
        runtime(metrics, now);
        List<Run> runs = new ArrayList<>(recentImports(period));
        if (version != null) {
            runs.addAll(jdbc.query("""
                    SELECT status, started_at, completed_at FROM story_processing_runs
                    WHERE clustering_version_id = ? AND started_at >= ? AND started_at < ?
                    ORDER BY started_at DESC, id DESC LIMIT 5
                    """, (rs, row) -> new Run("STORY", rs.getString(1), rs.getTimestamp(2).toInstant(),
                    instant(rs.getTimestamp(3))), version, Timestamp.from(start), Timestamp.from(end)));
        }
        runs.sort(Comparator.comparing(Run::startedAt).reversed());
        return new StatisticsReport(now, period, visibleVersion, versionStatus, List.copyOf(metrics), List.copyOf(runs));
    }

    private void stories(List<Metric> metrics, Long version, Instant now) {
        String missing = version == null ? "Die konfigurierte sichtbare Clustering-Version ist nicht vorhanden oder nicht ACTIVE." : null;
        Map<String, Object> values = version == null ? Map.of() : jdbc.queryForMap("""
                SELECT COUNT(*) AS stories,
                       COALESCE(SUM(CASE WHEN member_count = 1 THEN 1 ELSE 0 END), 0) AS singletons,
                       COALESCE(SUM(CASE WHEN member_count BETWEEN 2 AND 5 THEN 1 ELSE 0 END), 0) AS small,
                       COALESCE(SUM(CASE WHEN member_count BETWEEN 6 AND 20 THEN 1 ELSE 0 END), 0) AS medium,
                       COALESCE(SUM(CASE WHEN member_count > 20 THEN 1 ELSE 0 END), 0) AS large
                FROM stories WHERE clustering_version_id = ? AND state <> 'SUPERSEDED'
                """, version);
        inventory(metrics, "stories", "stories", "Stories", value(values, "stories"), "Stories",
                "Nicht ersetzte Stories der sichtbaren Version (ACTIVE und CLOSED).", now, missing);
        inventory(metrics, "singletonStories", "stories", "Stories mit 1 Artikel", value(values, "singletons"), "Stories",
                "Nicht ersetzte Stories mit genau einem aktuellen Mitglied der sichtbaren Version.", now, missing);
        inventory(metrics, "smallStories", "stories", "Stories mit 2–5 Artikeln", value(values, "small"), "Stories",
                "Nicht ersetzte Stories mit 2 bis 5 aktuellen Mitgliedern der sichtbaren Version.", now, missing);
        inventory(metrics, "mediumStories", "stories", "Stories mit 6–20 Artikeln", value(values, "medium"), "Stories",
                "Nicht ersetzte Stories mit 6 bis 20 aktuellen Mitgliedern der sichtbaren Version.", now, missing);
        inventory(metrics, "largeStories", "stories", "Stories mit über 20 Artikeln", value(values, "large"), "Stories",
                "Nicht ersetzte Stories mit mehr als 20 aktuellen Mitgliedern der sichtbaren Version.", now, missing);
        Double countries = version == null ? null : number("""
                SELECT COUNT(DISTINCT location.country_code) FROM story_locations location
                JOIN stories story ON story.id = location.story_id
                WHERE story.clustering_version_id = ? AND story.state <> 'SUPERSEDED'
                  AND location.rank = 1 AND TRIM(location.country_code) <> ''
                """, version);
        inventory(metrics, "countries", "stories", "Länder", countries, "Länder",
                "Unterschiedliche Ländercodes der primären Orte nicht ersetzter Stories der sichtbaren Version.", now, missing);
        Double members = version == null ? null : number("""
                SELECT COUNT(DISTINCT member.article_id) FROM story_memberships member
                JOIN stories story ON story.id = member.story_id
                WHERE member.clustering_version_id = ? AND member.current_marker = 1
                  AND story.state <> 'SUPERSEDED'
                """, version);
        inventory(metrics, "assignedArticles", "stories", "Zugeordnete Artikel", members, "Artikel",
                "Unterschiedliche Artikel mit aktueller Mitgliedschaft in nicht ersetzten Stories der sichtbaren Version.", now, missing);
    }

    private void processing(List<Metric> metrics, Long version, Period period, Instant now) {
        Timestamp from = Timestamp.from(period.from());
        Timestamp to = Timestamp.from(period.to());
        period(metrics, "incomingArticles", "processing", "Neu gespeicherte Artikel",
                number("SELECT COUNT(*) FROM articles WHERE created_at >= ? AND created_at < ?", from, to),
                "Artikel", "Artikel mit created_at im gewählten Zeitraum; keine Publikationszeit.", period, null);
        Map<String, Object> imports = jdbc.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS succeeded,
                       COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed,
                       COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN row_count ELSE 0 END), 0) AS rows,
                       SUM(EXTRACT(EPOCH FROM completed_at) - EXTRACT(EPOCH FROM started_at)) AS seconds
                FROM gdelt_import_files WHERE completed_at >= ? AND completed_at < ?
                """, from, to);
        period(metrics, "completedImports", "processing", "Erfolgreiche Dateiimporte", value(imports, "succeeded"), "Dateien",
                "COMPLETED-Dateien nach completed_at; die Tabelle bewahrt nur den letzten Versuch je Datei.", period, null);
        period(metrics, "failedImports", "processing", "Fehlgeschlagene Dateiimporte", value(imports, "failed"), "Dateien",
                "FAILED-Dateien nach completed_at; frühere Fehlversuche können durch Retries überschrieben sein.", period, null);
        period(metrics, "importedRows", "processing", "Importierte Zeilen", value(imports, "rows"), "Zeilen",
                "Summe row_count erfolgreich abgeschlossener Dateien nach completed_at.", period, null);
        period(metrics, "importDuration", "effort", "Import-Laufzeit", value(imports, "seconds"), "Sekunden",
                "Summe completed_at minus started_at abgeschlossener Importe; parallele Laufzeiten addieren sich.", period,
                value(imports, "seconds") == null ? "Keine abgeschlossenen Importläufe mit Zeitmessung im Zeitraum." : null);
        inventory(metrics, "runningImports", "processing", "Laufende Dateiimporte",
                number("SELECT COUNT(*) FROM gdelt_import_files WHERE status = 'PROCESSING'"), "Dateien",
                "Aktueller Status PROCESSING; kein historischer Status zum Zeitraumende.", now, null);
        String missing = version == null ? "Die konfigurierte sichtbare Clustering-Version ist nicht vorhanden oder nicht ACTIVE." : null;
        Double processed = version == null ? null : ProcessedArticleCounter.count(jdbc, version, from, to);
        period(metrics, "processedArticles", "processing", "In Story-Läufen gelesene Artikel", processed, "Artikel",
                "Unterschiedliche Snapshot-Artikel erfolgreicher Story-Läufe der sichtbaren Version nach completed_at; kann früher eingelesene Artikel enthalten.", period, missing);
        period(metrics, "processingThroughput", "processing", "Story-Verarbeitungsdurchsatz",
                processed == null ? null : processed / (Duration.between(period.from(), period.to()).toNanos() / 3_600_000_000_000.0), "Artikel/Stunde",
                "Unterschiedliche Snapshot-Artikel erfolgreicher Story-Läufe geteilt durch die Stunden des gewählten Zeitraums; keine reine Rechenleistung.", period, missing);
        Map<String, Object> runs = version == null ? Map.of() : jdbc.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded,
                       COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed,
                       SUM(EXTRACT(EPOCH FROM completed_at) - EXTRACT(EPOCH FROM started_at)) AS seconds
                FROM story_processing_runs WHERE clustering_version_id = ?
                    AND completed_at >= ? AND completed_at < ?
                """, version, from, to);
        period(metrics, "successfulStoryRuns", "processing", "Erfolgreiche Story-Läufe", value(runs, "succeeded"), "Läufe",
                "Läufe der sichtbaren Version mit Status SUCCEEDED nach completed_at.", period, missing);
        period(metrics, "failedStoryRuns", "processing", "Fehlgeschlagene Story-Läufe", value(runs, "failed"), "Läufe",
                "Läufe der sichtbaren Version mit Status FAILED nach completed_at; ein erneuter Lauf kann den vorherigen Status ersetzen.", period, missing);
        period(metrics, "storyDuration", "effort", "Story-Laufzeit", value(runs, "seconds"), "Sekunden",
                "Summe completed_at minus started_at abgeschlossener Läufe der sichtbaren Version, einschließlich Fehler und verworfener Läufe.", period,
                missing != null ? missing : value(runs, "seconds") == null ? "Keine abgeschlossenen Story-Läufe mit Zeitmessung im Zeitraum." : null);
        inventory(metrics, "runningStoryRuns", "processing", "Laufende Story-Läufe", version == null ? null
                        : number("SELECT COUNT(*) FROM story_processing_runs WHERE clustering_version_id = ? AND status = 'RUNNING'", version), "Läufe",
                "Aktueller Status RUNNING in der sichtbaren Version.", now, missing);
        inventory(metrics, "pendingStoryRuns", "processing", "Ausstehende Story-Läufe", version == null ? null
                        : number("SELECT COUNT(*) FROM story_processing_runs WHERE clustering_version_id = ? AND status = 'PENDING'", version), "Läufe",
                "Aktueller Status PENDING in der sichtbaren Version; keine Schätzung noch nicht erzeugter Jobs.", now, missing);
        inventory(metrics, "pendingEmbeddings", "processing", "Ausstehende Artikel-Embeddings", version == null ? null : number("""
                SELECT COUNT(*) FROM story_article_inputs WHERE clustering_version_id = ? AND current_marker = 1
                    AND (embedding_status = 'PENDING'
                         OR (embedding_status = 'RETRYABLE_FAILURE' AND attempt_count < 5))
                """, version), "Artikel-Inputs", "Aktuelle Artikel-Inputs der sichtbaren Version mit ausstehenden oder wiederholbaren Embeddings; noch nicht erzeugte Inputs sind nicht enthalten.", now, missing);
        inventory(metrics, "failedEmbeddings", "processing", "Dauerhaft fehlgeschlagene Artikel-Embeddings", version == null ? null : number("""
                SELECT COUNT(*) FROM story_article_inputs WHERE clustering_version_id = ? AND current_marker = 1
                    AND (embedding_status = 'TERMINAL_FAILURE' OR (embedding_status = 'RETRYABLE_FAILURE' AND attempt_count >= 5))
                """, version), "Artikel-Inputs", "Aktuelle Artikel-Inputs der sichtbaren Version mit terminalem Fehler oder ausgeschöpften fünf Versuchen.", now, missing);
    }

    private void runtime(List<Metric> metrics, Instant now) {
        EmbeddingUsageMetrics.Snapshot snapshot = usage.snapshot();
        metrics.add(new Metric("embeddingCalls", "effort", "Embedding-Aufrufe", (double) snapshot.calls(), "Aufrufe", "RUNTIME",
                "Vorhandener Modell-Aufrufzähler dieser Engine-Instanz seit Prozessstart; alle Clustering-Versionen und Modelle, unabhängig vom gewählten Zeitraum.", snapshot.from(), now, null));
        metrics.add(new Metric("embeddingDuration", "effort", "Embedding-Laufzeit", snapshot.durationSeconds(), "Sekunden", "RUNTIME",
                "Summe vorhandener Provider-Latenzmessungen dieser Engine-Instanz; alle Versionen und Modelle, keine historische Zeitraumabfrage.", snapshot.from(), now,
                snapshot.durationSeconds() == null ? "Noch keine abgeschlossenen Provider-Aufrufe gemessen." : null));
        metrics.add(new Metric("embeddingTokens", "effort", "Embedding-Tokenverbrauch", snapshot.tokens() == null ? null : snapshot.tokens().doubleValue(), "Token", "RUNTIME",
                "Provider usage.total_tokens einmal je HTTP-Antwort dieser Engine-Instanz. Bei fehlender Usage, Fehlern oder laufenden Aufrufen ist der Gesamtverbrauch unbekannt; keine Historie vor Prozessstart.", snapshot.from(), now,
                snapshot.tokens() == null ? "Kein vollständig bekannter Tokenverbrauch; fehlende Usage oder noch keine Messung (Aufrufe ohne Usage: " + snapshot.callsWithoutUsage() + ")." : null));
        metrics.add(new Metric("estimatedEmbeddingCost", "effort", "Geschätzte Embedding-Kosten", null, "USD", "RUNTIME",
                "Eine Kostenschätzung benötigt vollständigen Tokenverbrauch und nachvollziehbare modellbezogene Preise; es wird kein Betrag aus unvollständigen Messwerten berechnet.", snapshot.from(), now,
                "Kein modellbezogener Tarif konfiguriert; Kosten nicht verfügbar."));
    }

    private List<Run> recentImports(Period period) {
        return jdbc.query("""
                SELECT status, started_at, completed_at FROM gdelt_import_files
                WHERE started_at >= ? AND started_at < ? ORDER BY started_at DESC, id DESC LIMIT 5
                """, (rs, row) -> new Run("IMPORT", rs.getString(1), rs.getTimestamp(2).toInstant(), instant(rs.getTimestamp(3))),
                Timestamp.from(period.from()), Timestamp.from(period.to()));
    }

    private Double number(String sql, Object... parameters) {
        Number value = jdbc.queryForObject(sql, Double.class, parameters);
        return value == null ? null : value.doubleValue();
    }

    private static Double value(Map<String, Object> values, String key) {
        Number value = (Number) values.get(key);
        return value == null ? null : value.doubleValue();
    }

    private static Instant parse(String value, Instant fallback) {
        if (value == null) return fallback;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("from and to must be ISO-8601 timestamps with timezone");
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void inventory(List<Metric> metrics, String id, String group, String label, Double value,
                                  String unit, String definition, Instant now, String missing) {
        metrics.add(new Metric(id, group, label, value, unit, "INVENTORY", definition, null, now, missing));
    }

    private static void period(List<Metric> metrics, String id, String group, String label, Double value,
                               String unit, String definition, Period period, String missing) {
        metrics.add(new Metric(id, group, label, value, unit, "PERIOD", definition, period.from(), period.to(), missing));
    }
}
