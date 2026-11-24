import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal-network API measurement only; response bodies are counted and never saved. */
public final class ApiProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: ApiProbe http://gem122-local-{before|after}:8080 output-directory [seconds]");
        }
        URI base = URI.create(args[0]);
        if (!"http".equals(base.getScheme()) || base.getPort() != 8080
                || !base.getHost().matches("gem122-local-(before|after)")) {
            throw new IllegalArgumentException("Probe target must be the isolated local benchmark engine");
        }
        Path directory = Path.of(args[1]);
        long seconds = args.length == 3 ? Long.parseLong(args[2]) : 3600;
        if (seconds <= 0 || seconds > 3600) throw new IllegalArgumentException("Duration must be 1..3600 seconds");
        Files.createDirectories(directory);
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        AtomicBoolean ready = new AtomicBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).build();
             BufferedWriter csv = Files.newBufferedWriter(directory.resolve("api.csv"), StandardCharsets.UTF_8)) {
            csv.write("utc,endpoint,status,milliseconds,bytes,error\n");
            csv.flush();
            var articles = workers.submit(() -> {
                poll(client, base, "articles", "?offset=0&limit=20", directory, deadline, ready, csv);
                return null;
            });
            var stories = workers.submit(() -> {
                poll(client, base, "stories", "?offset=0&limit=20&sort=time&direction=desc",
                        directory, deadline, ready, csv);
                return null;
            });
            try {
                articles.get();
                stories.get();
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(6, TimeUnit.SECONDS);
            }
        }
        Files.writeString(directory.resolve("api.finished"), Instant.now() + "\n");
    }

    private static void poll(HttpClient client, URI base, String endpoint, String query, Path directory,
                             long deadline, AtomicBoolean ready, BufferedWriter csv) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/" + endpoint + query))
                .timeout(Duration.ofSeconds(5)).GET().build();
        while (!Files.exists(directory.resolve("api.stop")) && System.nanoTime() < deadline
                && !Thread.currentThread().isInterrupted()) {
            long started = System.nanoTime();
            int status = 0;
            long bytes = 0;
            String error = "";
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                status = response.statusCode();
                bytes = response.body().length;
            } catch (Exception exception) {
                error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            synchronized (csv) {
                csv.write(row(Instant.now(), endpoint, status, elapsedMs, bytes, error));
                csv.flush();
            }
            if ("articles".equals(endpoint) && status == 200 && ready.compareAndSet(false, true)) {
                Files.writeString(directory.resolve("api.ready"), Instant.now() + "\n");
            }
            long remaining = Math.min(Duration.ofSeconds(1).toNanos() - (System.nanoTime() - started),
                    deadline - System.nanoTime());
            if (remaining > 0 && !Thread.currentThread().isInterrupted()) TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static String row(Object... values) {
        StringBuilder line = new StringBuilder();
        for (Object value : values) {
            if (!line.isEmpty()) line.append(',');
            line.append('"').append(String.valueOf(value).replace("\"", "\"\"")).append('"');
        }
        return line.append('\n').toString();
    }
}
