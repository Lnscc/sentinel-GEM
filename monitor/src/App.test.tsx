import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider, createTheme } from "@mui/material";
import App from "./App";

const mapLibre = vi.hoisted(() => {
  const handlers = new globalThis.Map<string, (...args: any[]) => any>();
  const addSource = vi.fn();
  const addLayer = vi.fn();
  const setData = vi.fn();
  const remove = vi.fn();
  const easeTo = vi.fn();
  let options: any;
  let sourceData: any = { features: [] };
  const state: { queryFeatures: any[] | null; bounds: [number, number, number, number] } = { queryFeatures: null, bounds: [-180, -90, 180, 90] };
  class MockMap {
    private canvas = document.createElement("canvas");
    readonly container: HTMLElement;
    constructor(nextOptions: { container: HTMLElement }) { options = nextOptions; this.container = nextOptions.container; nextOptions.container.append(this.canvas); setTimeout(() => handlers.get("load")?.(), 0); }
    addControl() {}
    addSource(id: string, source: any) { sourceData = source.data; addSource(id, source); }
    addLayer = addLayer;
    easeTo = easeTo;
    getCanvas = () => this.canvas;
    getBounds = () => ({ getWest: () => state.bounds[0], getSouth: () => state.bounds[1], getEast: () => state.bounds[2], getNorth: () => state.bounds[3] });
    getSource = () => ({
      getClusterExpansionZoom: () => Promise.resolve(5),
      getClusterLeaves: () => Promise.resolve(sourceData.features.slice(0, 3)),
      setData: (data: any) => { sourceData = data; setData(data); },
    });
    querySourceFeatures = () => state.queryFeatures ?? sourceData.features;
    on(event: string, layerOrHandler: string | ((...args: any[]) => any), handler?: (...args: any[]) => any) { handlers.set(typeof layerOrHandler === "string" ? `${event}:${layerOrHandler}` : event, handler ?? layerOrHandler as (...args: any[]) => any); }
    remove = remove;
  }
  class MockMarker {
    constructor(private options: { element: HTMLElement }) {}
    setLngLat() { return this; }
    addTo(map: MockMap) { map.container.append(this.options.element); return this; }
    remove() { this.options.element.remove(); }
  }
  return { handlers, addSource, addLayer, setData, remove, easeTo, state, get options() { return options; }, MockMap, MockMarker };
});

vi.mock("maplibre-gl", () => ({ Map: mapLibre.MockMap, Marker: mapLibre.MockMarker, NavigationControl: class {}, setWorkerUrl: vi.fn() }));

const story = {
  id: "story-1", effectiveFrom: "2026-10-21T10:00:00Z", effectiveTo: "2026-10-21T11:00:00Z",
  location: { name: "Berlin", countryCode: "GM", regionCode: "GM16", latitude: 52.52, longitude: 13.405, source: "GDELT_EVENT_ACTION" },
  representativeArticle: { domain: "example.com", title: "Ein wichtiges Ereignis", mainImageUrl: "https://images.example.com/story.jpg" }, memberCount: 2,
};
const otherStory = { ...story, id: "story-2", location: { ...story.location, name: "Paris", regionCode: "FR11", latitude: 48.85, longitude: 2.35 }, representativeArticle: { ...story.representativeArticle, domain: "news.fr", title: "Ein anderes Ereignis", mainImageUrl: null } };
const detail = { ...story, state: "PUBLISHED", representativeArticle: { ...story.representativeArticle, id: 1, canonicalUrl: "https://example.com/1", firstSeenAt: story.effectiveFrom, publishedAt: null }, members: [{ assignmentReason: "representative", article: { id: 1, canonicalUrl: "https://example.com/1", domain: "example.com", firstSeenAt: story.effectiveFrom, title: "Artikel eins", publishedAt: null, mainImageUrl: null } }] };
const response = (body: unknown, ok = true) => Promise.resolve({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) } as Response);
const renderApp = () => render(<ThemeProvider theme={createTheme()}><App /></ThemeProvider>);

