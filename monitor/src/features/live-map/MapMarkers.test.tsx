import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ClusterMapMarker, StoryMapMarker } from "./MapMarkers";

describe("MapMarkers", () => {
  afterEach(cleanup);

  it.each([null, "", "   ", "not-a-url", "file:///story.jpg"])("zeigt für %p einen bedienbaren Story-Fallback", (imageUrl) => {
    const onClick = vi.fn();
    const { container } = render(<StoryMapMarker title="Ereignis" imageUrl={imageUrl} selected={false} onClick={onClick} />);

    expect(container.querySelector(".live-map-marker__placeholder")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Ereignis: Ereignis" }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("blendet ein fehlerhaftes Bild aus, ohne die Story-Auswahl zu beeinträchtigen", () => {
    const onClick = vi.fn();
    const { container } = render(<StoryMapMarker title="Ereignis" imageUrl="https://images.example.com/broken.jpg" selected={false} onClick={onClick} />);
    const image = container.querySelector("img")!;

    fireEvent.error(image);
    fireEvent.click(screen.getByRole("button", { name: "Ereignis: Ereignis" }));

    expect(image).not.toBeVisible();
    expect(container.querySelector(".live-map-marker__placeholder")).toBeInTheDocument();
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("zeigt Bilder und drei bedienbare Cluster-Karten mit Fallbacks", () => {
    const onClick = vi.fn();
    const { container } = render(<ClusterMapMarker imageUrls={["https://images.example.com/story.jpg", "", null]} count="3" onClick={onClick} />);

    expect(container.querySelectorAll(".live-map-marker__image")).toHaveLength(3);
    expect(container.querySelectorAll(".live-map-marker__placeholder")).toHaveLength(3);
    expect(container.querySelector("img")).toHaveAttribute("src", "https://images.example.com/story.jpg");
    fireEvent.click(screen.getByRole("button", { name: "Cluster mit 3 Ereignissen" }));
    expect(onClick).toHaveBeenCalledOnce();
  });
});
