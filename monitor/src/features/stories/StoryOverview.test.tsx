import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import StoryOverview from "./StoryOverview";

vi.mock("../live-map/LiveMap", () => ({ default: () => <div>Weltkarte</div> }));
vi.mock("./StoryDetails", () => ({ default: ({ id }: { id: string }) => <article>{id}</article> }));
vi.mock("./useLagebildUrlSync", () => ({ default: () => undefined }));
vi.mock("./useStories", () => ({
  default: () => ({
    page: { stories: [], offset: 0, limit: 100, total: 0 },
    regions: [],
    error: undefined,
    isLoading: false,
    lastLoadedAt: new Date("2026-11-15T10:00:00Z"),
    refresh: () => undefined,
  }),
}));

describe("StoryOverview", () => {
  beforeEach(() => window.history.replaceState(null, "", "/?story=ukraine-story"));
  afterEach(() => {
    cleanup();
    window.history.replaceState(null, "", "/");
  });

  it("hält die Ukraine-Detailansicht innerhalb der stabilen Sidebar", () => {
    const { getByRole } = render(<StoryOverview />);
    const sidebar = getByRole("complementary", { name: "Ereignisse und Details" });
    const detailScroller = sidebar.firstElementChild;

    expect(sidebar).toHaveStyle({ minWidth: 0, overflow: "hidden" });
    expect(detailScroller).toHaveStyle({ minWidth: 0, overflow: "auto" });
    expect(detailScroller).toHaveTextContent("ukraine-story");
  });
});
