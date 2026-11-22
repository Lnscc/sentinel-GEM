import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import StoryDetails from "./StoryDetails";
import type { StoryDetail } from "./storyTypes";

const { useStoryDetailsMock } = vi.hoisted(() => ({ useStoryDetailsMock: vi.fn() }));

vi.mock("./useStoryDetails", () => ({ default: useStoryDetailsMock }));

const longTitle = "Ukraine-Lage: " + "SehrLangeUeberschriftOhneTrennzeichen".repeat(8);
const longDomain = "sehr-lange-nachrichten-domain-ohne-umbruchmoeglichkeit".repeat(6) + ".example";

const story: StoryDetail = {
  id: "ukraine-story",
  state: "ACTIVE",
  effectiveFrom: "2026-11-15T08:00:00Z",
  effectiveTo: "2026-11-15T10:00:00Z",
  location: null,
  locations: [],
  representativeArticle: {
    id: 1,
    canonicalUrl: "https://example.com/ukraine",
    domain: longDomain,
    firstSeenAt: "2026-11-15T08:00:00Z",
    title: longTitle,
    publishedAt: "2026-11-15T09:00:00Z",
    mainImageUrl: null,
  },
  members: [
    {
      assignmentReason: "similarity",
      article: {
        id: 2,
        canonicalUrl: "https://example.org/source",
        domain: longDomain,
        firstSeenAt: "2026-11-15T08:30:00Z",
        title: longTitle,
        publishedAt: null,
        mainImageUrl: null,
      },
    },
  ],
};

describe("StoryDetails", () => {
  afterEach(cleanup);

  it("hält den Ukraine-Fall mit langen Inhalten innerhalb der Detailspalte", () => {
    useStoryDetailsMock.mockReturnValue({ story, error: undefined });

    const { container, getAllByText, getByRole } = render(<StoryDetails id={story.id} onBack={() => undefined} />);
    const detail = container.querySelector("article");

    expect(detail).toHaveStyle({ minWidth: 0, overflowWrap: "anywhere" });
    expect(getByRole("heading", { name: longTitle, level: 2 })).toBeInTheDocument();
    expect(getAllByText(longTitle)).toHaveLength(2);
    expect(getAllByText(longDomain, { exact: false })).toHaveLength(2);
    expect(getByRole("link", { name: `${longTitle}: externe Quelle öffnen (neuer Tab)` })).toHaveAttribute(
      "href",
      "https://example.org/source",
    );
  });

  it("kennzeichnet fehlende Titel und Quellen und erhält den Link zum Original", () => {
    useStoryDetailsMock.mockReturnValue({
      story: {
        ...story,
        representativeArticle: { ...story.representativeArticle, title: null, domain: null },
        members: story.members.map((member) => ({ ...member, article: { ...member.article, title: null, domain: null } })),
      },
    });

    const { getByRole, getByText } = render(<StoryDetails id={story.id} onBack={() => undefined} />);
    expect(getByRole("heading", { name: "Story ohne Titel" })).toBeVisible();
    expect(getByText("Quelle unbekannt", { exact: true })).toBeVisible();
    expect(getByRole("link", { name: /https:\/\/example\.org\/source: externe Quelle öffnen/ }))
      .toHaveAttribute("href", "https://example.org/source");
  });
});
