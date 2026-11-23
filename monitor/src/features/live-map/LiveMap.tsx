import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";
import { createRoot, type Root } from "react-dom/client";
import { Alert, Box } from "@mui/material";
import { Map as MapLibreMap, Marker, NavigationControl, setWorkerUrl, type GeoJSONSource } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { ClusterMapMarker, StoryMapMarker } from "./MapMarkers";
import createMapMarkerLoader from "./mapMarkerLoader";
import type { StoryMapPosition } from "./mapTypes";

setWorkerUrl(workerUrl);
const SOURCE = "stories";
const CLUSTERS = "story-cluster-hit-area";
const POINTS = "story-point-hit-area";
const CLUSTER_RADIUS = 64;
// MapLibre buffers clustered tiles by their radius. Stay inside that buffer at tile boundaries.
const MARKER_PADDING = 60;
const MARKER_UPDATE_MS = 60;

function hasValidLocation(position: StoryMapPosition) {
  return Number.isFinite(position.latitude) && Number.isFinite(position.longitude)
    && Math.abs(position.latitude) <= 90 && Math.abs(position.longitude) <= 180;
}

type MarkerLoader = ReturnType<typeof createMapMarkerLoader>;
type RenderedMarker = { marker: Marker; root: Root; signature: string; clusterId?: number; loader: MarkerLoader };

function createMarker(content: ReactNode, coordinates: [number, number], anchor?: "bottom") {
  const element = document.createElement("div");
  element.inert = true;
  const root = createRoot(element);
  flushSync(() => root.render(content));
  return { marker: new Marker({ element, anchor }).setLngLat(coordinates), root };
}

function removeMarkers(markers: RenderedMarker[]) {
  markers.forEach(({ marker, root }) => { marker.remove(); queueMicrotask(() => root.unmount()); });
}

export type MapBounds = { west: number; south: number; east: number; north: number };
type Props = { positions: StoryMapPosition[]; selectedId?: string; initialBounds?: MapBounds; worldViewReset: number; onSelect: (id: string) => void; onBoundsChange: (bounds: MapBounds) => void };

