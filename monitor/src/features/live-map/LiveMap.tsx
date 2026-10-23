import { useEffect, useMemo, useRef } from "react";
import { Box } from "@mui/material";
import { Map, NavigationControl, setWorkerUrl, type GeoJSONSource } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import type { Story } from "../stories/storyTypes";

setWorkerUrl(workerUrl);
const SOURCE = "stories";
const CLUSTERS = "story-clusters";
const POINTS = "story-points";

function hasValidLocation(story: Story) {
  const location = story.location;
  return location && Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
    && Math.abs(location.latitude) <= 90 && Math.abs(location.longitude) <= 180;
}

type Props = { stories: Story[]; selectedId?: string; onSelect: (id: string) => void };

export default function LiveMap({ stories, selectedId, onSelect }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const locatedStories = useMemo(() => stories.filter(hasValidLocation), [stories]);

  useEffect(() => {
    if (!container.current) return;
    const map = new Map({ container: container.current, style: import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/liberty", center: [0, 20], zoom: 1.2, minZoom: 1 });
    map.addControl(new NavigationControl({ showCompass: false }), "top-right");
    map.getCanvas().setAttribute("aria-label", "Interaktive Weltkarte der aktuellen Ereignisse");
    map.on("load", () => {
      map.addSource(SOURCE, { type: "geojson", cluster: true, clusterMaxZoom: 10, clusterRadius: 48, data: { type: "FeatureCollection", features: locatedStories.map((story) => ({ type: "Feature", geometry: { type: "Point", coordinates: [story.location!.longitude, story.location!.latitude] }, properties: { storyId: story.id, selected: story.id === selectedId } })) } });
      map.addLayer({ id: CLUSTERS, type: "circle", source: SOURCE, filter: ["has", "point_count"], paint: { "circle-color": "#13795b", "circle-radius": ["step", ["get", "point_count"], 18, 10, 24], "circle-stroke-color": "#ffffff", "circle-stroke-width": 3 } });
      map.addLayer({ id: "story-cluster-count", type: "symbol", source: SOURCE, filter: ["has", "point_count"], layout: { "text-field": ["get", "point_count_abbreviated"], "text-size": 13 }, paint: { "text-color": "#ffffff" } });
      map.addLayer({ id: POINTS, type: "circle", source: SOURCE, filter: ["!", ["has", "point_count"]], paint: { "circle-color": ["case", ["get", "selected"], "#d97706", "#13795b"], "circle-radius": ["case", ["get", "selected"], 12, 9], "circle-stroke-color": "#ffffff", "circle-stroke-width": 3 } });
      map.on("click", CLUSTERS, async (event) => {
        const feature = event.features?.[0];
        if (!feature || feature.geometry.type !== "Point") return;
        const zoom = await (map.getSource(SOURCE) as GeoJSONSource).getClusterExpansionZoom(Number(feature.properties.cluster_id));
        map.easeTo({ center: feature.geometry.coordinates as [number, number], zoom });
      });
      map.on("click", POINTS, (event) => {
        const id = event.features?.[0]?.properties.storyId;
        if (id) onSelect(String(id));
      });
    });
    return () => map.remove();
  }, [locatedStories, onSelect, selectedId]);

  return <Box ref={container} sx={{ position: "absolute", inset: 0, "& .maplibregl-map": { fontFamily: "inherit" } }} />;
}
