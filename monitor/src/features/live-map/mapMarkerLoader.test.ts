import { beforeEach, describe, expect, it, vi } from "vitest";
import createMapMarkerLoader from "./mapMarkerLoader";

const marker = (id: string) => ({ id, title: `Ereignis ${id}`, imageUrl: null });
const response = (body: unknown, ok = true) => Promise.resolve({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) } as Response);

describe("sichtbare Kartenmetadaten", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("dedupliziert IDs, lädt höchstens 100 pro Anfrage und verwendet den Cache", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
      const ids = new URL(String(input), "http://localhost").searchParams.get("ids")!.split(",");
      return response(ids.map(marker));
    });
    const loader = createMapMarkerLoader();
    const ids = Array.from({ length: 205 }, (_, index) => String(index));
    const result = await loader.load([...ids, "0", "1"]);
    expect(result.size).toBe(205);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.map(([input]) => new URL(String(input), "http://localhost").searchParams.get("ids")!.split(",").length)).toEqual([100, 100, 5]);
    await loader.load(ids);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("teilt ausstehende Anfragen und lädt bestätigte fehlende IDs nicht erneut", async () => {
    let release!: (value: Response) => void;
    const pending = new Promise<Response>((resolve) => { release = resolve; });
    const fetchMock = vi.spyOn(globalThis, "fetch").mockReturnValue(pending);
    const loader = createMapMarkerLoader();
    const first = loader.load(["a", "missing"]);
    const second = loader.load(["a"]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    release(await response([marker("a"), marker("unrequested")]));
    const [left, right] = await Promise.all([first, second]);
    expect(left.get("a")).toEqual(marker("a"));
    expect(right.get("a")).toEqual(marker("a"));
    expect(left.get("missing")).toBeNull();
    expect(left.has("unrequested")).toBe(false);
    await loader.load(["missing", "a"]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("erlaubt einen erneuten Versuch nach fehlgeschlagenen Anfragen", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementationOnce(() => response({}, false)).mockImplementationOnce(() => response([marker("a")]));
    const loader = createMapMarkerLoader();
    await expect(loader.load(["a"])).rejects.toThrow("HTTP 500");
    expect((await loader.load(["a"])).get("a")).toEqual(marker("a"));
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("verwirft Metadaten, deren JSON erst nach dem Abbruch eintrifft", async () => {
    let release!: (value: unknown) => void;
    let signal: AbortSignal | undefined;
    vi.spyOn(globalThis, "fetch").mockImplementation((_input, init) => {
      signal = init?.signal as AbortSignal;
      return Promise.resolve({ ok: true, json: () => new Promise((resolve) => { release = resolve; }) } as Response);
    });
    const loader = createMapMarkerLoader();
    const result = loader.load(["a"]);
    await Promise.resolve();
    loader.abort();
    expect(signal?.aborted).toBe(true);
    release([marker("a")]);
    expect((await result).has("a")).toBe(false);
  });
});
