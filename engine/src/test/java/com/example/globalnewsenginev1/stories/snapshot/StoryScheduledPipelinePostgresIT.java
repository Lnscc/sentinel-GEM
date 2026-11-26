package com.example.globalnewsenginev1.stories.snapshot;

import com.example.globalnewsenginev1.GlobalNewsEngineV1Application;
import com.example.globalnewsenginev1.gdelt.staging.parser.GdeltParserTests;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Functional scheduler coverage; neither a 107k load test nor a VPS performance qualification. */
public class StoryScheduledPipelinePostgresIT {
    private static final String VERSION = "story-mvp-title-embedding-24h-v1.1.0";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, byte[]> downloads = new ConcurrentHashMap<>();
    private final List<String> manifest = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> embedded = ConcurrentHashMap.newKeySet();
    private final AtomicInteger duplicateEmbeddings = new AtomicInteger();
    private final AtomicInteger providerRequests = new AtomicInteger();
    private final AtomicInteger manifestPolls = new AtomicInteger();
    private final List<String> fixtureFailures = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("it.pipeline.enabled")) throw new IllegalArgumentException("Explicit pipeline opt-in required");
        new StoryScheduledPipelinePostgresIT().run();
    }

    @Test
    void downloadsThroughScheduledPublicationWithoutDuplicateEmbeddings() throws Exception {
        assumeTrue(Boolean.getBoolean("it.pipeline.enabled"), "Explicit local scheduler test opt-in required");
        run();
    }

    private void run() throws Exception {
        String url = System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:55424/gem124");
        if (!url.matches("jdbc:postgresql://(?:localhost:55424|127\\.0\\.0\\.1:55424|postgres:5432)/gem124")) {
            throw new IllegalArgumentException("Only the dedicated GEM-124 database is allowed");
        }
        int batch = Integer.getInteger("it.pipeline.articles-per-release", 1000);
        assertThat(batch).isBetween(1, 1000);
        Path output = Path.of(System.getProperty("it.pipeline.output", "target/gem124-benchmark/scheduled-pipeline.json"));
        if (Files.exists(output)) throw new IllegalArgumentException("Preserve evidence; choose a fresh it.pipeline.output");
        String schema = "scheduled_" + UUID.randomUUID().toString().replace("-", "");
        var source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(System.getProperty("it.postgres.username", "gem124"));
        source.setPassword(System.getProperty("it.postgres.password", "gem124-disposable-local-test"));
        var admin = new JdbcTemplate(source);
        admin.execute("CREATE SCHEMA " + schema);
        var scoped = new PGSimpleDataSource();
        scoped.setUrl(url);
        scoped.setUser(source.getUser());
        scoped.setPassword(source.getPassword());
        scoped.setCurrentSchema(schema);
        var jdbc = new JdbcTemplate(scoped);
        var report = new LinkedHashMap<String, Object>();
        var steps = new ArrayList<Map<String, Object>>();
        report.put("scope", "Functional real scheduled pipeline, synthetic HTTP fixtures; accelerated one-second polling");
        report.put("vpsAcceptancePassed", false);
        report.put("productionProviderLatencyMeasured", false);
        report.put("startedUtc", Instant.now().toString());
        report.put("articlesPerRelease", batch);
        report.put("releases", steps);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                byte[] body;
                if (path.equals("/manifest")) {
                    manifestPolls.incrementAndGet();
                    synchronized (manifest) { body = String.join("\n", manifest).getBytes(StandardCharsets.UTF_8); }
                } else if (path.equals("/v1/embeddings")) {
                    var request = json.readTree(exchange.getRequestBody());
                    assertThat(request.path("dimensions").asInt()).isEqualTo(1536);
                    var data = new ArrayList<Map<String, Object>>();
                    for (var input : request.path("input")) {
                        String title = input.asText();
                        if (!embedded.add(title)) duplicateEmbeddings.incrementAndGet();
                        int id = Integer.parseInt(title.substring(title.lastIndexOf(' ') + 1));
                        var random = new Random((id - 1) / 4 + 124L);
                        float[] vector = new float[1536];
                        for (int i = 0; i < vector.length; i++) vector[i] = random.nextFloat() * 2 - 1;
                        data.add(Map.of("index", data.size(), "embedding", vector));
                    }
                    providerRequests.incrementAndGet();
                    body = json.writeValueAsBytes(Map.of("data", data, "usage", Map.of("total_tokens", data.size() * 12)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                } else {
                    body = downloads.get(path);
                    if (body == null) { exchange.sendResponseHeaders(404, -1); return; }
                }
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Throwable failure) {
                fixtureFailures.add(failure.toString());
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        try {
            Flyway.configure().dataSource(scoped).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).load().migrate();
            jdbc.execute("ALTER TABLE story_clustering_versions DISABLE TRIGGER USER");
            jdbc.update("UPDATE story_clustering_versions SET status = CASE WHEN version_key = ? THEN 'ACTIVE' ELSE 'SHADOW' END", VERSION);
            jdbc.execute("ALTER TABLE story_clustering_versions ENABLE TRIGGER USER");
            server.start();
            try (var app = new SpringApplicationBuilder(GlobalNewsEngineV1Application.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.docker.compose.enabled=false",
                    "--spring.datasource.url=" + scoped.getUrl(), "--spring.datasource.username=" + scoped.getUser(),
                    "--spring.datasource.password=" + scoped.getPassword(), "--spring.jpa.hibernate.ddl-auto=none",
                    "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                    "--stories.visible-version=" + VERSION, "--gdelt.manifest-url=" + base + "/manifest",
                    "--gdelt.download-base-url=" + base + "/gdeltv2", "--gdelt.retention.enabled=false",
                    "--gdelt.ingestion.enabled=true", "--gdelt.ingestion.initial-delay=PT1S", "--gdelt.ingestion.poll-interval=PT1S",
                    "--gdelt.staging.enabled=true", "--gdelt.staging.initial-delay=PT1S", "--gdelt.staging.poll-interval=PT1S",
                    "--articles.enabled=true", "--articles.initial-delay=PT1S", "--articles.poll-interval=PT1S",
                    "--stories.embeddings.incremental.enabled=true", "--stories.embeddings.incremental.initial-delay=PT1S",
                    "--stories.embeddings.incremental.poll-interval=PT1S", "--stories.embeddings.api-key=local-disposable-fixture",
                    "--stories.embeddings.base-url=" + base + "/v1", "--stories.embeddings.repair.enabled=false",
                    "--stories.snapshots.incremental.enabled=true", "--stories.snapshots.incremental.initial-delay=PT1S",
                    "--stories.snapshots.incremental.poll-interval=PT1S", "--stories.snapshots.backfill.enabled=false",
                    "--stories.snapshots.retention.enabled=true"); var client = HttpClient.newHttpClient()) {
                int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
                Instant windowStart = Instant.now().minusSeconds(3600);
                for (int release = 1; release <= 3; release++) {
                    long started = System.nanoTime();
                    release(base, windowStart.plusSeconds(release * 900L), (release - 1) * batch + 1, batch);
                    long expected = (long) release * batch;
                    long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
                    long members;
                    do {
                        assertThat(fixtureFailures).isEmpty();
                        members = count(jdbc, "SELECT COUNT(*) FROM story_memberships WHERE current_marker = 1");
                        if (members >= expected) break;
                        Thread.sleep(200);
                    } while (System.nanoTime() < deadline);
                    assertThat(members).as("Published articles in release %s", release).isEqualTo(expected);
                    assertThat(count(jdbc, "SELECT COUNT(*) FROM story_article_inputs WHERE current_marker=1 AND embedding_status='READY'")).isEqualTo(expected);
                    var latencies = new LinkedHashMap<String, Object>();
                    for (String path : List.of("/articles?limit=20", "/stories?limit=20", "/statistics")) {
                        long requestStart = System.nanoTime();
                        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode()).as(path).isEqualTo(200);
                        latencies.put(path, (System.nanoTime() - requestStart) / 1_000_000);
                        if (path.startsWith("/stories")) assertThat(json.readTree(response.body()).path("stories").size()).isPositive();
                        if (path.startsWith("/articles")) assertThat(json.readTree(response.body()).path("total").asLong()).isEqualTo(expected);
                    }
                    String newestUrl = "https://scheduled-fixture.example/news/" + expected;
                    UUID publicId = jdbc.queryForObject("""
                            SELECT story.public_id FROM stories story JOIN story_memberships member ON member.story_id=story.id
                            JOIN articles article ON article.id=member.article_id
                            WHERE member.current_marker=1 AND article.canonical_url=?
                            """, UUID.class, newestUrl);
                    var detail = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stories/" + publicId))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(detail.statusCode()).isEqualTo(200);
                    assertThat(detail.body()).contains(newestUrl);
                    steps.add(Map.of("release", release, "members", members, "releaseToVisibleMs", (System.nanoTime() - started) / 1_000_000,
                            "apiLatencyMs", latencies, "embeddedUniqueTitles", embedded.size()));
                }
                int pollsBefore = manifestPolls.get();
                Thread.sleep(3000); // More scheduled polls must reuse completed downloads and embedding artifacts.
                assertThat(manifestPolls.get()).isGreaterThan(pollsBefore);
                assertThat(embedded).hasSize(3 * batch);
                assertThat(duplicateEmbeddings.get()).isZero();
                assertThat(fixtureFailures).isEmpty();
                assertThat(count(jdbc, "SELECT COUNT(*) FROM gdelt_import_files WHERE status='COMPLETED'")).isEqualTo(9);
                assertThat(count(jdbc, "SELECT COUNT(*) FROM gdelt_processing_errors")).isZero();
                assertThat(count(jdbc, "SELECT COUNT(*) FROM article_extraction_errors")).isZero();
                for (String table : List.of("gdelt_events", "gdelt_mentions", "gdelt_gkg")) {
                    assertThat(count(jdbc, "SELECT COUNT(*) FROM " + table)).as(table).isEqualTo(3L * batch);
                    assertThat(count(jdbc, "SELECT COUNT(*) FROM " + table + " WHERE article_id IS NULL")).as(table).isZero();
                }
                for (String table : List.of("articles", "story_embedding_artifacts")) {
                    assertThat(count(jdbc, "SELECT COUNT(*) FROM " + table)).as(table).isEqualTo(3L * batch);
                }
                report.put("linkedSignals", 9L * batch); // Each of the three domain tables was checked above.
                assertThat(count(jdbc, "SELECT COUNT(*) FROM story_memberships WHERE current_marker=1")).isEqualTo(3L * batch);
                assertThat(count(jdbc, "SELECT COUNT(*) FROM story_article_inputs WHERE current_marker=1")).isEqualTo(3L * batch);
                assertThat(count(jdbc, "SELECT COUNT(*) FROM story_processing_runs WHERE status='FAILED'")).isZero();
                assertThat(count(jdbc, "SELECT COUNT(*) FROM (SELECT article_ref FROM story_memberships WHERE current_marker=1 GROUP BY article_ref HAVING COUNT(*)<>1) duplicates")).isZero();
                report.put("schedulerBeans", app.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                        .entrySet().stream().map(e -> Map.of("name", e.getKey(), "poolSize", e.getValue().getPoolSize())).toList());
                report.put("providerRequests", providerRequests.get());
                report.put("manifestPolls", manifestPolls.get());
                report.put("completedFiles", count(jdbc, "SELECT COUNT(*) FROM gdelt_import_files WHERE status='COMPLETED'"));
                report.put("passed", true);
            }
        } catch (Throwable failure) {
            report.put("passed", false);
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            server.stop(0);
            report.put("fixtureFailures", fixtureFailures);
            report.put("duplicateEmbeddingInputs", duplicateEmbeddings.get());
            report.put("completedUtc", Instant.now().toString());
            try {
                Files.createDirectories(output.toAbsolutePath().getParent());
                json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    private void release(String base, Instant at, int first, int count) throws Exception {
        String stamp = STAMP.format(at);
        var rows = new LinkedHashMap<String, List<String>>();
        rows.put("export.CSV.zip", new ArrayList<>());
        rows.put("mentions.CSV.zip", new ArrayList<>());
        rows.put("gkg.csv.zip", new ArrayList<>());
        String published = STAMP.format(Instant.now().minusSeconds(30));
        for (int id = first; id < first + count; id++) {
            String url = "https://scheduled-fixture.example/news/" + id;
            String[] event = GdeltParserTests.eventRow().split("\t", -1);
            event[0] = Integer.toString(id); event[1] = stamp.substring(0, 8); event[60] = url;
            rows.get("export.CSV.zip").add(String.join("\t", event));
            String[] mention = GdeltParserTests.mentionRow().split("\t", -1);
            mention[0] = Integer.toString(id); mention[1] = stamp; mention[2] = stamp; mention[5] = url;
            rows.get("mentions.CSV.zip").add(String.join("\t", mention));
            String[] gkg = GdeltParserTests.gkgRow().split("\t", -1);
            gkg[0] = stamp + "-" + id; gkg[1] = stamp; gkg[4] = url;
            gkg[26] = "<PAGE_PRECISEPUBTIMESTAMP>" + published + "</PAGE_PRECISEPUBTIMESTAMP>"
                    + "<PAGE_TITLE>Storm damage forces residents from homes in district " + id + "</PAGE_TITLE>";
            rows.get("gkg.csv.zip").add(String.join("\t", gkg));
        }
        var additions = new ArrayList<String>();
        for (var dataset : rows.entrySet()) {
            String path = "/gdeltv2/" + stamp + "." + dataset.getKey();
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("data.tsv"));
                zip.write((String.join("\n", dataset.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            downloads.put(path, bytes.toByteArray());
            additions.add(bytes.size() + " 00000000000000000000000000000000 " + base + path);
        }
        synchronized (manifest) { manifest.addAll(additions); }
    }

    private static long count(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Long.class); }
}
