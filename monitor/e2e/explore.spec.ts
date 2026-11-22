import { expect, test } from "@playwright/test";

const availableFrom = "2026-11-17T12:00:00Z";
const availableTo = "2026-11-18T12:00:00Z";
const storyId = "10000000-0000-0000-0000-000000000051";
const headlines = [
  "Potsdam plant neue Tramverbindung zum Hauptbahnhof",
  "Stadt legt Entwurf für den Umbau des Potsdamer Ufers vor",
  "Potsdamer Kliniken vereinbaren gemeinsame Notfallversorgung",
  "Potsdam öffnet weitere Flächen für das Radwegenetz",
];
const source = {
  id: 51,
  canonicalUrl: "https://example.com/original",
  domain: "example.com",
  title: "Bericht aus Berlin",
  firstSeenAt: availableFrom,
  publishedAt: null,
  mainImageUrl: null,
};

test("Explore bei 800 Pixeln per Tastatur bedienen und Recherche wiederherstellen", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 800, height: 780 });
  await page.route("**/api/explore/countries?**", (route) => route.fulfill({
    json: [{ code: "GM", name: "Deutschland" }],
  }));
  const requests: URL[] = [];
  await page.route("**/api/explore/stories?**", (route) => {
    const url = new URL(route.request().url());
    requests.push(url);
    const number = Number(url.searchParams.get("page") ?? 1);
    const items = Array.from({ length: number === 2 ? 1 : 50 }, (_, index) => ({
      id: number === 2 ? storyId : `10000000-0000-0000-0000-${String(index + 1).padStart(12, "0")}`,
      title: number === 2 ? "Story ohne Kartenposition" : headlines[index] ?? `Meldung aus Potsdam ${index + 1}`,
      domain: "example.com",
      locationName: number === 2 ? null : "Potsdam, Deutschland",
      countryCode: number === 2 ? null : "GM",
      startedAt: availableFrom,
      lastSeenAt: availableTo,
      articleCount: number === 2 ? 1 : 2 + index % 8,
    }));
    return route.fulfill({ json: { items, total: 51, page: number, pageSize: 50, availableFrom, availableTo } });
  });
  await page.route(`**/api/stories/${storyId}`, (route) => route.fulfill({
    json: {
      id: storyId,
      state: "ACTIVE",
      effectiveFrom: availableFrom,
      effectiveTo: availableTo,
      location: null,
      locations: [],
      representativeArticle: source,
      members: [{ article: source, assignmentReason: "test" }],
    },
  }));

  await page.goto("/explore?period=all&q=Berlin&page=2");
  await expect(page.getByRole("heading", { name: "Explore", exact: true })).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "Suchtext" })).toHaveValue("Berlin");
  await expect.poll(() => requests.at(-1)?.searchParams.get("page")).toBe("2");
  await expect(page.getByRole("link", { name: "Lagebild", exact: true })).toBeVisible();
  const result = page.getByRole("link", { name: "Story ohne Kartenposition" });
  await expect(result).toBeVisible();
  await result.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: source.title })).toBeVisible();
  await expect(page.getByRole("link", { name: /externe Quelle/ })).toHaveAttribute("href", source.canonicalUrl);

  await page.goBack();
  await expect(result).toBeFocused();
  await expect(page).toHaveURL(/page=2/);
  expect(await page.getByRole("main").evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("explore-800px.png") });

  const search = page.getByRole("searchbox", { name: "Suchtext" });
  await search.fill("Potsdam");
  await search.press("Enter");
  await expect.poll(() => requests.at(-1)?.searchParams.get("q")).toBe("Potsdam");
  expect(requests.at(-1)!.searchParams.get("page") ?? "1").toBe("1");
  const firstResult = page.getByRole("link", { name: headlines[0], exact: true });
  await expect(firstResult).toBeInViewport();
  const filters = await page.getByRole("complementary", { name: "Filter" }).boundingBox();
  const results = await page.getByRole("region", { name: "Suchergebnisse" }).boundingBox();
  expect(results!.x).toBeGreaterThan(filters!.x + filters!.width);
  await page.screenshot({ path: testInfo.outputPath("explore-trefferliste.png") });
});

test("Artikel ohne Story öffnen und Fehler der Recherche wiederholen", async ({ page }) => {
  let fail = true;
  await page.route("**/api/explore/countries?**", (route) => route.fulfill({
    json: [{ code: "GM", name: "Deutschland" }],
  }));
  await page.route("**/api/explore/articles?**", (route) => fail
    ? route.fulfill({ status: 500, json: {} })
    : route.fulfill({
        json: {
          items: [{
            id: source.id, title: null, url: source.canonicalUrl, domain: source.domain,
            firstSeenAt: source.firstSeenAt, mentionedCountries: [{ code: "GM", name: "Deutschland" }], storyId: null,
          }],
          total: 1, page: 1, pageSize: 50, availableFrom, availableTo,
        },
      }));

  await page.goto("/explore?view=articles&period=all");
  await expect(page.getByRole("alert")).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: "Erneut laden" }).click();
  await expect(page.getByRole("heading", { name: source.canonicalUrl })).toBeVisible();
  await expect(page.getByRole("link", { name: /Originalquelle öffnen/ })).toHaveAttribute("href", source.canonicalUrl);
  await expect(page.getByRole("link", { name: "Zugehörige Story öffnen" })).toHaveCount(0);
});
