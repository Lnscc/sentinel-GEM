import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
  const setMarkerCoordinates = vi.fn();
  let options: any;
  let sourceData: any = { features: [] };
  const getClusterLeaves = vi.fn(() => Promise.resolve(sourceData.features.slice(0, 3)));
  const getClusterExpansionZoom = vi.fn(() => Promise.resolve(5));
  const state: { queryFeatures: any[] | null; visibleFeatures: any[] | null; bounds: [number, number, number, number] } = { queryFeatures: null, visibleFeatures: null, bounds: [-180, -90, 180, 90] };
  class MockMap {
    private canvas = document.createElement("canvas");
    readonly container: HTMLElement;
    constructor(nextOptions: { container: HTMLElement }) { options = nextOptions; this.container = nextOptions.container; nextOptions.container.append(this.canvas); setTimeout(() => handlers.get("load")?.(), 0); }
    addControl() {}
    addSource(id: string, source: any) { sourceData = source.data; addSource(id, source); }
    addLayer = addLayer;
    easeTo = easeTo;
    stop() {}
    jumpTo() { state.bounds = [-180, -90, 180, 90]; handlers.get("moveend")?.(); }
    getCenter = () => ({ lng: (state.bounds[0] + state.bounds[2]) / 2, lat: (state.bounds[1] + state.bounds[3]) / 2 });
    getZoom = () => 1;
    getCanvas = () => this.canvas;
    getBounds = () => ({ getWest: () => state.bounds[0], getSouth: () => state.bounds[1], getEast: () => state.bounds[2], getNorth: () => state.bounds[3] });
    isSourceLoaded = () => true;
    getSource = () => ({
      getClusterExpansionZoom,
      getClusterLeaves,
      setData: (data: any) => { sourceData = data; setData(data); queueMicrotask(() => handlers.get("render")?.()); },
    });
    project = () => ({ x: 0, y: 0 });
    querySourceFeatures = () => state.visibleFeatures ?? state.queryFeatures ?? sourceData.features;
    on(event: string, layerOrHandler: string | ((...args: any[]) => any), handler?: (...args: any[]) => any) { handlers.set(typeof layerOrHandler === "string" ? `${event}:${layerOrHandler}` : event, handler ?? layerOrHandler as (...args: any[]) => any); }
    remove = remove;
  }
  class MockMarker {
    constructor(private options: { element: HTMLElement }) {}
    setLngLat(coordinates: [number, number]) { setMarkerCoordinates(coordinates); return this; }
    getElement() { return this.options.element; }
    addTo(map: MockMap) { map.container.append(this.options.element); return this; }
    remove() { this.options.element.remove(); }
  }
  return { handlers, addSource, addLayer, setData, remove, easeTo, setMarkerCoordinates, getClusterLeaves, getClusterExpansionZoom, state, get options() { return options; }, MockMap, MockMarker };
});

vi.mock("maplibre-gl", () => ({ Map: mapLibre.MockMap, Marker: mapLibre.MockMarker, NavigationControl: class {}, setWorkerUrl: vi.fn() }));

const story = {
  id: "story-1", effectiveFrom: "2026-10-21T10:00:00Z", effectiveTo: "2026-10-21T11:00:00Z",
  location: { name: "Berlin", countryCode: "GM", regionCode: "GM16", latitude: 52.52, longitude: 13.405, source: "GDELT_EVENT_ACTION", confidence: 100, supportingArticles: 2, supportingDomains: 2 },
  locations: [{ name: "Berlin", countryCode: "GM", regionCode: "GM16", latitude: 52.52, longitude: 13.405, source: "GDELT_EVENT_ACTION", confidence: 100, supportingArticles: 2, supportingDomains: 2 }],
  representativeArticle: { domain: "example.com", title: "Ein wichtiges Ereignis", mainImageUrl: "https://images.example.com/story.jpg" }, memberCount: 2, sourceCount: 2,
};
const otherStory = { ...story, id: "story-2", location: { ...story.location, name: "Paris", countryCode: "FR", regionCode: "FR11", latitude: 48.85, longitude: 2.35 }, representativeArticle: { ...story.representativeArticle, domain: "news.fr", title: "Ein anderes Ereignis", mainImageUrl: null } };
const detail = { ...story, state: "PUBLISHED", representativeArticle: { ...story.representativeArticle, id: 1, canonicalUrl: "https://example.com/1", firstSeenAt: story.effectiveFrom, publishedAt: null }, members: [{ assignmentReason: "representative", article: { id: 1, canonicalUrl: "https://example.com/1", domain: "example.com", firstSeenAt: story.effectiveFrom, title: "Artikel eins", publishedAt: null, mainImageUrl: null } }] };
const response = (body: unknown, ok = true, status = ok ? 200 : 500) => Promise.resolve({ ok, status, json: () => Promise.resolve(body) } as Response);
const mockFetch = (implementation: typeof fetch, mapResponses: Partial<Record<"positions" | "markers", typeof fetch>> = {}) => {
  let latestList = response({ stories: [], total: 0 });
  const fixtures = new Map<string, typeof story | typeof otherStory>([story, otherStory].map((value) => [value.id, value]));
  return vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/stories/countries") return response([{ code: "GM", name: "Deutschland" }, { code: "FR", name: "Frankreich" }, { code: "UP", name: "Ukraine" }]);
    if (url.pathname === "/api/stories/map-positions") return mapResponses.positions?.(input, init) ?? latestList.then(async (result) => {
      if (!result.ok) return response([]);
      const page = await result.json();
      page.stories.forEach((value: typeof story) => fixtures.set(value.id, value));
      return response(page.stories.filter((value: typeof story) => value.location).map((value: typeof story) => ({ id: value.id, latitude: value.location.latitude, longitude: value.location.longitude })));
    });
    if (url.pathname === "/api/stories/map-markers") return mapResponses.markers?.(input, init) ?? response((url.searchParams.get("ids") ?? "").split(",").flatMap((id) => {
      const value = fixtures.get(id);
      return value ? [{ id, title: value.representativeArticle.title, imageUrl: value.representativeArticle.mainImageUrl }] : [];
    }));
    const result = implementation(input, init);
    if (url.pathname === "/api/stories") latestList = result;
    return result;
  });
};
const renderApp = () => render(<ThemeProvider theme={createTheme()}><App /></ThemeProvider>);

