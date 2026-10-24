import { useEffect, useMemo, useRef, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { Box } from "@mui/material";
import { Map, Marker, NavigationControl, setWorkerUrl, type GeoJSONSource } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import type { Story } from "../stories/storyTypes";
import { ClusterMapMarker, StoryMapMarker } from "./MapMarkers";

setWorkerUrl(workerUrl);
const SOURCE = "stories";
const CLUSTERS = "story-cluster-hit-area";
const POINTS = "story-point-hit-area";

function hasValidLocation(story: Story) {
  const location = story.location;
  return location && Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
    && Math.abs(location.latitude) <= 90 && Math.abs(location.longitude) <= 180;
}

type RenderedMarker = { marker: Marker; root: Root };

function createMarker(content: ReactNode, coordinates: [number, number], anchor?: "bottom") {
  const element = document.createElement("div");
  const root = createRoot(element);
  root.render(content);
  return { marker: new Marker({ element, anchor }).setLngLat(coordinates), root };
}

function removeMarkers(markers: RenderedMarker[]) {
  markers.forEach(({ marker, root }) => { marker.remove(); root.unmount(); });
}

type Props = { stories: Story[]; selectedId?: string; onSelect: (id: string) => void };

export default function LiveMap({ stories, selectedId, onSelect }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Map>(null);
  const markersRef = useRef<RenderedMarker[]>([]);
  const dataRef = useRef<GeoJSON.FeatureCollection<GeoJSON.Point>>({ type: "FeatureCollection", features: [] });
  const locatedStories = useMemo(() => stories.filter(hasValidLocation), [stories]);
  dataRef.current = {
    type: "FeatureCollection",
    features: locatedStories.map((story) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [story.location!.longitude, story.location!.latitude] },
      properties: {
        storyId: story.id,
        title: story.representativeArticle.title,
        imageUrl: story.representativeArticle.mainImageUrl,
        selected: story.id === selectedId,
      },
    })),
  };

  useEffect(() => {
    if (!container.current) return;
    const map = new Map({ container: container.current, style: import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/liberty", center: [0, 20], zoom: 1.2, minZoom: 1 });
    let renderVersion = 0;
    mapRef.current = map;
    map.addControl(new NavigationControl({ showCompass: false }), "top-right");
    map.getCanvas().setAttribute("aria-label", "Interaktive Weltkarte der aktuellen Ereignisse");

    const renderMarkers = async () => {
      const version = ++renderVersion;
      const source = map.getSource(SOURCE) as GeoJSONSource | undefined;
      if (!source) return;
      const features = map.querySourceFeatures(SOURCE);
      const unique = features.filter((feature, index) => {
        const key = feature.properties.cluster ? `cluster-${feature.properties.cluster_id}` : `story-${feature.properties.storyId}`;
        return features.findIndex((candidate) => (candidate.properties.cluster ? `cluster-${candidate.properties.cluster_id}` : `story-${candidate.properties.storyId}`) === key) === index;
      });
      const next = await Promise.all(unique.map(async (feature) => {
        if (feature.geometry.type !== "Point") return null;
        const coordinates = feature.geometry.coordinates as [number, number];
        if (feature.properties.cluster) {
          const clusterId = Number(feature.properties.cluster_id);
          const leaves = await source.getClusterLeaves(clusterId, 3, 0);
          return createMarker(
            <ClusterMapMarker
              imageUrls={leaves.map((leaf) => leaf.properties?.imageUrl)}
              count={String(feature.properties.point_count_abbreviated)}
              onClick={async () => map.easeTo({ center: coordinates, zoom: await source.getClusterExpansionZoom(clusterId) })}
            />,
            coordinates,
          );
        }
        return createMarker(
          <StoryMapMarker title={String(feature.properties.title)} imageUrl={feature.properties.imageUrl} selected={Boolean(feature.properties.selected)} onClick={() => onSelect(String(feature.properties.storyId))} />,
          coordinates,
          "bottom",
        );
      }));
      const rendered = next.filter((marker): marker is RenderedMarker => marker !== null);
      if (version !== renderVersion) { removeMarkers(rendered); return; }
      removeMarkers(markersRef.current);
      markersRef.current = rendered;
      markersRef.current.forEach(({ marker }) => marker.addTo(map));
    };

    map.on("load", () => {
      map.addSource(SOURCE, { type: "geojson", cluster: true, clusterMaxZoom: 10, clusterRadius: 64, data: dataRef.current });
      map.addLayer({ id: CLUSTERS, type: "circle", source: SOURCE, filter: ["has", "point_count"], paint: { "circle-opacity": 0, "circle-radius": 1 } });
      map.addLayer({ id: POINTS, type: "circle", source: SOURCE, filter: ["!", ["has", "point_count"]], paint: { "circle-opacity": 0, "circle-radius": 1 } });
      map.on("idle", renderMarkers);
      void renderMarkers();
    });
    return () => {
      renderVersion++;
      removeMarkers(markersRef.current);
      markersRef.current = [];
      mapRef.current = null;
      map.remove();
    };
  }, [onSelect]);

  useEffect(() => {
    (mapRef.current?.getSource(SOURCE) as GeoJSONSource | undefined)?.setData(dataRef.current);
  }, [locatedStories, selectedId]);

  return <Box ref={container} sx={{ position: "absolute", inset: 0, "& .maplibregl-map": { fontFamily: "inherit" } }} />;
}
