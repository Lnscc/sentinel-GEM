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

  useEffect(() => {
    const controller = new AbortController();

    fetch("/api/stories?offset=0&limit=20", { signal: controller.signal })
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
  }, []);

  return (
    <main>
      <header>
        <p className="eyebrow">Sentinel GEM</p>
        <h1>Aktuelle Stories</h1>
        <p className="intro">Nachrichtenereignisse aus verschiedenen Quellen, gebündelt im Überblick.</p>
      </header>

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
        </section>
      )}
    </main>
  );
}
