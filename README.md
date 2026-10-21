# Sentinel-GEM

Sentinel-GEM collects and processes global news data and makes the results available in a web monitor.

```text
monitor -> engine -> PostgreSQL
```

## Components

- [`engine`](engine) — Java and Spring Boot backend
- [`monitor`](monitor) — React frontend for the story overview
- [`docs`](docs) — architecture, operations, and technical analysis
- [`compose.yaml`](compose.yaml) — shared local infrastructure

Work is tracked in the [Sentinel GEM team on Linear](https://linear.app/sentinel-gem/team/GEM).

## Run the complete system in containers

Create the local runtime configuration once:

```powershell
Copy-Item .env.example .env
```

Set a local database password in `.env`, then start PostgreSQL, the engine, and
the monitor:

```powershell
docker compose up --build -d
```

Open the monitor at `http://localhost:5173`. The engine API remains available at
`http://localhost:8080`.

Stop the system with `docker compose down`. Add `-v` only when you intentionally
want to remove the local database volume.

## Run the engine locally

Start PostgreSQL from the repository root:

```powershell
docker compose up -d postgres
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

## Run the monitor locally

With the engine running:

```powershell
cd monitor
npm install
npm run dev
```

See the [monitor README](monitor/README.md) for build and configuration details.
