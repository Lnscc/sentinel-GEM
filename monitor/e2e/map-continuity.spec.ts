import { expect, test } from "@playwright/test";

for (const scenario of ["identische Antwort", "zusätzliches Ereignis", "unveränderter Cluster"]) {
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
    const clustered = scenario === "unveränderter Cluster";
    const addStory = scenario !== "identische Antwort";
    const initialStories = clustered ? [story, { ...story, id: "berlin-2", location: { ...story.location, latitude: 52.53 } }] : [story];
    const nextStories = addStory ? [...initialStories, { ...story, id: "madrid", location: { ...story.location, name: "Madrid", latitude: 40.42, longitude: -3.7 }, representativeArticle: { ...story.representativeArticle, title: "Madrid kommt hinzu" } }] : initialStories;
    let finishRequest!: () => Promise<void>;
    let requests = 0;
    let delayRequests = false;
    await page.route("**/api/stories**", async (route) => {
      if (new URL(route.request().url()).pathname.endsWith("/countries")) return route.fulfill({ json: [] });
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
    await page.screenshot({ path: testInfo.outputPath("marker-waehrend-laden.png") });
    await finishRequest();
    await expect(page.getByText(/Aktualisierung läuft/)).toHaveCount(0);
    await expect(marker).toBeVisible();
    if (addStory) await expect(page.getByRole("button", { name: "Ereignis: Madrid kommt hinzu", exact: true })).toBeVisible();
    expect(await original!.evaluate((node) => node.isConnected)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath("marker-nach-laden.png") });
  });
}
