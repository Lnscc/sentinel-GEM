import { useEffect, useMemo, useRef } from "react";
import { Box, Typography } from "@mui/material";
import { Map, NavigationControl, Popup, setWorkerUrl, type GeoJSONSource } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import StateMessage from "../../shared/StateMessage";
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

function popupContent(story: Story) {
  const article = document.createElement("article");
  const place = document.createElement("strong");
  const link = document.createElement("a");
  const metadata = document.createElement("p");

  place.textContent = story.location!.name;
  link.href = `#${story.id}`;
  link.textContent = story.representativeArticle.title;
  metadata.textContent = `${new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(story.effectiveTo))} · ${story.memberCount} Artikel`;
  article.append(place, link, metadata);
  article.className = "story-map-popup";
  return article;
}

export default function LiveMap({ stories }: { stories: Story[] }) {
  const container = useRef<HTMLDivElement>(null);
  const locatedStories = useMemo(() => stories.filter(hasValidLocation), [stories]);

  useEffect(() => {
    if (!container.current || locatedStories.length === 0) return;
    const storiesById = new globalThis.Map(locatedStories.map((story) => [story.id, story]));
    const map = new Map({
      container: container.current,
      style: import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/liberty",
      center: [0, 20],
      zoom: 1.2,
      minZoom: 1,
    });
    map.addControl(new NavigationControl({ showCompass: false }), "top-right");
    map.getCanvas().setAttribute("aria-label", "Interaktive Weltkarte der aktuellen Stories");

    map.on("load", () => {
      map.addSource(SOURCE, {
        type: "geojson",
        cluster: true,
        clusterMaxZoom: 10,
        clusterRadius: 48,
        data: {
          type: "FeatureCollection",
          features: locatedStories.map((story) => ({
            type: "Feature",
            geometry: { type: "Point", coordinates: [story.location!.longitude, story.location!.latitude] },
            properties: { storyId: story.id },
          })),
        },
      });
      map.addLayer({
        id: CLUSTERS,
        type: "circle",
        source: SOURCE,
        filter: ["has", "point_count"],
        paint: { "circle-color": "#39735a", "circle-radius": ["step", ["get", "point_count"], 18, 10, 24], "circle-stroke-color": "#fffefa", "circle-stroke-width": 3 },
      });
      map.addLayer({
        id: "story-cluster-count",
        type: "symbol",
        source: SOURCE,
        filter: ["has", "point_count"],
        layout: { "text-field": ["get", "point_count_abbreviated"], "text-size": 13 },
        paint: { "text-color": "#ffffff" },
      });
      map.addLayer({
        id: POINTS,
        type: "circle",
        source: SOURCE,
        filter: ["!", ["has", "point_count"]],
        paint: { "circle-color": "#39735a", "circle-radius": 9, "circle-stroke-color": "#fffefa", "circle-stroke-width": 3 },
      });

      map.on("click", CLUSTERS, async (event) => {
        const feature = event.features?.[0];
        if (!feature || feature.geometry.type !== "Point") return;
        const zoom = await (map.getSource(SOURCE) as GeoJSONSource).getClusterExpansionZoom(Number(feature.properties.cluster_id));
        map.easeTo({ center: feature.geometry.coordinates as [number, number], zoom });
      });
      map.on("click", POINTS, (event) => {
        const feature = event.features?.[0];
        if (!feature || feature.geometry.type !== "Point") return;
        const story = storiesById.get(String(feature.properties.storyId));
        if (story) new Popup().setLngLat(feature.geometry.coordinates as [number, number]).setDOMContent(popupContent(story)).addTo(map);
      });
    });

    return () => map.remove();
  }, [locatedStories]);

  return (
    <Box component="section" aria-labelledby="map-heading" sx={{ mb: 5 }}>
      <Typography id="map-heading" variant="h2" sx={{ mb: 0.5 }}>Weltkarte</Typography>
      <Typography color="text.secondary" sx={{ mb: 2 }}>Kartenpunkte öffnen, um die zugehörigen Stories anzuzeigen.</Typography>
      {locatedStories.length === 0 ? (
        <StateMessage>Für diese Stories sind keine Ortsdaten verfügbar.</StateMessage>
      ) : (
        <Box ref={container} sx={{ height: { xs: 360, sm: 520 }, overflow: "hidden", border: 1, borderColor: "divider", borderRadius: 2, "& .story-map-popup": { display: "grid", gap: 1, maxWidth: 280 }, "& .story-map-popup a": { color: "primary.main", fontWeight: 700 }, "& .story-map-popup p": { m: 0, color: "text.secondary" } }} />
      )}
    </Box>
  );
}
