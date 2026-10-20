import { useEffect, useState } from "react";

type Story = {
  id: string;
  effectiveFrom: string;
  effectiveTo: string;
  representativeArticle: {
    domain: string;
    title: string;
  };
  memberCount: number;
};

type StoryPage = {
  stories: Story[];
  total: number;
};

type Sort = "time" | "articleCount";
type Direction = "asc" | "desc";

const PAGE_SIZE = 20;

const dateTime = new Intl.DateTimeFormat("de-DE", {
  dateStyle: "medium",
  timeStyle: "short",
});

function StoryCard({ story }: { story: Story }) {
  return (
    <article className="story-card">
      <p className="domain">{story.representativeArticle.domain}</p>
      <h2>{story.representativeArticle.title}</h2>
      <dl>
        <div>
          <dt>Zeitraum</dt>
          <dd>
            <time dateTime={story.effectiveFrom}>{dateTime.format(new Date(story.effectiveFrom))}</time>
            <span aria-hidden="true"> – </span>
            <time dateTime={story.effectiveTo}>{dateTime.format(new Date(story.effectiveTo))}</time>
          </dd>
        </div>
        <div>
          <dt>Artikel</dt>
          <dd>{story.memberCount}</dd>
        </div>
      </dl>
    </article>
  );
}

export default function App() {
  const [page, setPage] = useState<StoryPage>();
  const [error, setError] = useState<string>();
  const [offset, setOffset] = useState(0);
  const [sort, setSort] = useState<Sort>("time");
  const [direction, setDirection] = useState<Direction>("desc");

  useEffect(() => {
    const controller = new AbortController();
    const query = new URLSearchParams({
      offset: String(offset),
      limit: String(PAGE_SIZE),
      sort,
      direction,
    });

    setPage(undefined);
    setError(undefined);

    fetch(`/api/stories?${query}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<StoryPage>;
      })
      .then(setPage)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
        }
      });

    return () => controller.abort();
  }, [offset, sort, direction]);

  const changeSort = (next: Sort) => {
    setSort(next);
    setOffset(0);
  };

  const changeDirection = (next: Direction) => {
    setDirection(next);
    setOffset(0);
  };

  return (
    <main>
      <header>
        <p className="eyebrow">Sentinel GEM</p>
        <h1>Aktuelle Stories</h1>
        <p className="intro">Nachrichtenereignisse aus verschiedenen Quellen, gebündelt im Überblick.</p>
      </header>

      <section className="toolbar" aria-label="Story-Sortierung">
        <label>
          Sortieren nach
          <select value={sort} onChange={(event) => changeSort(event.target.value as Sort)}>
            <option value="time">Zeitpunkt</option>
            <option value="articleCount">Artikelanzahl</option>
          </select>
        </label>
        <label>
          Reihenfolge
          <select
            value={direction}
            onChange={(event) => changeDirection(event.target.value as Direction)}
          >
            <option value="desc">Absteigend</option>
            <option value="asc">Aufsteigend</option>
          </select>
        </label>
      </section>

      {!page && !error && <p className="state" role="status">Stories werden geladen …</p>}
      {error && (
        <p className="state error" role="alert">
          Die Stories konnten nicht geladen werden. <span>{error}</span>
        </p>
      )}
      {page?.stories.length === 0 && <p className="state">Derzeit sind keine Stories verfügbar.</p>}
      {page && page.stories.length > 0 && (
        <section aria-label="Story-Liste">
          <p className="result-count">{page.total} Stories insgesamt</p>
          <div className="story-list">
            {page.stories.map((story) => <StoryCard key={story.id} story={story} />)}
          </div>
          <nav className="pagination" aria-label="Story-Seiten">
            <button
              type="button"
              disabled={offset === 0}
              onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
            >
              Zurück
            </button>
            <p>
              Seite {offset / PAGE_SIZE + 1} von {Math.ceil(page.total / PAGE_SIZE)}
            </p>
            <button
              type="button"
              disabled={offset + PAGE_SIZE >= page.total}
              onClick={() => setOffset(offset + PAGE_SIZE)}
            >
              Weiter
            </button>
          </nav>
        </section>
      )}
    </main>
  );
}
