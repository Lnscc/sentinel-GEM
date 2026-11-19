import { expect, test } from "@playwright/test";

for (const scenario of ["identische Antwort", "geänderte Liste", "unveränderter Cluster", "mehr als 100 Positionen"]) {
  test(`Marker bleiben beim Verschieben erhalten (${scenario})`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.route("https://tiles.openfreemap.org/styles/liberty", (route) => route.fulfill({
      json: { version: 8, sources: {}, layers: [{ id: "background", type: "background", paint: { "background-color": "#dbeae3" } }] },
    }));
    await page.route("https://images.example.com/**", (route) => route.fulfill({
      contentType: "image/svg+xml", body: '<svg xmlns="http://www.w3.org/2000/svg" width="82" height="50"><rect width="82" height="50" fill="#13795b"/></svg>',
    }));
    const story = {
      id: "berlin", effectiveFrom: "2026-11-19T10:00:00Z", effectiveTo: "2026-11-19T11:00:00Z",
      location: { name: "Berlin", countryCode: "GM", latitude: 52.52, longitude: 13.405 },
      representativeArticle: { domain: "example.com", title: "Berlin bleibt sichtbar", mainImageUrl: "https://images.example.com/berlin.svg" },
      memberCount: 2, sourceCount: 2,
    };
    const clustered = scenario === "unveränderter Cluster" || scenario === "mehr als 100 Positionen";
    const addStory = scenario !== "identische Antwort";
    const initialStories = clustered ? [story, { ...story, id: "berlin-2", location: { ...story.location, latitude: 52.53 } }] : [story];
    const nextStories = addStory ? [...initialStories, { ...story, id: "madrid", location: { ...story.location, name: "Madrid", latitude: 40.42, longitude: -3.7 }, representativeArticle: { ...story.representativeArticle, title: "Madrid kommt hinzu" } }] : initialStories;
    const farPositions = scenario === "mehr als 100 Positionen" ? Array.from({ length: 148 }, (_, index) => ({ id: `sydney-${index}`, latitude: -33.86, longitude: 151.21 })) : [];
    const positions = [...farPositions, ...nextStories.map((value) => ({ id: value.id, latitude: value.location.latitude, longitude: value.location.longitude }))];
    let positionRequests = 0;
    const metadataRequests: string[][] = [];
    let finishRequest!: () => Promise<void>;
    let requests = 0;
    let delayRequests = false;
    await page.route("**/api/stories**", async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith("/countries")) return route.fulfill({ json: [] });
      if (url.pathname.endsWith("/map-positions")) {
        positionRequests++;
        expect(url.searchParams.has("bounds")).toBe(false);
        expect(url.searchParams.has("limit")).toBe(false);
        return route.fulfill({ json: positions });
      }
      if (url.pathname.endsWith("/map-markers")) {
        const ids = (url.searchParams.get("ids") ?? "").split(",");
        metadataRequests.push(ids);
        expect(ids.length).toBeLessThanOrEqual(100);
        expect(ids.every((id) => !id.startsWith("sydney-"))).toBe(true);
        return route.fulfill({ json: nextStories.filter((value) => ids.includes(value.id)).map((value) => ({ id: value.id, title: value.representativeArticle.title, imageUrl: value.representativeArticle.mainImageUrl })) });
      }
      requests++;
      if (!delayRequests) return route.fulfill({ json: { stories: initialStories, total: initialStories.length } });
      await new Promise<void>((resolve) => {
        finishRequest = async () => { await route.fulfill({ json: { stories: nextStories, total: nextStories.length } }); resolve(); };
      });
    });
    await page.goto("/?bounds=-10,35,35,65");
    const marker = page.getByRole("button", { name: clustered ? "Cluster mit 2 Ereignissen" : "Ereignis: Berlin bleibt sichtbar", exact: true });
    await expect(marker).toBeVisible();
    const original = await marker.elementHandle();
    const metadataBeforePan = metadataRequests.length;
    const positionsBeforePan = positionRequests;
    const markerCountBeforePan = await page.locator(".live-map-marker").count();
    delayRequests = true;
    const beforePan = requests;
    const canvas = await page.locator("canvas").boundingBox();
    if (!canvas) throw new Error("Kartenfläche fehlt");
    await page.mouse.move(canvas.x + canvas.width / 2, canvas.y + canvas.height * .8);
    await page.mouse.down();
    await page.mouse.move(canvas.x + canvas.width / 2 + 35, canvas.y + canvas.height * .8, { steps: 8 });
    await page.mouse.up();
    await expect.poll(() => requests).toBeGreaterThan(beforePan);
    await expect(page.getByText(/Aktualisierung läuft/)).toBeVisible();
    await expect(page.getByRole("list", { name: "Gefilterte Ereignisse" }).getByRole("listitem")).toHaveCount(0);
    await expect(page.getByText("—", { exact: true })).toBeVisible();
    await expect(marker).toBeVisible();
    expect(await original!.evaluate((node) => node.isConnected)).toBe(true);
    expect(positionRequests).toBe(positionsBeforePan);
    expect(metadataRequests).toHaveLength(metadataBeforePan);
    if (scenario === "mehr als 100 Positionen") expect(positions).toHaveLength(151);
    await page.screenshot({ path: testInfo.outputPath("marker-waehrend-laden.png") });
    await finishRequest();
    await expect(page.getByText(/Aktualisierung läuft/)).toHaveCount(0);
    await expect(marker).toBeVisible();
    if (addStory) await expect(page.getByRole("button", { name: "Ereignis: Madrid kommt hinzu", exact: true })).toBeVisible();
    expect(await original!.evaluate((node) => node.isConnected)).toBe(true);
    await expect(page.locator(".live-map-marker")).toHaveCount(markerCountBeforePan);
    expect(positionRequests).toBe(positionsBeforePan);
    expect(metadataRequests).toHaveLength(metadataBeforePan);
    await page.screenshot({ path: testInfo.outputPath("marker-nach-laden.png") });
  });
}
