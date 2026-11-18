import { expect, test } from "@playwright/test";

test("Kartenausschnitt: Zoom, Verschieben, URL und beide Resets", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.route("https://tiles.openfreemap.org/styles/liberty", (route) => route.fulfill({
    json: { version: 8, sources: {}, layers: [{ id: "background", type: "background", paint: { "background-color": "#dbeae3" } }] },
  }));
  const requests: URL[] = [];
  await page.route("**/api/stories**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/countries")) return route.fulfill({ json: [{ code: "GM", name: "Deutschland" }] });
    requests.push(url);
    return route.fulfill({ json: { stories: [], total: 0 } });
  });
  await page.goto("/?q=Berlin&country=GM&hours=6");
  const zoom = page.getByRole("button", { name: "Zoom in", exact: true });
  const world = page.getByRole("button", { name: "Zur Weltansicht", exact: true });
  await expect(zoom).toBeVisible();
  await expect(world).toHaveCount(0);
  await expect.poll(() => requests.length).toBeGreaterThan(0);
  expect(requests.at(-1)!.searchParams.has("bounds")).toBe(false);

  await zoom.click();
  await expect(world).toBeVisible();
  await expect(page.getByText("Kartenausschnitt aktiv", { exact: true })).toBeVisible();
  await expect(page.getByText("Ergebnisanzahl im aktiven Kartenausschnitt.")).toBeVisible();
  await expect(page.getByText(/Kein Ereignis passt.*im aktiven Kartenausschnitt/)).toBeVisible();
  const zoomedBounds = new URL(page.url()).searchParams.get("bounds");
  const canvas = page.locator("canvas");
  const box = (await canvas.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 140, box.y + box.height / 2 + 50, { steps: 12 });
  await page.mouse.up();
  await expect.poll(() => new URL(page.url()).searchParams.get("bounds")).not.toBe(zoomedBounds);
  await page.reload();
  await expect(world).toBeVisible();
  await expect(zoom).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("kartenausschnitt.png") });

  await world.click();
  await expect(world).toHaveCount(0);
  await expect.poll(() => requests.at(-1)!.searchParams.has("bounds")).toBe(false);
  await expect(page).toHaveURL(/q=Berlin/);
  await expect(page).toHaveURL(/country=GM/);
  await expect(page).toHaveURL(/hours=6/);
  await page.setViewportSize({ width: 800, height: 700 });
  await expect(world).toHaveCount(0);
  expect(new URL(page.url()).searchParams.has("bounds")).toBe(false);

  await zoom.click();
  await expect(world).toBeVisible();
  await page.getByRole("button", { name: "Filter löschen", exact: true }).click();
  await expect(page).toHaveURL("http://127.0.0.1:5174/");
  await expect(page.getByRole("searchbox", { name: "Suche" })).toHaveValue("");
  await expect(page.getByRole("combobox", { name: "Land", exact: true })).toHaveValue("");
  await expect(page.getByRole("combobox", { name: "Zeitraum" })).toContainText("Letzte 24 Stunden");
  await expect.poll(() => requests.at(-1)!.searchParams.has("bounds")).toBe(false);
  await expect(page.getByText("Kein Ereignis passt zu diesen Filtern.")).toBeVisible();
  await page.reload();
  await expect(zoom).toBeVisible();
  await expect(world).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("weltansicht.png") });
});
