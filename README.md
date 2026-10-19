# Sentinel-GEM

Sentinel-GEM collects and processes global news data and makes the results available in a web monitor.

```text
monitor -> engine -> PostgreSQL
```

## Components

- [`engine`](engine) — Java and Spring Boot backend
- [`monitor`](monitor) — React frontend (not initialized yet)
- [`docs`](docs) — architecture, operations, analysis, and local tickets
- [`compose.yaml`](compose.yaml) — shared local infrastructure

## Run the engine locally

Start PostgreSQL from the repository root:

```powershell
docker compose up -d
```

Start the engine:

```powershell
cd engine
.\mvnw.cmd spring-boot:run
```

The API is available at `http://localhost:8080`.

Run all engine tests with PostgreSQL running:

```powershell
cd engine
.\mvnw.cmd verify
```

See the [engine README](engine/README.md) for backend details.
