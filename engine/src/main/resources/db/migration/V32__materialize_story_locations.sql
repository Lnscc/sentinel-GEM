CREATE TABLE story_locations (
    story_id UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    rank INTEGER NOT NULL,
    name TEXT,
    country_code VARCHAR(16),
    region_code VARCHAR(32),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    source VARCHAR(64) NOT NULL,
    confidence INTEGER NOT NULL,
    supporting_articles INTEGER NOT NULL,
    supporting_domains INTEGER NOT NULL,
    PRIMARY KEY (story_id, rank)
);

CREATE INDEX idx_story_locations_primary_country_region
    ON story_locations (country_code, region_code, rank, story_id);

CREATE INDEX idx_story_locations_primary_region
    ON story_locations (region_code, rank, story_id);

