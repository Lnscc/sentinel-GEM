import { expect, test, type Locator, type Page } from "@playwright/test";

const title = "Nachrichtenereignis".repeat(12);
const country = "Vereinigtes Königreich Großbritannien und Nordirland";
const story = {
  id: "desktop-story", state: "PUBLISHED",
  effectiveFrom: "2026-11-18T10:00:00Z", effectiveTo: "2026-11-18T11:00:00Z",
  location: { name: "London", countryCode: "UK", latitude: 51.5, longitude: -0.1 },
  representativeArticle: {
    id: 1, title, domain: `${"nachrichten".repeat(12)}.example.com`,
    canonicalUrl: "https://example.com/article", firstSeenAt: "2026-11-18T10:00:00Z",
  },
  memberCount: 1, sourceCount: 1,
};

async function fitsHorizontally(locator: Locator, checkContent = true) {
  const bounds = await locator.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.width).toBeGreaterThan(0);
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(locator.page().viewportSize()!.width + 1);
  if (checkContent) {
    expect(await locator.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  }
}

async function layoutFits(page: Page) {
  for (const locator of [
    page.locator("html"), page.getByRole("main"), page.getByRole("form"),
    page.getByRole("region", { name: "Globales Lagebild", exact: true }),
    page.getByRole("region", { name: "Weltkarte", exact: true }),
    page.getByRole("complementary"),
  ]) await fitsHorizontally(locator);
  const map = await page.getByRole("region", { name: "Weltkarte", exact: true }).boundingBox();
  expect(map!.width).toBeGreaterThan(250);
  expect(map!.height).toBeGreaterThan(150);
  const panel = await page.getByRole("complementary").boundingBox();
  expect(panel!.y + panel!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
}

test.beforeEach(async ({ page }) => {
  // Keep the real MapLibre canvas and controls; avoid external tile dependencies.
  await page.route("https://tiles.openfreemap.org/styles/liberty", (route) => route.fulfill({
    json: { version: 8, sources: {}, layers: [{ id: "background", type: "background", paint: { "background-color": "#dbeae3" } }] },
  }));
  await page.route("**/api/stories**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/countries")) return route.fulfill({ json: [{ code: "UK", name: country }] });
    if (url.pathname.endsWith("/desktop-story")) return route.fulfill({ json: {
      ...story, members: [{ article: story.representativeArticle }],
    } });
    if (url.searchParams.get("q") === "leer") return route.fulfill({ json: { stories: [], total: 0 } });
    if (url.searchParams.get("q") === "fehler") return route.fulfill({ status: 500, json: {} });
    return route.fulfill({ json: { stories: [story], total: 1 } });
  });
});

for (const width of [800, 960, 1280, 1600]) {
  test(`Lagebild bleibt bei ${width}px bedienbar`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 700 });
    await page.goto("/");
    const list = page.getByRole("list", { name: "Gefilterte Ereignisse" });
    await expect(list.getByRole("heading", { name: title })).toBeVisible();
    await expect(page.getByRole("button", { name: "Zoom in", exact: true })).toBeVisible();
    await layoutFits(page);
    await fitsHorizontally(list);
    await page.screenshot({ path: testInfo.outputPath("liste.png") });

    const search = page.getByRole("searchbox", { name: "Suche" });
    await search.fill("Nachrichten".repeat(15));
    await expect(page).toHaveURL(/q=/);
    const countries = page.getByRole("combobox", { name: "Land", exact: true });
    await countries.fill("Königreich");
    await countries.press("ArrowDown");
    await countries.press("Enter");
    await expect(countries).toHaveValue(country);
    await page.getByRole("combobox", { name: "Zeitraum" }).click();
    await page.getByRole("option", { name: "Letzte 6 Stunden" }).click();
    await expect(page).toHaveURL(/hours=6/);
    await layoutFits(page);
    for (const control of [search, countries, page.getByRole("combobox", { name: "Zeitraum" }), page.getByRole("button", { name: "Filter löschen" })]) {
      // Inputs scroll and selects ellipsize long values inside their bounds.
      await fitsHorizontally(control, false);
    }
    await page.getByRole("button", { name: "Filter löschen" }).click();
    await expect(search).toHaveValue("");
    await expect(countries).toHaveValue("");

    const item = list.getByRole("listitem");
    await item.focus();
    await item.press("Enter");
    await expect(page.getByRole("heading", { name: "Zugehörige Artikel" })).toBeVisible();
    await layoutFits(page);
    await fitsHorizontally(page.getByRole("complementary").locator("article").first());
    const source = page.getByRole("link", { name: /externe Quelle öffnen/ });
    await source.scrollIntoViewIfNeeded();
    await expect(source).toBeInViewport();
    await page.screenshot({ path: testInfo.outputPath("details.png") });
    await page.getByRole("button", { name: /Alle Ereignisse/ }).click();
    await expect(list).toBeVisible();

    await test.step("Marker auswählen und Karte per Tastatur zoomen", async () => {
      await page.getByRole("button", { name: `Ereignis: ${title}`, exact: true }).click();
      await expect(page.getByRole("heading", { name: "Zugehörige Artikel" })).toBeVisible();
      await layoutFits(page);
      await page.getByRole("button", { name: /Alle Ereignisse/ }).click();
      const previousUrl = page.url();
      const zoom = page.getByRole("button", { name: "Zoom in", exact: true });
      await zoom.focus();
      await zoom.press("Enter");
      await expect.poll(() => page.url()).not.toBe(previousUrl);
      await layoutFits(page);
    });
  });
}

test("800px: Leerzustand, Fehler und Größenänderung bleiben erreichbar", async ({ page }) => {
  await page.setViewportSize({ width: 800, height: 600 });
  await page.goto("/");
  const search = page.getByRole("searchbox", { name: "Suche" });
  await search.fill("leer");
  await expect(page.getByText("Kein Ereignis passt zu diesen Filtern.")).toBeVisible();
  await layoutFits(page);
  await search.fill("fehler");
  await expect(page.getByRole("alert")).toContainText("HTTP 500");
  await layoutFits(page);
  await page.getByRole("button", { name: "Filter löschen" }).click();
  await expect(page.getByRole("listitem")).toBeVisible();
  await page.setViewportSize({ width: 1600, height: 900 });
  await layoutFits(page);
  await page.setViewportSize({ width: 800, height: 600 });
  await layoutFits(page);
  const canvas = page.locator("canvas");
  await expect.poll(async () => (await canvas.boundingBox())!.width).toBeLessThan(800);
});
