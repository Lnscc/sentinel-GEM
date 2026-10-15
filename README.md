# Sentinel GEM

**Global Event Monitor**

Sentinel GEM is the web interface for exploring global news events produced by the
[Global News Engine](https://github.com/Lnscc/global-news-engine-v1). It turns the engine's
article and story data into a usable view for readers and for manually reviewing clustering
quality.

## Planned MVP

- browse current stories with pagination;
- inspect a story and its member articles;
- open original sources;
- handle loading, empty, and error states;
- work on desktop and mobile screens.

Topics, themes, authentication, editing, and administration are outside the first MVP.

## Integration

The frontend will use the engine's read-only REST API:

- `GET /stories`
- `GET /stories/{id}`

The backend remains responsible for ingestion, embeddings, clustering, publication, and version
promotion. Sentinel GEM only reads published data from the active clustering version.

## Development

The repository is initialized, but the frontend application has not been scaffolded yet. Build,
run, and test commands will be documented when the first executable frontend slice is added.