describe("Globales Lagebild", () => {
  beforeEach(() => { vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); mapLibre.handlers.clear(); mapLibre.state.queryFeatures = null; mapLibre.state.bounds = [-180, -90, 180, 90]; mapLibre.addSource.mockClear(); mapLibre.addLayer.mockClear(); mapLibre.setData.mockClear(); mapLibre.remove.mockClear(); mapLibre.easeTo.mockClear(); });
  afterEach(cleanup);

  it("zeigt Karte und priorisierte Ereignisse aus der bestehenden API", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => response({ stories: [story, otherStory], total: 2 }));
    renderApp();

    expect(screen.getByRole("heading", { name: "Globales Lagebild" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
    expect(mapLibre.addLayer).toHaveBeenCalledWith(expect.objectContaining({ id: "story-cluster-hit-area", source: "stories" }));
    expect(mapLibre.addLayer).toHaveBeenCalledWith(expect.objectContaining({ id: "story-point-hit-area", source: "stories" }));
    expect(within(screen.getByRole("region", { name: "Weltkarte" })).getByLabelText("Interaktive Weltkarte der aktuellen Ereignisse")).toBeInTheDocument();
    const url = new URL(fetchMock.mock.calls[0][0] as string, "http://localhost");
    expect(url.searchParams.get("sort")).toBe("articleCount");
    expect(url.searchParams.get("direction")).toBe("desc");
    expect(url.searchParams.get("limit")).toBe("100");
    expect(url.searchParams.get("from")).toBeTruthy();
  });

  it("filtert Suche, Zeitraum und Region konsistent und setzt alles zurück", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => response({ stories: [story, otherStory], total: 2 }));
    const user = userEvent.setup();
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });

    await user.type(screen.getByRole("searchbox", { name: "Suche" }), "Paris");
    expect(screen.queryByRole("heading", { name: "Ein wichtiges Ereignis" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "Region" }), "FR11");
    await user.click(screen.getByRole("combobox", { name: "Zeitraum" }));
    await user.click(screen.getByRole("option", { name: "Letzte 6 Stunden" }));
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.at(-1)![0] as string, "http://localhost");
      expect(url.searchParams.get("region")).toBe("FR11");
    });
    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).get("q")).toBe("Paris");
      expect(new URLSearchParams(window.location.search).get("region")).toBe("FR11");
      expect(new URLSearchParams(window.location.search).get("hours")).toBe("6");
    });

    await user.click(screen.getByRole("button", { name: "Filter löschen" }));
    expect(screen.getByRole("searchbox", { name: "Suche" })).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "Region" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveTextContent("Letzte 24 Stunden");
  });

  it("stellt Filter, Kartenausschnitt und ausgewähltes Ereignis aus der URL wieder her", async () => {
    window.history.replaceState(null, "", "/?hours=6&region=GM16&q=wichtig&bounds=10,50,15,55&story=story-1");
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story], total: 1 }));
    renderApp();

    expect(screen.getByRole("searchbox", { name: "Suche" })).toHaveValue("wichtig");
    expect(screen.getByRole("textbox", { name: "Region" })).toHaveValue("GM16");
    expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveTextContent("Letzte 6 Stunden");
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    await waitFor(() => expect(mapLibre.options.bounds).toEqual([[10, 50], [15, 55]]));
    expect(window.location.search).toContain("story=story-1");
  });

  it("fällt bei ungültigen URL-Parametern auf den Standardzustand zurück", async () => {
    window.history.replaceState(null, "", "/?hours=7&region=GM16&story=story-1&bounds=10,50,15");
    vi.spyOn(globalThis, "fetch").mockImplementation(() => response({ stories: [story], total: 1 }));
    renderApp();

    expect(screen.getByRole("searchbox", { name: "Suche" })).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "Region" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveTextContent("Letzte 24 Stunden");
    expect(await screen.findByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    expect(window.location.search).toBe("");
  });

  it("öffnet Details aus Liste und Karte im Kontextpanel und kehrt zurück", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story], total: 1 }));
    const user = userEvent.setup();
    renderApp();
    const heading = await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    await user.click(heading);
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    expect(new URLSearchParams(window.location.search).get("story")).toBe("story-1");
    await user.click(screen.getByRole("button", { name: /Alle Ereignisse/ }));
    expect(await screen.findByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    expect(new URLSearchParams(window.location.search).has("story")).toBe(false);

    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
    const removalsBeforeMarkerClick = mapLibre.remove.mock.calls.length;
    await user.click(await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" }));
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    expect(mapLibre.remove).toHaveBeenCalledTimes(removalsBeforeMarkerClick);
    expect(mapLibre.setData).toHaveBeenCalled();
  });

  it("zeigt Bild-, Fallback- und Cluster-Marker mit ihren Interaktionen", async () => {
    mapLibre.state.queryFeatures = [{
      type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] },
      properties: { cluster: true, cluster_id: 7, point_count: 3, point_count_abbreviated: "3" },
    }];
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story, otherStory], total: 2 }));
    const user = userEvent.setup();
    renderApp();

    const cluster = await screen.findByRole("button", { name: "Cluster mit 3 Ereignissen" });
    expect(cluster.querySelectorAll(".live-map-marker__image")).toHaveLength(3);
    expect(cluster).toHaveTextContent("3");
    await user.click(cluster);
    await waitFor(() => expect(mapLibre.easeTo).toHaveBeenCalledWith({ center: [13.4, 52.5], zoom: 5 }));

    mapLibre.state.queryFeatures = null;
    mapLibre.handlers.get("idle")?.();
    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    expect(marker.querySelector(".live-map-marker__image")).toBeInTheDocument();
    expect(marker.querySelector("img")).toHaveAttribute("src", "https://images.example.com/story.jpg");
    expect(marker.parentElement).not.toHaveAttribute("style");
    expect(screen.getByRole("button", { name: "Ereignis: Ein anderes Ereignis" }).querySelector("img")).toBeNull();
    await user.click(marker);
    await screen.findByRole("heading", { name: "Zugehörige Artikel" });
    mapLibre.handlers.get("idle")?.();
    await waitFor(() => expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toHaveClass("is-selected"));
  });

  it("aktualisiert Marker bereits während des Zoomens", async () => {
    mapLibre.state.queryFeatures = [{
      type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] },
      properties: { cluster: true, cluster_id: 7, point_count: 2, point_count_abbreviated: "2" },
    }];
    vi.spyOn(globalThis, "fetch").mockImplementation(() => response({ stories: [story, otherStory], total: 2 }));
    renderApp();
    await screen.findByRole("button", { name: "Cluster mit 2 Ereignissen" });

    mapLibre.state.queryFeatures = null;
    mapLibre.handlers.get("zoom")?.();

    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    expect(screen.queryByRole("button", { name: "Cluster mit 2 Ereignissen" })).not.toBeInTheDocument();

    mapLibre.handlers.get("zoom")?.();
    expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBe(marker);
  });

  it("synchronisiert Liste, Anzahl und Auswahl mit dem Kartenausschnitt", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story, otherStory], total: 2 }));
    const user = userEvent.setup();
    renderApp();
    await user.click(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" }));
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();

    mapLibre.state.bounds = [2, 48, 3, 49];
    mapLibre.handlers.get("moveend")?.();

    expect(await screen.findByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    expect(new URLSearchParams(window.location.search).get("bounds")).toBe("2,48,3,49");
    expect(screen.queryByRole("heading", { name: "Ein wichtiges Ereignis" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    expect(screen.getByText("1 von 2")).toBeInTheDocument();
  });

  it("bildet Lade-, Leer- und Fehlerzustände mit Produktbegriffen ab", async () => {
    let resolveFetch!: (value: Response) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise((resolve) => { resolveFetch = resolve; }));
    const view = renderApp();
    expect(screen.getByRole("status")).toHaveTextContent("Ereignisse werden geladen");
    resolveFetch(await response({ stories: [], total: 0 }));
    expect(await screen.findByText("Kein Ereignis passt zu diesen Filtern.")).toBeInTheDocument();
    expect(await screen.findByLabelText("Interaktive Weltkarte der aktuellen Ereignisse")).toBeInTheDocument();

    view.unmount();
    vi.spyOn(globalThis, "fetch").mockImplementation(() => response({}, false));
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("Die Ereignisse konnten nicht geladen werden. HTTP 500");
  });
});
