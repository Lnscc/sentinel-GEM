import { expect, test } from "@playwright/test";

const from = "2026-11-17T12:00:00Z";
const to = "2026-11-18T12:00:00Z";
const report = {
  generatedAt: to,
  period: { from, to },
  visibleVersion: "story-mvp-title-embedding-24h-v1.3.0",
  metrics: [
    { id: "articles", group: "inventory", label: "Artikel", value: 123456, unit: "Artikel", scope: "INVENTORY", definition: "Gespeicherte Artikel aller Quellen, unabhängig vom gewählten Zeitraum.", from: null, to: null, unavailableReason: null },
    { id: "sources", group: "inventory", label: "Quellen", value: 523, unit: "Domains", scope: "INVENTORY", definition: "Unterschiedliche Domains der gespeicherten Artikel.", from: null, to: null, unavailableReason: null },
    { id: "incoming", group: "processing", label: "Neue Artikel", value: 2789, unit: "Artikel", scope: "PERIOD", definition: "Im Zeitraum erstmals in der Datenbank erfasste Artikel.", from, to, unavailableReason: null },
    { id: "calls", group: "effort", label: "Embedding-Aufrufe", value: 87, unit: "Aufrufe", scope: "RUNTIME", definition: "Tatsächliche Provider-Aufrufe seit Beginn der Laufzeitmessung.", from, to, unavailableReason: null },
    { id: "tokens", group: "effort", label: "Tokenverbrauch", value: null, unit: "Token", scope: "RUNTIME", definition: "Nur vom Provider gemeldeter Tokenverbrauch.", from, to, unavailableReason: "Vor Beginn der Messung wurden keine Tokenwerte erfasst." },
    { id: "cost", group: "effort", label: "Geschätzte Kosten", value: null, unit: "USD", scope: "RUNTIME", definition: "Kosten nur mit nachvollziehbarem Tarif schätzen.", from, to, unavailableReason: "Kein Tarif hinterlegt." },
  ],
  recentRuns: [{ kind: "IMPORT", status: "COMPLETED", startedAt: "2026-11-18T11:55:00Z", completedAt: "2026-11-18T11:56:00Z" }, { kind: "STORY", status: "RUNNING", startedAt: "2026-11-18T11:57:00Z", completedAt: null }],
};

for (const width of [800, 1280]) {
  test(`Statistik direkt bei ${width}px öffnen und aktualisieren`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    let requests = 0;
    let expectedHours = "24";
    await page.route("**/api/statistics?**", async (route) => {
      requests++;
      const parameters = new URL(route.request().url()).searchParams;
      expect(parameters.get("hours")).toBe(expectedHours);
      expect(parameters.has("to")).toBe(false);
      await route.fulfill({ json: report });
    });
    await page.goto("/statistics");
    await expect(page.getByRole("heading", { name: "Statistik", exact: true })).toBeVisible();
    await expect(page.getByText("123.456", { exact: true })).toBeVisible();
    await expect(page.getByText("Nicht verfügbar", { exact: true })).toHaveCount(2);
    await expect(page.getByText("Kein Tarif hinterlegt.", { exact: false })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Bestand", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Zeitraum", exact: true })).toBeVisible();
    expect(await page.locator("html").evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath("statistik.png"), fullPage: true });
    expectedHours = "168";
    await page.getByRole("combobox", { name: "Zeitraum", exact: true }).selectOption("168");
    await expect(page.getByText("123.456", { exact: true })).toBeVisible();
    const requestsBeforeRefresh = requests;
    await page.getByRole("button", { name: "Aktualisieren", exact: true }).click();
    await expect(page.getByRole("button", { name: "Aktualisieren", exact: true })).toBeEnabled();
    expect(requests).toBe(requestsBeforeRefresh + 1);
    await page.getByRole("heading", { name: "Letzte Verarbeitungsläufe", exact: true }).scrollIntoViewIfNeeded();
    await expect(page.getByText("COMPLETED", { exact: true })).toBeVisible();
    await expect(page.getByText("Nicht abgeschlossen", { exact: true })).toBeVisible();
  });
}

test("Ladefehler anzeigen, erneut laden und leeren Zeitraum erklären", async ({ page }) => {
  let fail = true;
  await page.route("**/api/statistics?**", (route) => fail
    ? route.fulfill({ status: 503, json: { code: "statistics_unavailable", message: "Statistics could not be loaded" } })
    : route.fulfill({ json: { ...report, recentRuns: [], metrics: report.metrics.map((metric) => metric.id === "incoming" ? { ...metric, value: 0 } : metric) } }));
  await page.goto("/statistics");
  await expect(page.getByRole("alert")).toContainText("HTTP 503");
  await expect(page.getByText("123.456", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath("statistik-fehler.png") });
  fail = false;
  await page.getByRole("button", { name: "Erneut versuchen" }).click();
  await expect(page.getByText("0", { exact: true })).toBeVisible();
  await page.getByText("Keine Verarbeitungsläufe in diesem Zeitraum vorhanden.").scrollIntoViewIfNeeded();
  await expect(page.getByText("Keine Verarbeitungsläufe in diesem Zeitraum vorhanden.")).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
});
