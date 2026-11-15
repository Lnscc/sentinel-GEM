import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import StoryCard from "./StoryCard";
import type { Story } from "./storyTypes";

const story: Story = {
  id: "story-1",
  effectiveFrom: "2026-10-21T10:00:00Z",
  effectiveTo: "2026-10-21T11:00:00Z",
  location: null,
  locations: [],
  representativeArticle: { domain: "example.com", title: "Ein Ereignis", mainImageUrl: "https://images.example.com/story.jpg" },
  memberCount: 2,
  sourceCount: 2,
};

describe("StoryCard", () => {
  afterEach(cleanup);

  it("zeigt ein erfolgreich ladbares Story-Bild", () => {
    const { container } = render(<StoryCard story={story} />);

    expect(container.querySelector("img")).toHaveAttribute("src", "https://images.example.com/story.jpg");
  });

  it("zeigt ohne Bild-URL die neutrale Ersatzdarstellung", () => {
    const withoutImage = { ...story, representativeArticle: { ...story.representativeArticle, mainImageUrl: null } };
    const { container } = render(<StoryCard story={withoutImage} />);

    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector(".story-image__placeholder")).toBeInTheDocument();
  });

  it("behält bei einem Bildfehler Layout und Link bei", () => {
    const { container, getByRole } = render(<StoryCard story={story} />);
    const image = container.querySelector("img")!;

    fireEvent.error(image);

    expect(image).not.toBeVisible();
    expect(container.querySelector(".story-image__placeholder")).toBeInTheDocument();
    expect(getByRole("link")).toHaveAttribute("href", "#story-1");
  });
});
