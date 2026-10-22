export type Story = {
  id: string;
  effectiveFrom: string;
  effectiveTo: string;
  location: StoryLocation | null;
  representativeArticle: { domain: string; title: string };
  memberCount: number;
};

export type StoryLocation = {
  name: string;
  countryCode: string | null;
  regionCode: string | null;
  latitude: number;
  longitude: number;
  source: string;
};

export type Article = {
  id: number;
  canonicalUrl: string;
  domain: string;
  firstSeenAt: string;
  title: string;
  publishedAt: string | null;
  mainImageUrl: string | null;
};

export type StoryDetail = {
  id: string;
  state: string;
  effectiveFrom: string;
  effectiveTo: string;
  location: StoryLocation | null;
  representativeArticle: Article;
  members: { article: Article; assignmentReason: string }[];
};

export type StoryPage = { stories: Story[]; total: number };