function cameraPosition(map: MapLibreMap) {
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

export default function LiveMap({ positions, selectedId, initialBounds, worldViewReset, onSelect, onBoundsChange }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap>(null);
  const globalCamera = useRef<string | undefined>(undefined);
  const resetting = useRef(false);
  const markersRef = useRef<RenderedMarker[]>([]);
  const renderVersion = useRef(0);
  const awaitingSource = useRef(true);
  const markerLoader = useRef<MarkerLoader | null>(null);
  const renderMarkersRef = useRef<(() => Promise<void>) | null>(null);
  const selectedIdRef = useRef(selectedId);
  selectedIdRef.current = selectedId;
  const [markerError, setMarkerError] = useState<string>();
  const dataRef = useRef<GeoJSON.FeatureCollection<GeoJSON.Point>>({ type: "FeatureCollection", features: [] });
  const locatedPositions = useMemo(() => positions.filter(hasValidLocation), [positions]);
  const storyCoordinates = useMemo(() => new Map(locatedPositions.map(
    ({ id, longitude, latitude }) => [id, [longitude, latitude] as [number, number]],
  )), [locatedPositions]);
  const storyCoordinatesRef = useRef(storyCoordinates);
  storyCoordinatesRef.current = storyCoordinates;
  dataRef.current = {
    type: "FeatureCollection",
    features: locatedPositions.map((position) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [position.longitude, position.latitude] },
      properties: { storyId: position.id },
    })),
  };

  useEffect(() => {
    if (!container.current) return;
    const map = new MapLibreMap({
      container: container.current,
      style: import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/liberty",
      center: [0, 20],
      zoom: 1.2,
      minZoom: 1,
      bounds: initialBounds ? [[initialBounds.west, initialBounds.south], [initialBounds.east, initialBounds.north]] : undefined,
    });
    mapRef.current = map;
    awaitingSource.current = true;
    const visibility = new IntersectionObserver((entries) => {
      for (const { target, isIntersecting } of entries) (target as HTMLElement).inert = !isIntersecting;
    }, { root: container.current });
    const removeRenderedMarkers = (markers: RenderedMarker[]) => {
      markers.forEach(({ marker }) => visibility.unobserve(marker.getElement()));
      removeMarkers(markers);
    };
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

    let lastQuery: { loader: MarkerLoader; key: string } | undefined;
    const clusterLeaves = new Map<number, Promise<string[]>>();
    const renderMarkers = async () => {
      const source = map.getSource(SOURCE) as GeoJSONSource | undefined;
      const loader = markerLoader.current;
      if (!source || !loader) return;
      if (!dataRef.current.features.length) {
        removeRenderedMarkers(markersRef.current);
        markersRef.current = [];
        lastQuery = undefined;
        clusterLeaves.clear();
        return;
      }
      if (awaitingSource.current) {
        if (!map.isSourceLoaded(SOURCE)) return;
        awaitingSource.current = false;
      }
      const { clientWidth, clientHeight } = map.getCanvas();
      const { lng: centerLongitude } = map.getCenter();
      // Rendered-feature queries omit points in the tile buffer beyond the tile edge.
      const features = map.querySourceFeatures(SOURCE);
      const unique = new Map<string, { feature: (typeof features)[number]; coordinates: [number, number] }>();
      for (const feature of features) {
        if (feature.geometry.type !== "Point") continue;
        const isCluster = !!feature.properties.cluster;
        const coordinates = isCluster
          ? feature.geometry.coordinates as [number, number]
          : storyCoordinatesRef.current.get(String(feature.properties.storyId));
        if (!coordinates) continue;
        const [longitude, latitude] = coordinates;
        const { x, y } = map.project([centerLongitude + normalizeLongitude(longitude - centerLongitude), latitude]);
        if (x < -MARKER_PADDING || x > clientWidth + MARKER_PADDING || y < -MARKER_PADDING || y > clientHeight + MARKER_PADDING) continue;
        const id = isCluster ? `cluster-${feature.properties.cluster_id}` : `story-${feature.properties.storyId}`;
        if (!unique.has(id)) unique.set(id, { feature, coordinates });
      }
      const key = JSON.stringify([selectedIdRef.current, [...unique.keys()].sort().map((id) => [id, unique.get(id)?.coordinates])]);
      if (lastQuery?.loader === loader && lastQuery.key === key) return;
      if (lastQuery?.loader !== loader) clusterLeaves.clear();
      lastQuery = { loader, key };
      const version = ++renderVersion.current;
      const created: RenderedMarker[] = [];
      try {
        const entries = await Promise.all([...unique.values()].map(async ({ feature, coordinates }) => {
          if (feature.properties.cluster) {
            const clusterId = Number(feature.properties.cluster_id);
            const leaves = clusterLeaves.get(clusterId) ?? source.getClusterLeaves(clusterId, 3, 0)
              .then((features) => features.map((leaf) => String(leaf.properties?.storyId)));
            clusterLeaves.set(clusterId, leaves);
            return { feature, coordinates, clusterId, ids: await leaves };
          }
          return { feature, coordinates, clusterId: undefined, ids: [String(feature.properties.storyId)] };
        }));
        if (version !== renderVersion.current) return;
        const renderLoaded = () => {
          const details = loader.cache;
          const previous = new Map(markersRef.current.map((marker) => [marker.signature, marker]));
          const markerFor = (content: ReactNode, coordinates: [number, number], signature: string, clusterId?: number) => {
            const existing = previous.get(signature);
            if (existing) {
              if (clusterId !== undefined && (existing.loader !== loader || existing.clusterId !== clusterId)) {
                flushSync(() => existing.root.render(content));
                existing.clusterId = clusterId;
              }
              existing.loader = loader;
              return existing;
            }
            const anchor = clusterId === undefined ? "bottom" : undefined;
            const marker = { ...createMarker(content, coordinates, anchor), signature, clusterId, loader };
            created.push(marker);
            return marker;
          };
          const next = entries.filter(({ ids }) => ids.every((id) => details.has(id))).map(({ feature, coordinates, clusterId, ids }) => {
            if (clusterId !== undefined) {
              const imageUrls = ids.map((id) => details.get(id)?.imageUrl);
              return markerFor(
                <ClusterMapMarker
                  imageUrls={imageUrls}
                  count={String(feature.properties.point_count_abbreviated)}
                  onClick={async () => {
                    if (loader !== markerLoader.current) return;
                    try {
                      const zoom = await source.getClusterExpansionZoom(clusterId);
                      if (loader === markerLoader.current) map.easeTo({ center: coordinates, zoom });
                    } catch (reason: unknown) {
                      if (loader === markerLoader.current) setMarkerError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
                    }
                  }}
                />,
                coordinates,
                JSON.stringify(["cluster", coordinates, feature.properties.point_count, feature.properties.point_count_abbreviated, imageUrls]),
                clusterId,
              );
            }
            const detail = details.get(ids[0]);
            if (!detail) return null;
            const selected = detail.id === selectedIdRef.current;
            return markerFor(
              <StoryMapMarker title={detail.title || "Unbenanntes Ereignis"} imageUrl={detail.imageUrl} selected={selected} onClick={() => onSelect(detail.id)} />,
              coordinates,
              JSON.stringify(["story", coordinates, detail, selected]),
            );
          });
          const rendered = new Set(next.filter((marker): marker is RenderedMarker => marker !== null));
          for (const entry of created) {
            entry.marker.addTo(map);
            visibility.observe(entry.marker.getElement());
          }
          created.length = 0;
          removeRenderedMarkers(markersRef.current.filter((entry) => !rendered.has(entry)));
          markersRef.current = [...rendered];
        };
        const missing = entries.flatMap(({ ids }) => ids.filter((id) => !loader.cache.has(id)));
        if (missing.length && markersRef.current.every((marker) => marker.loader === loader)) renderLoaded();
        await loader.load(missing);
        if (version !== renderVersion.current) return;
        renderLoaded();
        setMarkerError(undefined);
      } catch (reason: unknown) {
        removeRenderedMarkers(created);
        if (version === renderVersion.current) {
          lastQuery = undefined;
          setMarkerError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
        }
      }
    };
    renderMarkersRef.current = renderMarkers;

    let renderTimer: number | undefined;
    const scheduleMarkers = () => {
      if (renderTimer !== undefined) return;
      renderTimer = window.setTimeout(() => {
        renderTimer = undefined;
        void renderMarkers();
      }, MARKER_UPDATE_MS);
    };

    map.on("load", () => {
      map.addSource(SOURCE, { type: "geojson", cluster: true, clusterMaxZoom: 10, clusterRadius: CLUSTER_RADIUS, data: dataRef.current });
      map.addLayer({ id: CLUSTERS, type: "circle", source: SOURCE, filter: ["has", "point_count"], paint: { "circle-opacity": 0, "circle-radius": 1 } });
      map.addLayer({ id: POINTS, type: "circle", source: SOURCE, filter: ["!", ["has", "point_count"]], paint: { "circle-opacity": 0, "circle-radius": 1 } });
      map.on("render", scheduleMarkers);
      map.on("moveend", reportBounds);
      if (cameraPosition(map) !== initialCamera) reportBounds();
      scheduleMarkers();
    });
    return () => {
      renderVersion.current++;
      window.clearTimeout(renderTimer);
      visibility.disconnect();
      markerLoader.current?.abort();
      markerLoader.current = null;
      renderMarkersRef.current = null;
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
    awaitingSource.current = true;
    markerLoader.current?.abort();
    markerLoader.current = createMapMarkerLoader();
    setMarkerError(undefined);
    (mapRef.current?.getSource(SOURCE) as GeoJSONSource | undefined)?.setData(dataRef.current);
    if (!dataRef.current.features.length) queueMicrotask(() => void renderMarkersRef.current?.());
  }, [locatedPositions]);

  useEffect(() => { void renderMarkersRef.current?.(); }, [selectedId]);

  return <>
    <Box ref={container} sx={{ position: "absolute", inset: 0, "& .maplibregl-map": { fontFamily: "inherit" } }} />
    {markerError && <Alert severity="error" sx={{ position: "absolute", bottom: 16, left: 16, right: 56, zIndex: 2 }}>Die Kartenbilder konnten nicht geladen werden. {markerError}</Alert>}
  </>;
}
