# News Explorer API

The monitor uses these read-only endpoints from `/explore` to search the stored news corpus. Requests are independent of the Live Map filters.

| Endpoint | Result |
| --- | --- |
| `GET /explore/stories` | Page of current stories in the configured visible clustering version |
| `GET /explore/articles` | Page of stored articles, including articles without a story |
| `GET /explore/countries?view=stories\|articles` | Array of `{code, name}` options for the requested view |

Story details use the existing `GET /stories/{id}` endpoint.

## Search parameters

Both page endpoints accept the same optional parameters.

| Parameter | Meaning |
| --- | --- |
| `q` | Case-insensitive literal substring, trimmed at both ends. Story search covers the representative title and domain and primary location. Article search covers the title, URL, domain and normalized GKG place names. |
| `period` | `24h` (default), `7d`, `30d` or `all`. Relative periods use server time. A story matches if its known time interval overlaps the period. An article matches by first-seen time. |
| `country` | Exact country code from the view's country options. Stories use the primary location. Articles use countries mentioned in normalized GKG locations associated with the article. |
| `domain` | Exact domain. Stories match any current member article on that domain. Articles match their own domain. |
| `page` | Positive page number, starting at 1. Pages contain up to 50 results. |

Filters combine with AND. Results sort by the end of the known story interval or the first-seen time of the article, newest first, with a stable identity as a tie-breaker.

## Response

Page responses contain `items`, `total` (filtered result count), `page`, `pageSize` (50), `availableFrom` and `availableTo`. The bounds describe the eligible stored corpus for that view, independent of the search filters. An empty corpus has null bounds. Requesting a page beyond the last result returns an empty `items` array with the matching total and requested page number.

- Story items contain `id`, `title`, `domain`, `startedAt`, `lastSeenAt`, `articleCount`, `locationName` and `countryCode`.
- Article items contain `id`, `title`, `url`, `domain`, `firstSeenAt`, `mentionedCountries` (an array of `{code, name}`) and `storyId` (the current visible story, when available).
- Dates use ISO 8601. Metadata can be null when missing. Article countries identify mentions and do not establish where an event occurred.

Invalid parameter values return HTTP 400 with `{"code":"invalid_request","message":"…"}`. Import [the Explore Postman collection](postman/Explore-API.postman_collection.json) and [the local environment](postman/Local.postman_environment.json) to check the contract against a running engine.
