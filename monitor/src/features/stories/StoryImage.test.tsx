import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import StoryImage from "./StoryImage";

describe("StoryImage", () => {
  afterEach(cleanup);

  it.each([null, "", "   ", "not-a-url", "file:///story.jpg"])("zeigt für %p eine neutrale Ersatzdarstellung", (src) => {
    const { container } = render(<StoryImage src={src} variant="card" />);

    expect(container.querySelector(".story-image__placeholder")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it.each(["card", "detail"] as const)("behält die Ersatzdarstellung in der Variante %s bei einem Ladefehler", (variant) => {
    const { container } = render(<StoryImage src="https://images.example.com/story.jpg" variant={variant} />);
    const image = container.querySelector("img")!;

    fireEvent.error(image);

    expect(image).not.toBeVisible();
    expect(getComputedStyle(image).display).toBe("none");
    expect(container.querySelector(".story-image__placeholder")).toBeInTheDocument();
  });

  it("zeigt ein erfolgreich ladbares Bild unverändert an", () => {
    const { container } = render(<StoryImage src="https://images.example.com/story.jpg" variant="card" />);

    expect(container.querySelector("img")).toHaveAttribute("src", "https://images.example.com/story.jpg");
    expect(container.querySelector("img")).toBeVisible();
  });
});
