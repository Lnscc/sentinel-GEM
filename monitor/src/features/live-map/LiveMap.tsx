import { useEffect, useMemo, useRef, type ReactNode } from "react";
import { flushSync } from "react-dom";
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

type RenderedMarker = { marker: Marker; root: Root; signature: string };

function createMarker(content: ReactNode, coordinates: [number, number], signature: string, anchor?: "bottom") {
  const element = document.createElement("div");
  const root = createRoot(element);
  flushSync(() => root.render(content));
  return { marker: new Marker({ element, anchor }).setLngLat(coordinates), root, signature };
}

function removeMarkers(markers: RenderedMarker[]) {
  markers.forEach(({ marker, root }) => { marker.remove(); root.unmount(); });
}

export type MapBounds = { west: number; south: number; east: number; north: number };
type Props = { stories: Story[]; selectedId?: string; initialBounds?: MapBounds; worldViewReset: number; onSelect: (id: string) => void; onBoundsChange: (bounds: MapBounds) => void };

function cameraPosition(map: Map) {
  const center = map.getCenter();
  return JSON.stringify([center.lng, center.lat, map.getZoom()]);
}

function normalizeLongitude(value: number) {
  return ((value + 180) % 360 + 360) % 360 - 180;
}

function normalizeBounds(bounds: MapBounds): MapBounds {
  if (bounds.east - bounds.west >= 360) return { ...bounds, west: -180, east: 180 };
  return { ...bounds, west: normalizeLongitude(bounds.west), east: normalizeLongitude(bounds.east) };
}

export default function LiveMap({ stories, selectedId, initialBounds, worldViewReset, onSelect, onBoundsChange }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Map>(null);
  const globalCamera = useRef<string | undefined>(undefined);
  const resetting = useRef(false);
  const markersRef = useRef<RenderedMarker[]>([]);
  const renderVersion = useRef(0);
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
    const map = new Map({
      container: container.current,
      style: import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/liberty",
      center: [0, 20],
      zoom: 1.2,
      minZoom: 1,
      bounds: initialBounds ? [[initialBounds.west, initialBounds.south], [initialBounds.east, initialBounds.north]] : undefined,
    });
    mapRef.current = map;
    const initialCamera = cameraPosition(map);
    globalCamera.current = initialBounds ? undefined : cameraPosition(map);
    map.addControl(new NavigationControl({ showCompass: false }), "top-right");
    map.getCanvas().setAttribute("aria-label", "Interaktive Weltkarte der aktuellen Ereignisse");
    const reportBounds = () => {
      if (resetting.current || cameraPosition(map) === globalCamera.current) return;
      globalCamera.current = undefined;
      const bounds = map.getBounds();
      onBoundsChange(normalizeBounds({ west: bounds.getWest(), south: bounds.getSouth(), east: bounds.getEast(), north: bounds.getNorth() }));
    };

    const renderMarkers = async () => {
      const version = ++renderVersion.current;
      const source = map.getSource(SOURCE) as GeoJSONSource | undefined;
      if (!source) return;
      const features = dataRef.current.features.length ? map.querySourceFeatures(SOURCE) : [];
      const unique = features.filter((feature, index) => {
        const key = feature.properties.cluster ? `cluster-${feature.properties.cluster_id}` : `story-${feature.properties.storyId}`;
        return features.findIndex((candidate) => (candidate.properties.cluster ? `cluster-${candidate.properties.cluster_id}` : `story-${candidate.properties.storyId}`) === key) === index;
      });
      const created: RenderedMarker[] = [];
      const markerFor = (content: ReactNode, coordinates: [number, number], markerSignature: string, anchor?: "bottom") => {
        const existing = markersRef.current.find((marker) => marker.signature === markerSignature);
        if (existing) {
          flushSync(() => existing.root.render(content));
          return existing;
        }
        const marker = createMarker(content, coordinates, markerSignature, anchor);
        created.push(marker);
        return marker;
      };
      const next = await Promise.all(unique.map(async (feature) => {
        if (feature.geometry.type !== "Point") return null;
        const coordinates = feature.geometry.coordinates as [number, number];
        if (feature.properties.cluster) {
          const clusterId = Number(feature.properties.cluster_id);
          const leaves = await source.getClusterLeaves(clusterId, 3, 0);
          if (version !== renderVersion.current) return null;
          return markerFor(
            <ClusterMapMarker
              imageUrls={leaves.map((leaf) => leaf.properties?.imageUrl)}
              count={String(feature.properties.point_count_abbreviated)}
              onClick={async () => map.easeTo({ center: coordinates, zoom: await source.getClusterExpansionZoom(clusterId) })}
            />,
            coordinates,
            JSON.stringify(["cluster", feature.geometry, feature.properties.point_count, feature.properties.point_count_abbreviated, leaves.map((leaf) => leaf.properties?.imageUrl)]),
          );
        }
        return markerFor(
          <StoryMapMarker title={String(feature.properties.title)} imageUrl={feature.properties.imageUrl} selected={Boolean(feature.properties.selected)} onClick={() => onSelect(String(feature.properties.storyId))} />,
          coordinates,
          JSON.stringify(["story", feature.geometry, feature.properties]),
          "bottom",
        );
      }));
      const rendered = next.filter((marker): marker is RenderedMarker => marker !== null);
      if (version !== renderVersion.current) { removeMarkers(created); return; }
      rendered.forEach((entry) => { if (!markersRef.current.includes(entry)) entry.marker.addTo(map); });
      removeMarkers(markersRef.current.filter((entry) => !rendered.includes(entry)));
      markersRef.current = rendered;
    };

    map.on("sourcedata", (event) => {
      if (event.sourceId === SOURCE && event.sourceDataType === "content") void renderMarkers();
    });

    map.on("load", () => {
      map.addSource(SOURCE, { type: "geojson", cluster: true, clusterMaxZoom: 10, clusterRadius: 64, data: dataRef.current });
      map.addLayer({ id: CLUSTERS, type: "circle", source: SOURCE, filter: ["has", "point_count"], paint: { "circle-opacity": 0, "circle-radius": 1 } });
      map.addLayer({ id: POINTS, type: "circle", source: SOURCE, filter: ["!", ["has", "point_count"]], paint: { "circle-opacity": 0, "circle-radius": 1 } });
      map.on("zoom", () => void renderMarkers());
      map.on("idle", () => void renderMarkers());
      map.on("moveend", reportBounds);
      if (cameraPosition(map) !== initialCamera) reportBounds();
      void renderMarkers();
    });
    return () => {
      renderVersion.current++;
      removeMarkers(markersRef.current);
      markersRef.current = [];
      mapRef.current = null;
      map.remove();
    };
  }, [onBoundsChange, onSelect]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || worldViewReset === 0) return;
    resetting.current = true;
    map.stop();
    map.jumpTo({ center: [0, 20], zoom: 1.2, bearing: 0, pitch: 0 });
    globalCamera.current = cameraPosition(map);
    resetting.current = false;
  }, [worldViewReset]);

  useEffect(() => {
    renderVersion.current++;
    (mapRef.current?.getSource(SOURCE) as GeoJSONSource | undefined)?.setData(dataRef.current);
    if (dataRef.current.features.length === 0) {
      const staleMarkers = markersRef.current;
      markersRef.current = [];
      queueMicrotask(() => removeMarkers(staleMarkers));
    }
  }, [locatedStories, selectedId]);

  return <Box ref={container} sx={{ position: "absolute", inset: 0, "& .maplibregl-map": { fontFamily: "inherit" } }} />;
}
