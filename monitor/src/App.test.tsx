import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider, createTheme } from "@mui/material";
import App from "./App";

const story = {
  id: "story-1",
  effectiveFrom: "2026-10-21T10:00:00Z",
  effectiveTo: "2026-10-21T11:00:00Z",
  representativeArticle: { domain: "example.com", title: "Eine wichtige Story" },
  memberCount: 2,
};

const jsonResponse = (body: unknown, ok = true) => Promise.resolve({
  ok,
  status: ok ? 200 : 500,
  json: () => Promise.resolve(body),
} as Response);

const renderApp = () => render(<ThemeProvider theme={createTheme()}><App /></ThemeProvider>);

describe("App", () => {
  beforeEach(() => {
    window.location.hash = "";
    vi.restoreAllMocks();
  });

  afterEach(cleanup);

  it("shows the overview and loads the next page", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => jsonResponse({ stories: [story], total: 21 }));
    const user = userEvent.setup();
    renderApp();

    expect(await screen.findByRole("heading", { name: "Eine wichtige Story" })).toBeInTheDocument();
    expect(screen.getByText("21 Stories insgesamt")).toBeInTheDocument();
    await user.click(screen.getByLabelText("Sortieren nach"));
    await user.click(screen.getByRole("option", { name: "Artikelanzahl" }));
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith(expect.stringContaining("sort=articleCount"), expect.anything()));
    await user.click(screen.getByRole("button", { name: "Weiter" }));
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith(expect.stringMatching(/offset=20.*sort=articleCount/), expect.anything()));
  });

  it("shows the detail view", async () => {
    window.location.hash = "story-1";
    vi.spyOn(globalThis, "fetch").mockImplementation(() => jsonResponse({
      ...story,
      state: "PUBLISHED",
      representativeArticle: {
        ...story.representativeArticle,
        id: 1,
        canonicalUrl: "https://example.com/1",
        firstSeenAt: story.effectiveFrom,
        publishedAt: null,
        mainImageUrl: null,
      },
      members: [{
        assignmentReason: "representative",
        article: {
          id: 1,
          canonicalUrl: "https://example.com/1",
          domain: "example.com",
          firstSeenAt: story.effectiveFrom,
          title: "Artikel eins",
          publishedAt: null,
          mainImageUrl: null,
        },
      }],
    }));
    renderApp();

    expect(await screen.findByRole("heading", { name: "Eine wichtige Story" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    expect(screen.getByText("Artikel eins")).toBeInTheDocument();
  });

  it("shows loading and empty states", async () => {
    let resolveFetch!: (response: Response) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise((resolve) => { resolveFetch = resolve; }));
    renderApp();

    expect(screen.getByRole("status")).toHaveTextContent("Stories werden geladen");
    resolveFetch(await jsonResponse({ stories: [], total: 0 }));
    expect(await screen.findByText("Derzeit sind keine Stories verfügbar.")).toBeInTheDocument();
  });

  it("shows an error state", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(() => jsonResponse({}, false));
    renderApp();

    expect(await screen.findByRole("alert")).toHaveTextContent("Die Stories konnten nicht geladen werden. HTTP 500");
  });
});
