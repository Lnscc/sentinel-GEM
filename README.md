# Sentinel-GEM

**See where the world's news is happening.**

Sentinel-GEM turns global news signals into a geographic view
of real-world events: what is happening, where it is happening and which
reports belong to the same event.

The system continuously ingests
[GDELT 2.0](https://www.gdeltproject.org/) data, normalizes articles and groups
related reporting into reproducible stories. Its React monitor presents these
stories in a live global map and links them to their articles and sources.

## Current capabilities

- imports current GDELT event, mention and GKG data
- normalizes article URLs and combines their GDELT signals
- creates versioned story snapshots from title embeddings
- exposes articles, stories and pipeline health through a REST API
- presents located stories as clustered markers on an interactive world map
- filters the Lagebild by 6, 12 or 24 hours, region, text and visible map area
- opens story details with their articles and source links
- preserves filters, map bounds and the selected story in the URL and refreshes
  the story list every 60 seconds

The Lagebild loads at most the 100 stories with the most articles for the
selected period. Stories without coordinates remain available through the API
but do not appear on the map. Text search is applied to this loaded set; there
are no dedicated topic or source filters and updates are polled rather than
pushed in real time.

## Architecture

```mermaid
flowchart LR
    GDELT[GDELT 2.0] --> Engine[Spring Boot engine]
    Engine --> Database[(PostgreSQL)]
    Database --> Engine
    Engine --> API[REST API]
    API --> Monitor[React monitor]
    Monitor --> Map[Live Map and story details]
```

| Component | Technology | Purpose |
|---|---|---|
| [`engine`](engine) | Java 21, Spring Boot 4 | ingestion, processing and REST API |
| [`monitor`](monitor) | React 19, TypeScript, Vite, MapLibre GL | Live Map, filters and story details |
| PostgreSQL | PostgreSQL 17 | durable article, signal and story data |

## Quick start

You need [Docker](https://docs.docker.com/get-docker/) with Docker Compose.

1. Create your local configuration:

   ```powershell
   Copy-Item .env.example .env
   ```

2. Replace `change-me` in `.env` with a local database password. To generate
   story embeddings, also set `OPENAI_API_KEY`.

3. Start the complete system:

   ```powershell
   docker compose up --build -d
   ```

| Service | URL |
|---|---|
| Monitor | [http://localhost:5173](http://localhost:5173) |
| Engine API | [http://localhost:8080](http://localhost:8080) |

GDELT ingestion starts automatically and requires internet access. It can take
a few minutes before the first data appears.

Follow the services with `docker compose logs -f` and stop them with
`docker compose down`. Add `-v` only when you intentionally want to delete the
local database volume.

## Local development

Start only PostgreSQL from the repository root:

```powershell
docker compose up -d postgres
```

Then run the component you are working on:

```powershell
# Engine
cd engine
.\mvnw.cmd spring-boot:run

# Monitor (in a second terminal)
cd monitor
npm install
npm run dev
```

Run the checks before submitting a change:

```powershell
cd engine
.\mvnw.cmd verify

cd ..\monitor
npm run build
```

See the [engine guide](engine/README.md) and
[monitor guide](monitor/README.md) for component-specific configuration and
commands.

## Documentation

| Topic | Document |
|---|---|
| Architecture and product direction | [Platform architecture](docs/global_event_analysis_platform_architecture.md) |
| Article model and normalization | [Articles](docs/articles.md) |
| Story model and processing | [Stories](docs/stories.md) |
| Database structure | [Database overview](docs/database-overview.md) |
| Operations and troubleshooting | [Operations](docs/operations.md) |
| Ready-to-use API requests | [Postman collection](docs/postman) |

Engineering work is tracked in the
[Sentinel GEM team on Linear](https://linear.app/sentinel-gem/team/GEM).
