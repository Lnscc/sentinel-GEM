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

type Article = {
  id: number;
  canonicalUrl: string;
  domain: string;
  firstSeenAt: string;
  title: string;
  publishedAt: string | null;
  mainImageUrl: string | null;
};

type StoryDetail = {
  id: string;
  state: string;
  effectiveFrom: string;
  effectiveTo: string;
  representativeArticle: Article;
  members: {
    article: Article;
    assignmentReason: string;
  }[];
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
    <a className="story-card" href={`#${story.id}`}>
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
      <span className="open-story">Story öffnen <span aria-hidden="true">→</span></span>
    </a>
  );
}

function StoryDetails({ id }: { id: string }) {
  const [story, setStory] = useState<StoryDetail>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    const controller = new AbortController();

    setStory(undefined);
    setError(undefined);

    fetch(`/api/stories/${encodeURIComponent(id)}`, { signal: controller.signal })
      .then((response) => {
        if (response.status === 404) throw new Error("Diese Story wurde nicht gefunden.");
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<StoryDetail>;
      })
      .then(setStory)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
        }
      });

    return () => controller.abort();
  }, [id]);

  return (
    <>
      <a className="back-link" href="#">← Zurück zur Übersicht</a>
      {!story && !error && <p className="state" role="status">Story wird geladen …</p>}
      {error && (
        <p className="state error" role="alert">
          Die Story konnte nicht geladen werden. <span>{error}</span>
        </p>
      )}
      {story && (
        <article className="story-detail">
          <header className="detail-header">
            <p className="eyebrow">Story · {story.state}</p>
            <h1>{story.representativeArticle.title}</h1>
            <p className="intro">Repräsentativer Artikel von {story.representativeArticle.domain}</p>
          </header>

          <dl className="detail-meta">
            <div>
              <dt>Zeitraum</dt>
              <dd>
                <time dateTime={story.effectiveFrom}>{dateTime.format(new Date(story.effectiveFrom))}</time>
                <span aria-hidden="true"> – </span>
                <time dateTime={story.effectiveTo}>{dateTime.format(new Date(story.effectiveTo))}</time>
              </dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>{story.state}</dd>
            </div>
            <div>
              <dt>Artikel</dt>
              <dd>{story.members.length}</dd>
            </div>
          </dl>

          <section aria-labelledby="articles-heading">
            <h2 id="articles-heading">Zugehörige Artikel</h2>
            <div className="article-list">
              {story.members.map(({ article }) => (
                <article className="article-card" key={article.id}>
                  <p className="domain">{article.domain}</p>
                  <h3>{article.title}</h3>
                  <p>
                    <time dateTime={article.publishedAt ?? article.firstSeenAt}>
                      {dateTime.format(new Date(article.publishedAt ?? article.firstSeenAt))}
                    </time>
                  </p>
                </article>
              ))}
            </div>
          </section>
        </article>
      )}
    </>
  );
}

export default function App() {
  const [selectedStoryId, setSelectedStoryId] = useState(() => window.location.hash.slice(1));
  const [page, setPage] = useState<StoryPage>();
  const [error, setError] = useState<string>();
  const [offset, setOffset] = useState(0);
  const [sort, setSort] = useState<Sort>("time");
  const [direction, setDirection] = useState<Direction>("desc");

  useEffect(() => {
    const selectStory = () => setSelectedStoryId(window.location.hash.slice(1));
    window.addEventListener("hashchange", selectStory);
    return () => window.removeEventListener("hashchange", selectStory);
  }, []);

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

  if (selectedStoryId) {
    return <main><StoryDetails id={selectedStoryId} /></main>;
  }

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
