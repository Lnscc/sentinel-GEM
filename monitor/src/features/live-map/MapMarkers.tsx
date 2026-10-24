import { useState, type CSSProperties, type MouseEvent } from "react";

const buttonStyle: CSSProperties = { position: "relative", padding: 0, border: 0, background: "transparent", cursor: "pointer" };

function ImageCard({ url, offset = 0, selected = false }: { url?: string | null; offset?: number; selected?: boolean }) {
  const [failed, setFailed] = useState(false);
  return (
    <span className="live-map-marker__image" style={{
      position: "absolute", right: offset, bottom: offset * .75, display: "block", width: 82, height: 50,
      overflow: "hidden", boxSizing: "border-box", border: `3px solid ${selected ? "#e18212" : "white"}`,
      borderRadius: 13, background: "linear-gradient(135deg, #d7e4de, #a8c4b7)",
      boxShadow: selected ? "0 0 0 4px rgba(225, 130, 18, .24), 0 5px 14px rgba(11, 71, 52, .28)" : "0 5px 14px rgba(11, 71, 52, .28)",
      opacity: offset === 16 ? .75 : offset === 8 ? .9 : 1,
    }}>
      <span aria-hidden="true" style={{ display: "grid", placeItems: "center", width: "100%", height: "100%", color: "rgba(255,255,255,.8)", fontSize: 22 }}>▧</span>
      {url && !failed && <img src={url} alt="" onError={() => setFailed(true)} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />}
    </span>
  );
}

function click(handler: () => void) {
  return (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    handler();
  };
}

export function StoryMapMarker({ title, imageUrl, selected, onClick }: { title: string; imageUrl?: string | null; selected: boolean; onClick: () => void }) {
  const color = selected ? "#e18212" : "#13795b";
  return (
    <button type="button" className={`live-map-marker live-map-marker--story${selected ? " is-selected" : ""}`} aria-label={`Ereignis: ${title}`} onClick={click(onClick)} style={{ ...buttonStyle, width: 82, height: 60 }}>
      <span aria-hidden="true" style={{ position: "absolute", left: 33, bottom: 1, width: 17, height: 17, boxSizing: "border-box", border: `3px solid ${selected ? color : "white"}`, borderTop: 0, borderLeft: 0, background: color, transform: "rotate(45deg)", boxShadow: "3px 3px 6px rgba(11, 71, 52, .18)" }} />
      <ImageCard url={imageUrl} selected={selected} />
    </button>
  );
}

export function ClusterMapMarker({ imageUrls, count, onClick }: { imageUrls: (string | null | undefined)[]; count: string; onClick: () => void }) {
  const cards = [...imageUrls.slice(0, 3), ...Array(Math.max(0, 3 - imageUrls.length))];
  return (
    <button type="button" className="live-map-marker live-map-marker--cluster" aria-label={`Cluster mit ${count} Ereignissen`} onClick={click(onClick)} style={{ ...buttonStyle, width: 100, height: 70 }}>
      {cards.map((url, index) => <ImageCard key={index} url={url} offset={16 - index * 8} />)}
      <span className="live-map-marker__count" style={{ position: "absolute", zIndex: 2, right: -7, bottom: -8, display: "grid", placeItems: "center", minWidth: 30, height: 30, padding: "0 7px", boxSizing: "border-box", color: "white", background: "#0c5e46", border: "3px solid white", borderRadius: 999, boxShadow: "0 3px 9px rgba(11, 71, 52, .25)", fontSize: 12, fontWeight: 850 }}>{count}</span>
    </button>
  );
}
