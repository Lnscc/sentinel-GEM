# Sentinel GEM

**Global Event Monitor**

Sentinel GEM is the web interface for exploring global news events produced by the
[Global News Engine](https://github.com/Lnscc/global-news-engine-v1). It turns the engine's
article and story data into a usable view for readers and for manually reviewing clustering
quality.

## Planned MVP

- explore current stories on a 2D map using coordinates supplied by the engine;
- inspect a selected story and its member articles;
- browse loaded stories in an accessible list;
- open original sources;
- handle loading, empty, and error states;
- work on desktop and mobile screens.

Topics, themes, authentication, editing, and administration are outside the first MVP.

## Integration

The frontend will use the engine's read-only REST API:

- `GET /stories`
- `GET /stories/{id}`

The backend remains responsible for ingestion, embeddings, clustering, publication, and version
promotion, including the geographic assignment exposed as `lng` and `lat`. Sentinel GEM only
reads published data from the active clustering version.

The planned frontend uses Vite, React, TypeScript, MUI, and MapLibre GL. Stories are loaded for the
visible map bounds and zoom level. The engine filters out stories without a location and applies
both the zoom-dependent importance threshold and importance-dependent freshness windows. Application
styling stays in the MUI theme and `sx`; MapLibre may load its required base stylesheet, but there
are no custom CSS files.
The frontend and API are served from the same domain, with the engine available under `/api`.

## Development

The repository is initialized, but the frontend application has not been scaffolded yet. Build,
run, and test commands will be documented when the first executable frontend slice is added.
The proposed architecture is documented in [`docs/project-overview.md`](docs/project-overview.md).
