export const periods = {
  "24h": "Letzte 24 Stunden",
  "7d": "Letzte 7 Tage",
  "30d": "Letzte 30 Tage",
  all: "Gesamter gespeicherter Bestand",
} as const;

type Period = keyof typeof periods;
export type ExploreView = "stories" | "articles";
export type ExploreFilters = { q: string; country: string; domain: string; period: Period };
export type ExploreQuery = ExploreFilters & { view: ExploreView; page: number; story: string };

export const defaultFilters: ExploreFilters = { q: "", country: "", domain: "", period: "24h" };

export function readExploreQuery(): ExploreQuery {
  const params = new URLSearchParams(window.location.search);
  const page = Number(params.get("page"));
  const period = params.get("period");
  const story = params.get("story") ?? "";

  return {
    view: params.get("view") === "articles" ? "articles" : "stories",
    page: Number.isInteger(page) && page > 0 && page <= 2_147_483_647 ? page : 1,
    period: period && Object.hasOwn(periods, period) ? period as Period : "24h",
    q: params.get("q")?.trim() ?? "",
    country: params.get("country")?.trim().toUpperCase() ?? "",
    domain: params.get("domain")?.trim() ?? "",
    story: /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(story) ? story : "",
  };
}

export function exploreSearch({ q, country, domain, period, page }: ExploreQuery) {
  const params = new URLSearchParams();
  if (q) params.set("q", q);
  if (country) params.set("country", country);
  if (domain) params.set("domain", domain);
  if (period !== "24h") params.set("period", period);
  if (page !== 1) params.set("page", String(page));
  return params;
}

export function exploreHref(query: ExploreQuery) {
  const params = exploreSearch(query);
  if (query.view !== "stories") params.set("view", query.view);
  if (query.story) params.set("story", query.story);
  const search = params.toString();
  return search ? `/explore?${search}` : "/explore";
}