describe("Globales Lagebild", () => {
  beforeEach(() => {
    mapLibre.setMarkerCoordinates.mockClear();
    vi.stubGlobal("IntersectionObserver", class {
      observe(target: HTMLElement) { target.inert = false; }
      unobserve() {}
      disconnect() {}
    });
  });
  beforeEach(() => { vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); mapLibre.handlers.clear(); mapLibre.state.queryFeatures = null; mapLibre.state.visibleFeatures = null; mapLibre.state.bounds = [-180, -90, 180, 90]; mapLibre.addSource.mockClear(); mapLibre.addLayer.mockClear(); mapLibre.setData.mockClear(); mapLibre.remove.mockClear(); mapLibre.easeTo.mockClear(); mapLibre.getClusterExpansionZoom.mockReset().mockImplementation(() => Promise.resolve(5)); });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("zeigt Karte und priorisierte Ereignisse aus der bestehenden API", async () => {
    const fetchMock = mockFetch(() => response({ stories: [story, otherStory], total: 2 }));
    renderApp();

    expect(screen.getByRole("heading", { name: "Globales Lagebild" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    const listItems = within(screen.getByRole("list", { name: "Gefilterte Ereignisse" })).getAllByRole("listitem");
    expect(listItems[0].querySelector("img")).toHaveAttribute("src", "https://images.example.com/story.jpg");
    expect(listItems[1].querySelector("img")).toBeNull();
    expect(listItems[1].querySelector(".story-image__placeholder")).toBeInTheDocument();
    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
    expect(mapLibre.addLayer).toHaveBeenCalledWith(expect.objectContaining({ id: "story-cluster-hit-area", source: "stories" }));
    expect(mapLibre.addLayer).toHaveBeenCalledWith(expect.objectContaining({ id: "story-point-hit-area", source: "stories" }));
    expect(within(screen.getByRole("region", { name: "Weltkarte" })).getByLabelText("Interaktive Weltkarte der aktuellen Ereignisse")).toBeInTheDocument();
    const url = new URL(fetchMock.mock.calls.find(([input]) => String(input).startsWith("/api/stories?"))![0] as string, "http://localhost");
    expect(url.searchParams.get("sort")).toBe("priority");
    expect(url.searchParams.get("direction")).toBe("desc");
    expect(url.searchParams.get("limit")).toBe("100");
    expect(url.searchParams.get("from")).toBeTruthy();
    expect(screen.getAllByText(/2 Artikel · 2 Quellen/)).toHaveLength(2);
  });

  it("lädt Ereignisse automatisch nach und erhält Karten- und Auswahlzustand", async () => {
    let automaticRefresh: (() => void) | undefined;
    vi.spyOn(window, "setInterval").mockImplementation(((handler: TimerHandler, timeout?: number) => {
      if (timeout === 60_000) automaticRefresh = handler as () => void;
      return 1;
    }) as typeof window.setInterval);
    let listRequests = 0;
    mockFetch((input) => {
      if (String(input).includes("/story-1")) return response(detail);
      listRequests += 1;
      return response({ stories: listRequests === 1 ? [story] : [story, otherStory], total: listRequests === 1 ? 1 : 2 });
    });
    const user = userEvent.setup();
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });

    mapLibre.state.bounds = [2, 48, 15, 55];
    mapLibre.handlers.get("moveend")?.();
    await waitFor(() => expect(listRequests).toBeGreaterThan(1));
    await user.click(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" }));
    await screen.findByRole("heading", { name: "Zugehörige Artikel" });

    const requestsBeforeRefresh = listRequests;
    act(() => automaticRefresh?.());
    await waitFor(() => expect(listRequests).toBe(requestsBeforeRefresh + 1));
    expect(screen.getByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    expect(new URLSearchParams(window.location.search).get("story")).toBe("story-1");
    expect(new URLSearchParams(window.location.search).get("bounds")).toBe("2,48,15,55");

    await user.click(screen.getByRole("button", { name: /Alle Ereignisse/ }));
    expect(await screen.findByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
  });

  it("lädt mehr als 100 Positionen unabhängig von der Liste und nur sichtbare Metadaten", async () => {
    const positions = [...Array.from({ length: 150 }, (_, index) => ({ id: `hidden-${index}`, latitude: -33, longitude: 151 })), { id: story.id, latitude: 52.52, longitude: 13.405 }];
    mapLibre.state.visibleFeatures = [{ type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] }, properties: { storyId: story.id } }];
    const fetchMock = mockFetch(() => response({ stories: [otherStory], total: 500 }), { positions: () => response(positions) });
    renderApp();
    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    expect(mapLibre.setMarkerCoordinates).toHaveBeenLastCalledWith([13.405, 52.52]);
    const source = mapLibre.setData.mock.calls.at(-1)?.[0] ?? mapLibre.addSource.mock.calls.at(-1)?.[1].data;
    expect(source.features).toHaveLength(151);
    expect(source.features[0].properties).toEqual({ storyId: "hidden-0" });
    const sourceWrites = mapLibre.setData.mock.calls.length;
    const markerCalls = () => fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories/map-markers?"));
    expect(new URL(String(markerCalls()[0][0]), "http://localhost").searchParams.get("ids")).toBe(story.id);
    expect(markerCalls().every(([input]) => new URL(String(input), "http://localhost").searchParams.get("ids") === story.id)).toBe(true);
    const metadataBeforePan = markerCalls().length;
    expect(screen.getByText("1 von 500")).toBeInTheDocument();

    mapLibre.state.bounds = [10, 50, 15, 55];
    await act(async () => { mapLibre.handlers.get("moveend")?.(); mapLibre.handlers.get("render")?.(); });
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => String(input).startsWith("/api/stories?") && String(input).includes("bounds="))).toBe(true));
    expect(fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories/map-positions?"))).toHaveLength(1);
    expect(markerCalls()).toHaveLength(metadataBeforePan);
    expect(mapLibre.setData).toHaveBeenCalledTimes(sourceWrites);
    expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBe(marker);
  });

  it("zeigt Fehler der Kartenpositionen unabhängig von der erfolgreichen Liste", async () => {
    mockFetch(() => response({ stories: [story], total: 1 }), { positions: () => response({}, false) });
    renderApp();
    expect(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("Kartenereignisse konnten nicht geladen werden");
    expect(screen.getByRole("alert")).toHaveTextContent("HTTP 500");
  });

  it("zeigt Metadatenfehler und stellt die Marker beim nächsten erfolgreichen Versuch dar", async () => {
    let fail = true;
    mockFetch(() => response({ stories: [story], total: 1 }), { markers: () => fail ? response({}, false) : response([{ id: story.id, title: story.representativeArticle.title, imageUrl: null }]) });
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("Kartenbilder konnten nicht geladen werden");
    expect(screen.getByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();
    fail = false;
    act(() => mapLibre.handlers.get("render")?.());
    expect(await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("behält bei Aktualisierungsfehlern die Daten und bietet einen erneuten Versuch an", async () => {
    let listRequests = 0;
    let shouldFail = false;
    mockFetch(() => {
      listRequests += 1;
      if (shouldFail) return response({}, false);
      return response({ stories: listRequests === 1 ? [story] : [story, otherStory], total: listRequests === 1 ? 1 : 2 });
    });
    const user = userEvent.setup();
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole("button", { name: "Aktualisieren" })).toBeEnabled());

    shouldFail = true;
    await user.click(screen.getByRole("button", { name: "Aktualisieren" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 500");
    expect(screen.getByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();

    shouldFail = false;
    await user.click(screen.getByRole("button", { name: "Aktualisieren" }));
    expect(await screen.findByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("filtert Suche, Zeitraum und Land konsistent und setzt alles zurück", async () => {
    const fetchMock = mockFetch((input) => {
      const url = new URL(String(input), "http://localhost");
      return url.searchParams.get("q") === "Paris"
        ? response({ stories: [otherStory], total: 1 })
        : response({ stories: [story, otherStory], total: 2 });
    });
    const user = userEvent.setup();
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });

    await user.type(screen.getByRole("searchbox", { name: "Suche" }), "Paris");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Ein wichtiges Ereignis" })).not.toBeInTheDocument());
    expect(await screen.findByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    expect(new URL(fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories?")).at(-1)![0] as string, "http://localhost").searchParams.get("q")).toBe("Paris");
    expect(fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories?") && new URL(String(input), "http://localhost").searchParams.has("q"))).toHaveLength(1);
    await user.click(screen.getByRole("combobox", { name: "Land" }));
    await user.click(screen.getByRole("option", { name: "Frankreich" }));
    await user.click(screen.getByRole("combobox", { name: "Zeitraum" }));
    await user.click(screen.getByRole("option", { name: "Letzte 6 Stunden" }));
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories?")).at(-1)![0] as string, "http://localhost");
      expect(url.searchParams.get("country")).toBe("FR");
    });
    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).get("q")).toBe("Paris");
      expect(new URLSearchParams(window.location.search).get("country")).toBe("FR");
      expect(new URLSearchParams(window.location.search).get("hours")).toBe("6");
    });

    await user.click(screen.getByTitle("Land entfernen"));
    await waitFor(() => expect(new URLSearchParams(window.location.search).has("country")).toBe(false));
    expect(screen.getByRole("combobox", { name: "Land" })).toHaveValue("");

    await user.click(screen.getByRole("button", { name: "Filter löschen" }));
    expect(screen.getByRole("searchbox", { name: "Suche" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Land" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveTextContent("Letzte 24 Stunden");
  });

  it("findet ein Land außerhalb der geladenen Story-Seite per Tastatur und entfernt es wieder", async () => {
    const fetchMock = mockFetch((input) => response({ stories: new URL(String(input), "http://localhost").searchParams.has("country") ? [] : [story], total: 1 }));
    const user = userEvent.setup();
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    const country = screen.getByRole("combobox", { name: "Land" });
    await user.type(country, "ukr");
    expect(await screen.findByRole("option", { name: "Ukraine" })).toBeInTheDocument();
    await user.keyboard("{ArrowDown}{Enter}");
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("country")).toBe("UP"));
    expect(country).toHaveValue("Ukraine");
    expect(await screen.findByText("Kein Ereignis passt zu diesen Filtern.")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes("country=UP"))).toBe(true);
    expect(screen.queryByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).not.toBeInTheDocument();
    await user.click(country);
    await user.keyboard("{Control>}a{/Control}{Backspace}{Escape}");
    await waitFor(() => expect(new URLSearchParams(window.location.search).has("country")).toBe(false));
    expect(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();
  });

  it("entfernt alte Ergebnisse, wenn ein neuer Suchumfang abgelehnt wird", async () => {
    mockFetch((input) => {
      const query = new URL(String(input), "http://localhost").searchParams.get("q");
      return query && query.length > 200
        ? response({}, false, 400)
        : response({ stories: [story], total: 1 });
    });
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    expect(screen.getByText("1 von 1")).toBeInTheDocument();

    fireEvent.change(screen.getByRole("searchbox", { name: "Suche" }), { target: { value: "x".repeat(201) } });

    expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 400");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Ein wichtiges Ereignis" })).not.toBeInTheDocument());
    expect(screen.queryByText("1 von 1")).not.toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("entfernt alte Ergebnisse sofort, bevor die neue Suche antwortet", async () => {
    let resolveSearch!: (value: Response) => void;
    mockFetch((input) => {
      const query = new URL(String(input), "http://localhost").searchParams.get("q");
      if (query === "Paris") return new Promise((resolve) => { resolveSearch = resolve; });
      return response({ stories: [story], total: 1 });
    });
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });

    fireEvent.change(screen.getByRole("searchbox", { name: "Suche" }), { target: { value: "Paris" } });

    await waitFor(() => expect(screen.queryByRole("heading", { name: "Ein wichtiges Ereignis" })).not.toBeInTheDocument());
    expect(screen.queryByText("1 von 1")).not.toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).not.toBeInTheDocument();

    await waitFor(() => expect(resolveSearch).toBeTypeOf("function"));
    resolveSearch(await response({ stories: [otherStory], total: 1 }));
    expect(await screen.findByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
  });

  it("ignoriert die JSON-Antwort einer abgebrochenen älteren Suche", async () => {
    let resolveOldJson!: (value: unknown) => void;
    let oldSignal: AbortSignal | undefined;
    mockFetch((input, init) => {
      const query = new URL(String(input), "http://localhost").searchParams.get("q");
      if (query === "alt") {
        oldSignal = init?.signal as AbortSignal;
        return Promise.resolve({ ok: true, status: 200, json: () => new Promise((resolve) => { resolveOldJson = resolve; }) } as Response);
      }
      if (query === "neu") return response({ stories: [otherStory], total: 1 });
      return response({ stories: [story], total: 1 });
    });
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });

    fireEvent.change(screen.getByRole("searchbox", { name: "Suche" }), { target: { value: "alt" } });
    await waitFor(() => expect(resolveOldJson).toBeTypeOf("function"));
    fireEvent.change(screen.getByRole("searchbox", { name: "Suche" }), { target: { value: "neu" } });

    expect(await screen.findByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    expect(oldSignal?.aborted).toBe(true);
    await act(async () => resolveOldJson({ stories: [story], total: 1 }));
    expect(screen.getByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Ein wichtiges Ereignis" })).not.toBeInTheDocument();
  });

  it("stellt Filter, Kartenausschnitt und ausgewähltes Ereignis aus der URL wieder her", async () => {
    window.history.replaceState(null, "", "/?hours=6&country=GM&q=wichtig&bounds=10,50,15,55&story=story-1");
    const fetchMock = mockFetch((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story], total: 1 }));
    renderApp();

    expect(screen.getByRole("searchbox", { name: "Suche" })).toHaveValue("wichtig");
    expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveTextContent("Letzte 6 Stunden");
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    expect(window.location.search).toContain("story=story-1");
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Land" })).toHaveValue("Deutschland"));
    await waitFor(() => expect(mapLibre.options.bounds).toEqual([[10, 50], [15, 55]]));
    const listUrl = new URL(fetchMock.mock.calls.find(([input]) => String(input).startsWith("/api/stories?"))![0] as string, "http://localhost");
    expect(listUrl.searchParams.get("q")).toBe("wichtig");
    expect(listUrl.searchParams.get("bounds")).toBe("10,50,15,55");
  });

  it("fällt bei ungültigen URL-Parametern auf den Standardzustand zurück", async () => {
    window.history.replaceState(null, "", "/?hours=7&country=GM&story=story-1&bounds=10,50,15");
    mockFetch(() => response({ stories: [story], total: 1 }));
    renderApp();

    expect(screen.getByRole("searchbox", { name: "Suche" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Land" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveTextContent("Letzte 24 Stunden");
    expect(await screen.findByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    expect(window.location.search).toBe("");
  });

  it("öffnet Details aus Liste und Karte im Kontextpanel und kehrt zurück", async () => {
    mockFetch((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story], total: 1 }));
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
    const sourceWritesBeforeMarkerClick = mapLibre.setData.mock.calls.length;
    await user.click(await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" }));
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    expect(mapLibre.remove).toHaveBeenCalledTimes(removalsBeforeMarkerClick);
    expect(mapLibre.setData).toHaveBeenCalledTimes(sourceWritesBeforeMarkerClick);
  });

  it("zeigt Bild-, Fallback- und Cluster-Marker mit ihren Interaktionen", async () => {
    mapLibre.state.queryFeatures = [{
      type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] },
      properties: { cluster: true, cluster_id: 7, point_count: 3, point_count_abbreviated: "3" },
    }];
    mockFetch((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story, otherStory], total: 2 }));
    const user = userEvent.setup();
    renderApp();

    const cluster = await screen.findByRole("button", { name: "Cluster mit 3 Ereignissen" });
    expect(mapLibre.getClusterLeaves).toHaveBeenCalledWith(7, 3, 0);
    expect(cluster.querySelectorAll(".live-map-marker__image")).toHaveLength(3);
    expect(cluster).toHaveTextContent("3");
    await user.click(cluster);
    await waitFor(() => expect(mapLibre.easeTo).toHaveBeenCalledWith({ center: [13.4, 52.5], zoom: 5 }));

    mapLibre.state.queryFeatures = null;
    mapLibre.handlers.get("render")?.();
    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    expect(marker.querySelector(".live-map-marker__image")).toBeInTheDocument();
    expect(marker.querySelector("img")).toHaveAttribute("src", "https://images.example.com/story.jpg");
    expect(marker.parentElement).not.toHaveAttribute("style");
    expect(screen.getByRole("button", { name: "Ereignis: Ein anderes Ereignis" }).querySelector("img")).toBeNull();
    await user.click(marker);
    await screen.findByRole("heading", { name: "Zugehörige Artikel" });
    mapLibre.handlers.get("render")?.();
    await waitFor(() => expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toHaveClass("is-selected"));
  });

  it("aktualisiert Marker bereits während des Zoomens", async () => {
    mapLibre.state.queryFeatures = [{
      type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] },
      properties: { cluster: true, cluster_id: 7, point_count: 2, point_count_abbreviated: "2" },
    }];
    mockFetch(() => response({ stories: [story, otherStory], total: 2 }));
    renderApp();
    await screen.findByRole("button", { name: "Cluster mit 2 Ereignissen" });
    mapLibre.state.queryFeatures[0].geometry.coordinates = [13.42, 52.54];
    act(() => mapLibre.handlers.get("render")?.());
    await waitFor(() => expect(mapLibre.setMarkerCoordinates).toHaveBeenLastCalledWith([13.42, 52.54]));

    mapLibre.state.queryFeatures = null;
    mapLibre.handlers.get("render")?.();

    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    expect(screen.queryByRole("button", { name: "Cluster mit 2 Ereignissen" })).not.toBeInTheDocument();

    mapLibre.handlers.get("render")?.();
    expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBe(marker);
  });

  it("behält die unabhängigen Kartenmarker bei geänderter Ausschnittliste", async () => {
    let release!: (value: Response) => void;
    const pending = new Promise<Response>((resolve) => { release = resolve; });
    const addedStory = { ...otherStory, id: "story-3", representativeArticle: { ...otherStory.representativeArticle, title: "Ein neues Ereignis" } };
    mockFetch((input) => new URL(String(input), "http://localhost").searchParams.has("bounds")
      ? pending : response({ stories: [story, otherStory], total: 2 }));
    renderApp();
    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    const otherMarker = screen.getByRole("button", { name: "Ereignis: Ein anderes Ereignis" });
    const image = marker.querySelector("img");

    mapLibre.state.bounds = [2, 48, 15, 55];
    act(() => mapLibre.handlers.get("moveend")?.());
    await waitFor(() => expect(within(screen.getByRole("list", { name: "Gefilterte Ereignisse" })).queryAllByRole("listitem")).toHaveLength(0));
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBe(marker);
    expect(marker.querySelector("img")).toBe(image);

    await act(async () => { release(await response({ stories: [story, addedStory], total: 2 })); });
    await screen.findByRole("heading", { name: "Ein neues Ereignis" });
    await act(async () => { mapLibre.handlers.get("render")?.(); });
    expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBe(marker);
    expect(marker.querySelector("img")).toBe(image);
    expect(otherMarker).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ereignis: Ein neues Ereignis" })).not.toBeInTheDocument();
  });

  it("behält globale Kartenmarker auch bei fehlerhafter oder leerer Ausschnittliste", async () => {
    mockFetch((input) => {
      const bounds = new URL(String(input), "http://localhost").searchParams.get("bounds");
      if (bounds === "2,48,15,55") return response({}, false);
      return response({ stories: bounds ? [] : [story], total: bounds ? 0 : 1 });
    });
    renderApp();
    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    mapLibre.state.bounds = [2, 48, 15, 55];
    act(() => mapLibre.handlers.get("moveend")?.());
    expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 500");
    expect(screen.getByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBe(marker);
    expect(within(screen.getByRole("list", { name: "Gefilterte Ereignisse" })).queryAllByRole("listitem")).toHaveLength(0);

    mapLibre.state.bounds = [3, 48, 15, 55];
    act(() => mapLibre.handlers.get("moveend")?.());
    await screen.findByText(/Kein Ereignis passt.*im aktiven Kartenausschnitt/);
    expect(marker).toBeInTheDocument();
    expect(screen.getByText("0 von 0")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it.each(["Suche", "Land", "Zeitraum"])("entfernt bisherige Kartenmarker sofort bei geändertem Filter %s", async (filter) => {
    let requests = 0;
    mockFetch(() => ++requests === 1 ? response({ stories: [story], total: 1 }) : new Promise<Response>(() => {}));
    const user = userEvent.setup();
    renderApp();
    const marker = await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" });
    if (filter === "Suche") {
      await user.type(screen.getByRole("searchbox", { name: "Suche" }), "Paris");
    } else {
      await user.click(screen.getByRole("combobox", { name: filter }));
      await user.click(screen.getByRole("option", { name: filter === "Land" ? "Frankreich" : "Letzte 6 Stunden" }));
    }
    await waitFor(() => expect(marker).not.toBeInTheDocument());
    expect(within(screen.getByRole("list", { name: "Gefilterte Ereignisse" })).queryAllByRole("listitem")).toHaveLength(0);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("verwendet unveränderte Cluster wieder und aktualisiert Vorschaubilder bei gleicher Cluster-ID", async () => {
    mapLibre.state.queryFeatures = [{
      type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] },
      properties: { cluster: true, cluster_id: 7, point_count: 2, point_count_abbreviated: "2" },
    }];
    let nextStory = story;
    mockFetch(() => response({ stories: [nextStory, otherStory], total: 2 }));
    const user = userEvent.setup();
    renderApp();
    const cluster = await screen.findByRole("button", { name: "Cluster mit 2 Ereignissen" });
    const image = cluster.querySelector("img");
    await act(async () => { mapLibre.handlers.get("render")?.(); });
    expect(screen.getByRole("button", { name: "Cluster mit 2 Ereignissen" })).toBe(cluster);
    expect(cluster.querySelector("img")).toBe(image);

    await user.click(screen.getByRole("button", { name: "Aktualisieren" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Aktualisieren" })).toBeEnabled());
    await act(async () => { mapLibre.handlers.get("render")?.(); });
    expect(screen.getByRole("button", { name: "Cluster mit 2 Ereignissen" })).toBe(cluster);
    expect(cluster.querySelector("img")).toBe(image);

    mapLibre.state.queryFeatures![0].properties.cluster_id = 8;
    await act(async () => { mapLibre.handlers.get("render")?.(); });
    expect(screen.getByRole("button", { name: "Cluster mit 2 Ereignissen" })).toBe(cluster);
    expect(cluster.querySelector("img")).toBe(image);

    nextStory = { ...story, representativeArticle: { ...story.representativeArticle, mainImageUrl: "https://images.example.com/updated.jpg" } };
    await user.click(screen.getByRole("button", { name: "Aktualisieren" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Aktualisieren" })).toBeEnabled());
    await act(async () => { mapLibre.handlers.get("render")?.(); });
    await waitFor(() => expect(screen.getByRole("button", { name: "Cluster mit 2 Ereignissen" }).querySelector("img")).toHaveAttribute("src", "https://images.example.com/updated.jpg"));
  });

  it("stellt nach einem Filterwechsel keine veralteten asynchronen Cluster wieder her", async () => {
    mapLibre.state.queryFeatures = [{
      type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] },
      properties: { cluster: true, cluster_id: 7, point_count: 2, point_count_abbreviated: "2" },
    }];
    let requests = 0;
    mockFetch(() => ++requests === 1 ? response({ stories: [story], total: 1 }) : new Promise<Response>(() => {}));
    const user = userEvent.setup();
    renderApp();
    const cluster = await screen.findByRole("button", { name: "Cluster mit 2 Ereignissen" });
    let release!: (leaves: any[]) => void;
    mapLibre.getClusterLeaves.mockImplementationOnce(() => new Promise<any[]>((resolve) => { release = resolve; }));
    mapLibre.state.queryFeatures[0].properties.cluster_id = 8;
    act(() => mapLibre.handlers.get("render")?.());
    await waitFor(() => expect(release).toBeDefined());

    await user.type(screen.getByRole("searchbox", { name: "Suche" }), "Paris");
    await waitFor(() => expect(cluster).not.toBeInTheDocument());
    await act(async () => { release([{ properties: { storyId: story.id } }]); });
    expect(screen.queryByRole("button", { name: /Cluster mit/ })).not.toBeInTheDocument();
  });

  it.each(["vor dem Klick", "während der Zoomabfrage", "beim Entfernen der Karte"])("verwirft einen veralteten Cluster-Zoom %s", async (timing) => {
    mapLibre.state.queryFeatures = [{
      type: "Feature", geometry: { type: "Point", coordinates: [13.4, 52.5] },
      properties: { cluster: true, cluster_id: 7, point_count: 2, point_count_abbreviated: "2" },
    }];
    let currentId = story.id;
    let deferMetadata = false;
    let metadataPending = false;
    mockFetch(() => response({ stories: [story], total: 1 }), {
      positions: () => response([{ id: currentId, latitude: 52.52, longitude: 13.405 }]),
      markers: () => {
        if (deferMetadata) { metadataPending = true; return new Promise<Response>(() => {}); }
        return response([{ id: story.id, title: story.representativeArticle.title, imageUrl: null }]);
      },
    });
    const user = userEvent.setup();
    const view = renderApp();
    const cluster = await screen.findByRole("button", { name: "Cluster mit 2 Ereignissen" });
    let releaseZoom!: (zoom: number) => void;
    if (timing !== "vor dem Klick") {
      mapLibre.getClusterExpansionZoom.mockImplementationOnce(() => new Promise<number>((resolve) => { releaseZoom = resolve; }));
      await user.click(cluster);
      expect(mapLibre.getClusterExpansionZoom).toHaveBeenCalledWith(7);
    }
    if (timing === "beim Entfernen der Karte") {
      view.unmount();
      await act(async () => { releaseZoom(5); });
      expect(mapLibre.easeTo).not.toHaveBeenCalled();
      return;
    }

    deferMetadata = true;
    currentId = otherStory.id;
    mapLibre.state.queryFeatures[0].properties.cluster_id = 8;
    await user.click(screen.getByRole("button", { name: "Aktualisieren" }));
    await waitFor(() => expect(metadataPending).toBe(true));
    expect(cluster).toBeInTheDocument();
    if (timing === "vor dem Klick") {
      await user.click(cluster);
      expect(mapLibre.getClusterExpansionZoom).not.toHaveBeenCalled();
    } else {
      await act(async () => { releaseZoom(5); });
      expect(mapLibre.getClusterExpansionZoom).toHaveBeenCalledTimes(1);
    }
    expect(mapLibre.easeTo).not.toHaveBeenCalled();
  });

  it("rendert ein serverseitiges Suchergebnis nach der MapLibre-Quelldatenverarbeitung", async () => {
    window.history.replaceState(null, "", "/?q=Berlin&bounds=10,50,15,55");
    mapLibre.state.queryFeatures = [];
    mapLibre.state.bounds = [10, 50, 15, 55];
    mockFetch(() => response({ stories: [story], total: 1 }));
    renderApp();

    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    expect(screen.getByText("1 von 1")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).not.toBeInTheDocument();

    mapLibre.state.queryFeatures = null;
    act(() => mapLibre.handlers.get("render")?.());

    expect(await screen.findByRole("button", { name: "Ereignis: Ein wichtiges Ereignis" })).toBeInTheDocument();
  });

  it("synchronisiert Liste, Anzahl und Auswahl mit dem Kartenausschnitt", async () => {
    const fetchMock = mockFetch((input) => {
      if (String(input).includes("/story-1")) return response(detail);
      const bounds = new URL(String(input), "http://localhost").searchParams.get("bounds");
      return bounds === "2,48,3,49"
        ? response({ stories: [otherStory], total: 1 })
        : response({ stories: [story, otherStory], total: 2 });
    });
    const user = userEvent.setup();
    renderApp();
    await user.click(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" }));
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();

    mapLibre.state.bounds = [2, 48, 3, 49];
    mapLibre.handlers.get("moveend")?.();

    expect(await screen.findByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    expect(new URLSearchParams(window.location.search).get("bounds")).toBe("2,48,3,49");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Ein wichtiges Ereignis" })).not.toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    expect(screen.getByText("1 von 1")).toBeInTheDocument();
    expect(new URL(fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories?")).at(-1)![0] as string, "http://localhost").searchParams.get("bounds")).toBe("2,48,3,49");
  });

  it("setzt den Kartenausschnitt zurück und lädt wieder globale Ergebnisse", async () => {
    const fetchMock = mockFetch((input) => {
      const scoped = new URL(String(input), "http://localhost").searchParams.has("bounds");
      return response({ stories: scoped ? [] : [story, otherStory], total: scoped ? 0 : 2 });
    });
    const user = userEvent.setup();
    renderApp();
    await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
    mapLibre.state.bounds = [2, 48, 3, 49];
    act(() => mapLibre.handlers.get("moveend")?.());
    expect(await screen.findByText(/Kein Ereignis passt.*im aktiven Kartenausschnitt/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Zur Weltansicht" }));
    expect(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Ein anderes Ereignis" })).toBeInTheDocument();
    expect(screen.getByText("2 von 2")).toBeInTheDocument();
    expect(window.location.search).toBe("");
    expect(new URL(fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories?")).at(-1)![0] as string, "http://localhost").searchParams.has("bounds")).toBe(false);
    act(() => mapLibre.handlers.get("moveend")?.());
    expect(screen.queryByText("Kartenausschnitt aktiv")).not.toBeInTheDocument();
  });

  it("normalisiert MapLibre-Weltkopien als gültigen Datumsgrenzen-Ausschnitt", async () => {
    const fetchMock = mockFetch(() => response({ stories: [], total: 0 }));
    renderApp();
    await screen.findByLabelText("Interaktive Weltkarte der aktuellen Ereignisse");

    mapLibre.state.bounds = [170, -20, 190, 20];
    act(() => mapLibre.handlers.get("moveend")?.());

    await waitFor(() => expect(new URL(fetchMock.mock.calls.filter(([input]) => String(input).startsWith("/api/stories?")).at(-1)![0] as string, "http://localhost").searchParams.get("bounds")).toBe("170,-20,-170,20"));
    expect(new URLSearchParams(window.location.search).get("bounds")).toBe("170,-20,-170,20");
  });

  it("bildet Lade-, Leer- und Fehlerzustände mit Produktbegriffen ab", async () => {
    let resolveFetch!: (value: Response) => void;
    const pending = new Promise<Response>((resolve) => { resolveFetch = resolve; });
    mockFetch(() => pending);
    const view = renderApp();
    expect(screen.getByRole("status")).toHaveTextContent("Kartenereignisse werden geladen");
    resolveFetch(await response({ stories: [], total: 0 }));
    expect(await screen.findByText("Kein Ereignis passt zu diesen Filtern.")).toBeInTheDocument();
    expect(await screen.findByLabelText("Interaktive Weltkarte der aktuellen Ereignisse")).toBeInTheDocument();

    view.unmount();
    mockFetch(() => response({}, false));
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("Die Ereignisse konnten nicht geladen werden. HTTP 500");
  });
});
