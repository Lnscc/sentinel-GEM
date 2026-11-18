import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import StatisticsPage from "./StatisticsPage";
import type { StatisticsReport } from "./statisticsTypes";

function report(articleCount = 42): StatisticsReport {
  return {
    generatedAt: "2026-11-18T12:00:00Z",
    period: { from: "2026-11-17T12:00:00Z", to: "2026-11-18T12:00:00Z" },
    visibleVersion: "story-version-1.3",
    metrics: [
      { id: "articles", group: "inventory", label: "Artikel", value: articleCount, unit: "Artikel", scope: "INVENTORY", definition: "Gespeicherte Artikel aller Quellen.", from: null, to: null, unavailableReason: null },
      { id: "incoming", group: "processing", label: "Neue Artikel", value: 0, unit: "Artikel", scope: "PERIOD", definition: "Nach Erfassungszeitpunkt.", from: "2026-11-17T12:00:00Z", to: "2026-11-18T12:00:00Z", unavailableReason: null },
      { id: "tokens", group: "effort", label: "Tokenverbrauch", value: null, unit: "Token", scope: "RUNTIME", definition: "Vom Provider gemeldete Tokens.", from: "2026-11-18T10:00:00Z", to: "2026-11-18T12:00:00Z", unavailableReason: "Noch keine Tokenmessung vorhanden." },
    ],
    recentRuns: [],
  };
}

function response(value: StatisticsReport): Response {
  return { ok: true, json: async () => value } as Response;
}

describe("StatisticsPage", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("lädt 24 Stunden und unterscheidet Nullwerte von fehlenden Messungen", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(report()));
    vi.stubGlobal("fetch", fetchMock);
    render(<StatisticsPage />);
    expect(screen.getByRole("status")).toHaveTextContent("Statistik wird geladen");
    expect(await screen.findByText("42")).toBeInTheDocument();
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText("Nicht verfügbar")).toBeInTheDocument();
    expect(screen.getByText(/Noch keine Tokenmessung vorhanden/)).toBeInTheDocument();
    expect(screen.getByText("Gespeicherte Artikel aller Quellen.")).toBeInTheDocument();
    expect(screen.getByText("Keine Verarbeitungsläufe in diesem Zeitraum vorhanden.")).toBeInTheDocument();
    expect(screen.getByText(/story-version-1.3/)).toBeInTheDocument();
    const query = new URL(fetchMock.mock.calls[0][0], "http://localhost").searchParams;
    expect(query.get("hours")).toBe("24");
    expect(query.has("to")).toBe(false);
  });

  it("ändert den Zeitraum, aktualisiert und bietet nach Ladefehlern einen neuen Versuch", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(report()))
      .mockResolvedValueOnce(response(report(7)))
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce(response(report(8)));
    vi.stubGlobal("fetch", fetchMock);
    render(<StatisticsPage />);
    await screen.findByText("42");
    fireEvent.change(screen.getByRole("combobox", { name: "Zeitraum" }), { target: { value: "168" } });
    await screen.findByText("7");
    const query = new URL(fetchMock.mock.calls[1][0], "http://localhost").searchParams;
    expect(query.get("hours")).toBe("168");
    fireEvent.click(screen.getByRole("button", { name: "Aktualisieren" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 503");
    expect(screen.queryByText("7")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Erneut versuchen" }));
    expect(await screen.findByText("8")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("verwirft verspätete Antworten einer vorherigen Zeitraumwahl", async () => {
    let resolveOldJson!: (value: StatisticsReport) => void;
    let oldSignal: AbortSignal | undefined;
    const fetchMock = vi.fn()
      .mockImplementationOnce((_input: RequestInfo, options?: RequestInit) => {
        oldSignal = options?.signal as AbortSignal;
        return Promise.resolve({ ok: true, json: () => new Promise((resolve) => { resolveOldJson = resolve; }) });
      })
      .mockResolvedValueOnce(response(report(7)));
    vi.stubGlobal("fetch", fetchMock);
    render(<StatisticsPage />);
    await waitFor(() => expect(resolveOldJson).toBeTypeOf("function"));
    fireEvent.change(screen.getByRole("combobox", { name: "Zeitraum" }), { target: { value: "6" } });
    expect(await screen.findByText("7")).toBeInTheDocument();
    expect(oldSignal?.aborted).toBe(true);
    await act(async () => resolveOldJson(report(42)));
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.queryByText("42")).not.toBeInTheDocument();
  });
});
