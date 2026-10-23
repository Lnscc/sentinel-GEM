import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider, createTheme } from "@mui/material";
import App from "./App";

const mapLibre = vi.hoisted(() => {
  const handlers = new globalThis.Map<string, (...args: any[]) => any>();
  const addSource = vi.fn();
  const addLayer = vi.fn();
  class MockMap {
    private canvas = document.createElement("canvas");
    constructor({ container }: { container: HTMLElement }) { container.append(this.canvas); setTimeout(() => handlers.get("load")?.(), 0); }
    addControl() {}
    addSource = addSource;
    addLayer = addLayer;
    easeTo() {}
    getCanvas = () => this.canvas;
    getSource = () => ({ getClusterExpansionZoom: () => Promise.resolve(5) });
    on(event: string, layerOrHandler: string | ((...args: any[]) => any), handler?: (...args: any[]) => any) { handlers.set(typeof layerOrHandler === "string" ? `${event}:${layerOrHandler}` : event, handler ?? layerOrHandler as (...args: any[]) => any); }
    remove() {}
  }
  return { handlers, addSource, addLayer, MockMap };
});

vi.mock("maplibre-gl", () => ({ Map: mapLibre.MockMap, NavigationControl: class {}, setWorkerUrl: vi.fn() }));

const story = {
  id: "story-1", effectiveFrom: "2026-10-21T10:00:00Z", effectiveTo: "2026-10-21T11:00:00Z",
  location: { name: "Berlin", countryCode: "GM", regionCode: "GM16", latitude: 52.52, longitude: 13.405, source: "GDELT_EVENT_ACTION" },
  representativeArticle: { domain: "example.com", title: "Ein wichtiges Ereignis", mainImageUrl: null }, memberCount: 2,
};
const otherStory = { ...story, id: "story-2", location: { ...story.location, name: "Paris", regionCode: "FR11", latitude: 48.85, longitude: 2.35 }, representativeArticle: { ...story.representativeArticle, domain: "news.fr", title: "Ein anderes Ereignis" } };
const detail = { ...story, state: "PUBLISHED", representativeArticle: { ...story.representativeArticle, id: 1, canonicalUrl: "https://example.com/1", firstSeenAt: story.effectiveFrom, publishedAt: null }, members: [{ assignmentReason: "representative", article: { id: 1, canonicalUrl: "https://example.com/1", domain: "example.com", firstSeenAt: story.effectiveFrom, title: "Artikel eins", publishedAt: null, mainImageUrl: null } }] };
const response = (body: unknown, ok = true) => Promise.resolve({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) } as Response);
const renderApp = () => render(<ThemeProvider theme={createTheme()}><App /></ThemeProvider>);

describe("Globales Lagebild", () => {
  beforeEach(() => { vi.restoreAllMocks(); mapLibre.handlers.clear(); mapLibre.addSource.mockClear(); mapLibre.addLayer.mockClear(); });
  afterEach(cleanup);

  it("zeigt Karte und priorisierte Ereignisse aus der bestehenden API", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => response({ stories: [story, otherStory], total: 2 }));
    renderApp();

    expect(screen.getByRole("heading", { name: "Globales Lagebild" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();
    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
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

    await user.click(screen.getByRole("button", { name: "Filter löschen" }));
    expect(screen.getByRole("searchbox", { name: "Suche" })).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "Region" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Zeitraum" })).toHaveTextContent("Letzte 24 Stunden");
  });

  it("öffnet Details aus Liste und Karte im Kontextpanel und kehrt zurück", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => String(input).includes("/story-1") ? response(detail) : response({ stories: [story], total: 1 }));
    const user = userEvent.setup();
    renderApp();
    const heading = await screen.findByRole("heading", { name: "Ein wichtiges Ereignis" });
    await user.click(heading);
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Alle Ereignisse/ }));
    expect(await screen.findByRole("heading", { name: "Priorisierte Ereignisse" })).toBeInTheDocument();

    await waitFor(() => expect(mapLibre.addSource).toHaveBeenCalled());
    mapLibre.handlers.get("click:story-points")?.({ features: [{ properties: { storyId: "story-1" } }] });
    expect(await screen.findByRole("heading", { name: "Zugehörige Artikel" })).toBeInTheDocument();
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
