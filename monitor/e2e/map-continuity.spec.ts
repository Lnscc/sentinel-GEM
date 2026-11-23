import { expect, test } from "@playwright/test";

test("lädt Markerdetails und Bilder am Kartenrand beim Ziehen vor", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.route("https://tiles.openfreemap.org/styles/liberty", (route) => route.fulfill({
    json: { version: 8, sources: {}, layers: [{ id: "background", type: "background", paint: { "background-color": "#dbeae3" } }] },
  }));

  const positions = [
    { id: "near", latitude: 1.5, longitude: 0.6 },
    { id: "refill", latitude: -1.5, longitude: 2.3 },
    { id: "distant", latitude: 0, longitude: 70 },
  ];
  const metadataRequests: string[] = [];
  const loadedImages: string[] = [];
  await page.route("https://images.example.com/**", async (route) => {
    await route.fulfill({
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="82" height="50"><rect width="82" height="50" fill="#13795b"/></svg>',
    });
    loadedImages.push(new URL(route.request().url()).pathname);
  });
  await page.route("**/api/stories**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/countries")) return route.fulfill({ json: [] });
    if (url.pathname.endsWith("/map-positions")) return route.fulfill({ json: positions });
    if (url.pathname.endsWith("/map-markers")) {
      const ids = (url.searchParams.get("ids") ?? "").split(",");
      metadataRequests.push(...ids);
      return route.fulfill({
        json: positions.filter(({ id }) => ids.includes(id)).map(({ id }) => ({ id, title: id, imageUrl: `https://images.example.com/${id}.svg` })),
      });
    }
    return route.fulfill({ json: { stories: [], total: 0 } });
  });

  // These bounds fit horizontally and put the first marker across the tile boundary at longitude zero.
  await page.goto("/?bounds=-20.2,-2,-0.2,2");
  const nearMarker = page.getByRole("button", { name: "Ereignis: near", exact: true });
  const refillMarker = page.getByRole("button", { name: "Ereignis: refill", exact: true });
  await expect.poll(() => loadedImages).toEqual(["/near.svg"]);
  expect(metadataRequests).toEqual(["near"]);
  await expect(nearMarker).not.toBeInViewport({ ratio: .5 });

  const canvas = await page.getByRole("region", { name: "Weltkarte" }).locator("canvas").boundingBox();
  if (!canvas) throw new Error("Kartenfläche fehlt");
  const x = canvas.x + canvas.width * .8;
  const y = canvas.y + canvas.height * .76;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x - 57, y, { steps: 12 });
  await expect(nearMarker).toBeInViewport({ ratio: .5 });
  await expect.poll(() => nearMarker.locator("img").evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(82);
  await expect.poll(() => loadedImages).toEqual(["/near.svg", "/refill.svg"]);
  await expect(refillMarker).not.toBeInViewport({ ratio: .5 });

  await page.mouse.move(x - 145, y, { steps: 12 });
  await expect(refillMarker).toBeInViewport({ ratio: .5 });
  await expect.poll(() => refillMarker.locator("img").evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(82);
  await page.mouse.up();

  expect(metadataRequests).toEqual(["near", "refill"]);
  expect(loadedImages).toEqual(["/near.svg", "/refill.svg"]);
});

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
