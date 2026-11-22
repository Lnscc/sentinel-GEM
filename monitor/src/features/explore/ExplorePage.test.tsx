import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import ExplorePage from "./ExplorePage";

const from = "2026-11-01T10:00:00Z";
const to = "2026-11-22T10:00:00Z";
const storyId = "b38100c4-9d53-44fb-9d67-0e6fcb360db3";
const country = { code: "GM", name: "Deutschland" };
const article = {
  id: 1, title: null, url: "https://example.com/meldung", domain: "example.com", firstSeenAt: from,
  mentionedCountries: [country], storyId,
};

function respond(value: unknown, status = 200) {
  return Promise.resolve({ ok: status === 200, status, json: () => Promise.resolve(value) } as Response);
}

function page(items: unknown[], total: number, page = 1) {
  return { items, total, page, pageSize: 50, availableFrom: from, availableTo: to };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

it("restores shared filters and sends changed criteria to the first page", async () => {
  window.history.replaceState(null, "", "/explore?view=articles&period=all&country=GM&page=2");
  const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
    const url = new URL(String(input), window.location.origin);
    if (url.pathname.endsWith("/countries")) return respond([country]);
    if (url.searchParams.has("q")) return respond(page([], 0));
    return respond(page([article], 54, 2));
  });
  const user = userEvent.setup();
  render(<ExplorePage />);

  expect(await screen.findByRole("heading", { name: article.url })).toBeVisible();
  expect(screen.getByRole("button", { name: "Artikel" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveValue("all");
  expect(screen.getByRole("combobox", { name: "Erwähntes Land" })).toHaveValue("GM");
  expect(screen.getByRole("link", { name: "Originalquelle öffnen" })).toHaveAttribute("href", article.url);
  expect(screen.getByText("Erwähnte Länder: Deutschland")).toBeVisible();
  expect(screen.getByText(/Gespeicherter Bestand \(Ersterfassung\)/)).toBeVisible();

  await user.type(screen.getByRole("searchbox", { name: "Suchtext" }), "  25%_  ");
  await user.type(screen.getByRole("textbox", { name: "Quellendomain" }), "example.com");
  await user.click(screen.getByRole("button", { name: "Suchen" }));
  expect(await screen.findByText("Keine Treffer für diese Filter.")).toBeVisible();
  expect(fetchMock).toHaveBeenCalledWith("/api/explore/articles?q=25%25_&country=GM&domain=example.com&period=all", expect.anything());
  expect(new URLSearchParams(window.location.search).has("page")).toBe(false);
});

it("keeps the research URL and restores result focus after visiting a linked story", async () => {
  window.history.replaceState(null, "", "/explore?view=articles&page=2");
  const source = { id: 1, title: "Quellartikel", canonicalUrl: article.url, domain: article.domain, firstSeenAt: from };
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
    const path = new URL(String(input), window.location.origin).pathname;
    if (path.endsWith("/countries")) return respond([country]);
    if (path.endsWith(storyId)) return respond({
      id: storyId, state: "PUBLISHED", effectiveFrom: from, effectiveTo: to,
      representativeArticle: source, members: [{ article: source }],
    });
    return respond(page([article], 54, 2));
  });
  const user = userEvent.setup();
  render(<ExplorePage />);

  const storyLink = await screen.findByRole("link", { name: "Zugehörige Story öffnen" });
  const main = screen.getByRole("main");
  main.scrollTop = 250;
  await user.click(storyLink);
  expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeVisible();
  expect(screen.getByRole("link", { name: /Quellartikel: externe Quelle/ })).toHaveAttribute("href", article.url);
  expect(new URLSearchParams(window.location.search).get("story")).toBe(storyId);
  await user.click(screen.getByRole("button", { name: /Zurück zu den Suchergebnissen/ }));
  const restored = await screen.findByRole("link", { name: "Zugehörige Story öffnen" });
  await waitFor(() => expect(restored).toHaveFocus());
  expect(main.scrollTop).toBe(250);
  expect(window.location.search).toBe("?view=articles&page=2");
});

it("shows a load error with retry and a recovery action for an out-of-range page", async () => {
  window.history.replaceState(null, "", "/explore?page=9");
  let fail = true;
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
    const url = new URL(String(input), window.location.origin);
    if (url.pathname.endsWith("/countries")) return respond([]);
    if (fail) {
      fail = false;
      return respond({}, 503);
    }
    return respond(page([], 0, Number(url.searchParams.get("page") ?? "1")));
  });
  const user = userEvent.setup();
  render(<ExplorePage />);

  expect(screen.getByText("Suchergebnisse werden geladen …")).toBeVisible();
  expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 503");
  await user.click(screen.getByRole("button", { name: "Erneut laden" }));
  expect(await screen.findByText("Seite 9 liegt außerhalb der verfügbaren Treffer.")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Zur ersten Seite" }));
  expect(await screen.findByText("Keine Treffer für diese Filter.")).toBeVisible();
  expect(window.location.search).toBe("");
});
