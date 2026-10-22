import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider, createTheme } from "@mui/material";
import App from "./App";

const mapLibre = vi.hoisted(() => {
  const handlers = new globalThis.Map<string, (...args: any[]) => any>();
  const addSource = vi.fn();
  const addLayer = vi.fn();
  const easeTo = vi.fn();
  const getClusterExpansionZoom = vi.fn(() => Promise.resolve(5));

  class MockMap {
    private canvas = document.createElement("canvas");

    constructor({ container }: { container: HTMLElement }) {
      container.append(this.canvas);
      setTimeout(() => handlers.get("load")?.(), 0);
    }

    addControl() {}
    addSource = addSource;
    addLayer = addLayer;
    easeTo = easeTo;
    getCanvas = () => this.canvas;
    getSource = () => ({ getClusterExpansionZoom });
    on(event: string, layerOrHandler: string | ((...args: any[]) => any), handler?: (...args: any[]) => any) {
      handlers.set(typeof layerOrHandler === "string" ? `${event}:${layerOrHandler}` : event, handler ?? layerOrHandler as (...args: any[]) => any);
    }
    remove() {}
  }

  class MockPopup {
    setLngLat() { return this; }
    setDOMContent(content: Node) { document.body.append(content); return this; }
    addTo() { return this; }
  }

  return { handlers, addSource, addLayer, easeTo, getClusterExpansionZoom, MockMap, MockPopup };
});

vi.mock("maplibre-gl", () => ({
  Map: mapLibre.MockMap,
  NavigationControl: class {},
  Popup: mapLibre.MockPopup,
  setWorkerUrl: vi.fn(),
}));

const story = {
  id: "story-1",
  effectiveFrom: "2026-10-21T10:00:00Z",
  effectiveTo: "2026-10-21T11:00:00Z",
  location: { name: "Berlin", countryCode: "GM", regionCode: "GM16", latitude: 52.52, longitude: 13.405, source: "GDELT_EVENT_ACTION" },
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
    mapLibre.handlers.clear();
    mapLibre.addSource.mockClear();
    mapLibre.addLayer.mockClear();
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

  it("shows located stories on an accessible map and groups nearby points", async () => {
    const nearbyStory = {
      ...story,
      id: "story-2",
      location: { ...story.location, name: "Potsdam", latitude: 52.39, longitude: 13.06 },
      representativeArticle: { domain: "example.org", title: "Eine zweite Story" },
      memberCount: 1,
    };
    const storyWithoutLocation = { ...story, id: "story-3", location: null, representativeArticle: { domain: "example.net", title: "Story ohne Ort" } };
    const storyWithInvalidLocation = {
      ...story,
      id: "story-4",
      location: { ...story.location, latitude: 120 },
      representativeArticle: { domain: "example.edu", title: "Story mit ungültigem Ort" },
    };
    vi.spyOn(globalThis, "fetch").mockImplementation(() => jsonResponse({ stories: [story, nearbyStory, storyWithoutLocation, storyWithInvalidLocation], total: 4 }));
    renderApp();

    const map = await screen.findByRole("region", { name: "Weltkarte" });
    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
    const source = mapLibre.addSource.mock.calls[0][1];
    expect(source).toMatchObject({ cluster: true, clusterRadius: 48 });
    expect(source.data.features).toHaveLength(2);
    expect(source.data.features[0].geometry.coordinates).toEqual([13.405, 52.52]);
    expect(within(map).getByLabelText("Interaktive Weltkarte der aktuellen Stories")).toBeInTheDocument();

    mapLibre.handlers.get("click:story-points")?.({ features: [source.data.features[0]] });
    const popup = document.querySelector(".story-map-popup")!;
    expect(within(popup as HTMLElement).getByRole("link", { name: "Eine wichtige Story" })).toHaveAttribute("href", "#story-1");
    expect(within(popup as HTMLElement).getByText(/2 Artikel/)).toBeInTheDocument();
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
