import type { MapBounds } from "../live-map/LiveMap";
import { DEFAULT_FILTERS, type StoryFilterValues } from "./StoryFilters";

export type LagebildUrlState = { filters: StoryFilterValues; mapBounds?: MapBounds; selectedId?: string };

const HOURS = new Set(["6", "12", "24"]);

export function readLagebildUrl(search: string): LagebildUrlState {
  const params = new URLSearchParams(search);
  const hours = params.get("hours") ?? DEFAULT_FILTERS.hours;
  const query = params.get("q") ?? "";
  const region = params.get("region") ?? "";
  const selectedId = params.get("story") || undefined;
  const boundsValue = params.get("bounds");
  const bounds = boundsValue?.split(",").map(Number);
  const mapBounds = bounds?.length === 4 ? { west: bounds[0], south: bounds[1], east: bounds[2], north: bounds[3] } : undefined;
  const validBounds = !boundsValue || Boolean(mapBounds && Object.values(mapBounds).every(Number.isFinite)
    && mapBounds.west >= -180 && mapBounds.west <= 180 && mapBounds.east >= -180 && mapBounds.east <= 180
    && mapBounds.south >= -90 && mapBounds.south < mapBounds.north && mapBounds.north <= 90 && mapBounds.west !== mapBounds.east);

  if (!HOURS.has(hours) || query.length > 200 || region.length > 32 || (selectedId?.length ?? 0) > 100 || !validBounds) {
    return { filters: DEFAULT_FILTERS };
  }
  return { filters: { query, hours, region }, mapBounds, selectedId };
}

export function writeLagebildUrl({ filters, mapBounds, selectedId }: LagebildUrlState) {
  const params = new URLSearchParams();
  if (filters.hours !== DEFAULT_FILTERS.hours) params.set("hours", filters.hours);
  if (filters.region) params.set("region", filters.region);
  if (filters.query) params.set("q", filters.query);
  if (mapBounds) params.set("bounds", [mapBounds.west, mapBounds.south, mapBounds.east, mapBounds.north].map((value) => Number(value.toFixed(5))).join(","));
  if (selectedId) params.set("story", selectedId);
  return params.toString();
}
