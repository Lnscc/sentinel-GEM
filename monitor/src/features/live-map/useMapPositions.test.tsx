import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { DEFAULT_FILTERS } from "../stories/StoryFilters";
import useMapPositions from "./useMapPositions";

const berlin = { id: "berlin", latitude: 52.52, longitude: 13.405 };
const paris = { id: "paris", latitude: 48.85, longitude: 2.35 };
const response = (body: unknown, ok = true) => Promise.resolve({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) } as Response);

describe("vollständige Kartenpositionen", () => {
  beforeEach(() => vi.restoreAllMocks());
  afterEach(cleanup);

  it("lädt alle Positionen ohne Seitenlimit und übergibt die fachlichen Filter", async () => {
    const positions = Array.from({ length: 151 }, (_, index) => ({ ...berlin, id: String(index) }));
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => response(positions));
    const filters = { ...DEFAULT_FILTERS, query: " Berlin ", country: "GM", hours: "6" };
    const { result, rerender } = renderHook(({ refreshKey }) => useMapPositions(filters, refreshKey), { initialProps: { refreshKey: 0 } });
    await waitFor(() => expect(result.current.positions).toHaveLength(151));
    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(url.pathname).toBe("/api/stories/map-positions");
    expect(url.searchParams.get("q")).toBe("Berlin");
    expect(url.searchParams.get("country")).toBe("GM");
    expect(url.searchParams.get("from")).toBeTruthy();
    expect(url.searchParams.has("bounds")).toBe(false);
    expect(url.searchParams.has("limit")).toBe(false);
    rerender({ refreshKey: 0 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    rerender({ refreshKey: 1 });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });

  it("verwirft eine ältere JSON-Antwort nach einem Filterwechsel", async () => {
    let releaseOld!: (value: unknown) => void;
    let oldSignal: AbortSignal | undefined;
    vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
      if (!new URL(String(input), "http://localhost").searchParams.has("country")) {
        oldSignal = init?.signal as AbortSignal;
        return Promise.resolve({ ok: true, json: () => new Promise((resolve) => { releaseOld = resolve; }) } as Response);
      }
      return response([paris]);
    });
    const { result, rerender } = renderHook(({ country }) => useMapPositions({ ...DEFAULT_FILTERS, country }, 0), { initialProps: { country: "" } });
    await waitFor(() => expect(releaseOld).toBeDefined());
    rerender({ country: "FR" });
    await waitFor(() => expect(result.current.positions).toEqual([paris]));
    expect(oldSignal?.aborted).toBe(true);
    await act(async () => { releaseOld([berlin]); });
    expect(result.current.positions).toEqual([paris]);
  });

  it("behält Positionen bei Aktualisierungsfehlern und entfernt sie beim Filterwechsel", async () => {
    let fail = false;
    vi.spyOn(globalThis, "fetch").mockImplementation(() => fail ? response({}, false) : response([berlin]));
    const { result, rerender } = renderHook(({ country, refreshKey }) => useMapPositions({ ...DEFAULT_FILTERS, country }, refreshKey), { initialProps: { country: "", refreshKey: 0 } });
    await waitFor(() => expect(result.current.positions).toEqual([berlin]));
    fail = true;
    rerender({ country: "", refreshKey: 1 });
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.positions).toEqual([berlin]);
    rerender({ country: "FR", refreshKey: 1 });
    await waitFor(() => expect(result.current.positions).toEqual([]));
    await waitFor(() => expect(result.current.error).toBeTruthy());
    fail = false;
    rerender({ country: "FR", refreshKey: 2 });
    await waitFor(() => expect(result.current.positions).toEqual([berlin]));
    expect(result.current.error).toBeUndefined();
  });

  it("entfernt vorherige Positionen bei einer erfolgreich leeren Antwort", async () => {
    let positions = [berlin];
    vi.spyOn(globalThis, "fetch").mockImplementation(() => response(positions));
    const { result, rerender } = renderHook(({ refreshKey }) => useMapPositions(DEFAULT_FILTERS, refreshKey), { initialProps: { refreshKey: 0 } });
    await waitFor(() => expect(result.current.positions).toEqual([berlin]));
    positions = [];
    rerender({ refreshKey: 1 });
    await waitFor(() => expect(result.current.positions).toEqual([]));
    expect(result.current.error).toBeUndefined();
  });
});
