-- Synthetic, isolated snapshot-count fixture. Never apply to an application database.
CREATE TABLE story_article_inputs (id BIGINT PRIMARY KEY, article_id BIGINT NOT NULL);
CREATE TABLE story_snapshots (id BIGINT PRIMARY KEY, clustering_version_id BIGINT NOT NULL);
CREATE TABLE story_snapshot_members (snapshot_id BIGINT, clustering_version_id BIGINT, article_input_id BIGINT);
CREATE INDEX ON story_snapshot_members(snapshot_id);
CREATE TABLE story_snapshot_retention (snapshot_id BIGINT PRIMARY KEY, article_id_deltas BIGINT[] NOT NULL, completed_at TIMESTAMPTZ);
CREATE TABLE story_processing_runs (id BIGINT PRIMARY KEY, snapshot_id BIGINT, clustering_version_id BIGINT, status TEXT, completed_at TIMESTAMPTZ);
CREATE INDEX ON story_processing_runs(clustering_version_id, completed_at);

INSERT INTO story_article_inputs SELECT id, id FROM generate_series(1, 194002) id;
INSERT INTO story_snapshots SELECT id, 1 FROM generate_series(1,267) id;
INSERT INTO story_snapshots VALUES (268, 2);
INSERT INTO story_snapshot_retention
SELECT id, array_fill(1::BIGINT, ARRAY[194000]), '2026-11-28T10:00:00Z'::TIMESTAMPTZ
FROM generate_series(1,264) id;
-- Partial compaction: archive is authoritative even while live members remain.
INSERT INTO story_snapshot_retention VALUES (265, ARRAY[194001]::BIGINT[], NULL);
INSERT INTO story_snapshot_members VALUES (265,1,1), (266,1,194000), (266,1,194002);
INSERT INTO story_snapshot_retention VALUES
  (267, ARRAY[900000]::BIGINT[], '2026-11-28T10:00:00Z'),
  (268, ARRAY[800000]::BIGINT[], '2026-11-28T10:00:00Z');
INSERT INTO story_processing_runs
SELECT id, id, 1, 'SUCCEEDED', CASE WHEN id = 1 THEN '2026-11-28T09:00:00Z'::TIMESTAMPTZ
    ELSE '2026-11-28T10:00:00Z'::TIMESTAMPTZ END FROM generate_series(1,264) id;
INSERT INTO story_processing_runs VALUES
  (265,265,1,'SUCCEEDED','2026-11-28T11:00:00Z'),
  (266,266,1,'SUCCEEDED','2026-11-28T11:00:00Z'),
  (267,267,1,'SUCCEEDED','2026-11-28T12:00:00Z'),
  (268,268,2,'SUCCEEDED','2026-11-28T10:00:00Z'),
  (269,264,1,'SUCCEEDED','2026-11-28T10:00:00Z');

CREATE VIEW story_snapshot_statistics_members AS
SELECT member.snapshot_id, member.clustering_version_id, input.article_id
FROM story_snapshot_members member
JOIN story_article_inputs input ON input.id = member.article_input_id
WHERE NOT EXISTS (SELECT 1 FROM story_snapshot_retention archive WHERE archive.snapshot_id = member.snapshot_id)
UNION ALL
SELECT archive.snapshot_id, snapshot.clustering_version_id, member.article_id
FROM story_snapshot_retention archive
JOIN story_snapshots snapshot ON snapshot.id = archive.snapshot_id
CROSS JOIN LATERAL (
    SELECT SUM(delta) OVER (ORDER BY ordinal)::BIGINT AS article_id
    FROM unnest(archive.article_id_deltas) WITH ORDINALITY ids(delta, ordinal)
) member;
ANALYZE;

-- Remaining report tables use synthetic values; this measures the full report
-- over a production-size article/input/history inventory, not imported GDELT payloads.
ALTER TABLE story_article_inputs ADD COLUMN clustering_version_id BIGINT DEFAULT 1;
ALTER TABLE story_article_inputs ADD COLUMN current_marker SMALLINT DEFAULT 1;
ALTER TABLE story_article_inputs ADD COLUMN embedding_status TEXT DEFAULT 'NOT_REQUIRED';
ALTER TABLE story_article_inputs ADD COLUMN attempt_count INTEGER DEFAULT 0;
ALTER TABLE story_processing_runs ADD COLUMN started_at TIMESTAMPTZ;
UPDATE story_processing_runs SET started_at = completed_at - INTERVAL '5 seconds';
CREATE TABLE articles (id BIGINT PRIMARY KEY, domain TEXT, created_at TIMESTAMPTZ);
INSERT INTO articles SELECT id, 'source-' || (id % 1000) || '.test', '2026-11-28T09:00:00Z' FROM story_article_inputs;
CREATE TABLE gdelt_pipeline_health_view (domain_rows BIGINT, pending_payload_rows BIGINT, open_processing_errors BIGINT);
INSERT INTO gdelt_pipeline_health_view VALUES (194002, 0, 0);
CREATE TABLE story_clustering_versions (id BIGINT PRIMARY KEY, version_key TEXT, status TEXT);
INSERT INTO story_clustering_versions VALUES (1, 'visible', 'ACTIVE'), (2, 'other', 'SHADOW');
CREATE TABLE stories (id BIGINT PRIMARY KEY, clustering_version_id BIGINT, state TEXT, member_count INTEGER);
INSERT INTO stories SELECT id, 1, 'ACTIVE', 1 FROM generate_series(1,1000) id;
CREATE TABLE story_memberships (story_id BIGINT, clustering_version_id BIGINT, article_id BIGINT, current_marker SMALLINT);
INSERT INTO story_memberships SELECT id, 1, id, 1 FROM generate_series(1,1000) id;
CREATE TABLE story_locations (story_id BIGINT, rank INTEGER, country_code TEXT);
INSERT INTO story_locations SELECT id, 1, 'DE' FROM generate_series(1,1000) id;
CREATE TABLE gdelt_import_files (id BIGINT, status TEXT, row_count BIGINT, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ);
INSERT INTO gdelt_import_files VALUES (1,'COMPLETED',194002,'2026-11-28T09:00:00Z','2026-11-28T09:00:05Z');
ALTER TABLE articles ADD COLUMN canonical_url TEXT DEFAULT 'https://example.test/article';
ALTER TABLE articles ADD COLUMN first_seen_at TIMESTAMPTZ DEFAULT '2026-11-28T09:00:00Z';
CREATE INDEX ON articles(first_seen_at, id);
CREATE TABLE gdelt_gkg (id BIGINT, article_id BIGINT, page_title TEXT, source_timestamp TIMESTAMPTZ,
    page_precise_pub_timestamp TIMESTAMPTZ, main_image_url TEXT);
ALTER TABLE stories ADD COLUMN public_id UUID;
UPDATE stories SET public_id = md5(id::TEXT)::UUID;
ALTER TABLE stories ADD COLUMN effective_from TIMESTAMPTZ DEFAULT '2026-11-28T09:00:00Z';
ALTER TABLE stories ADD COLUMN effective_to TIMESTAMPTZ DEFAULT '2026-11-28T10:00:00Z';
ALTER TABLE stories ADD COLUMN representative_article_id BIGINT DEFAULT 1;
ALTER TABLE stories ADD COLUMN representative_canonical_url TEXT DEFAULT 'https://example.test/article';
ALTER TABLE stories ADD COLUMN representative_domain TEXT DEFAULT 'example.test';
ALTER TABLE stories ADD COLUMN representative_first_seen_at TIMESTAMPTZ DEFAULT '2026-11-28T09:00:00Z';
ALTER TABLE stories ADD COLUMN representative_title TEXT DEFAULT 'Synthetic test story';
ALTER TABLE stories ADD COLUMN representative_published_at TIMESTAMPTZ;
ALTER TABLE stories ADD COLUMN representative_main_image_url TEXT;
ALTER TABLE stories ADD COLUMN source_count INTEGER DEFAULT 1;
ALTER TABLE stories ADD COLUMN independent_report_count INTEGER DEFAULT 1;
ALTER TABLE story_locations ADD COLUMN name TEXT DEFAULT 'Berlin';
ALTER TABLE story_locations ADD COLUMN region_code TEXT DEFAULT 'DE';
ALTER TABLE story_locations ADD COLUMN latitude DOUBLE PRECISION DEFAULT 52.52;
ALTER TABLE story_locations ADD COLUMN longitude DOUBLE PRECISION DEFAULT 13.405;
ALTER TABLE story_locations ADD COLUMN source TEXT DEFAULT 'GKG';
ALTER TABLE story_locations ADD COLUMN confidence INTEGER DEFAULT 1;
ALTER TABLE story_locations ADD COLUMN supporting_articles INTEGER DEFAULT 1;
ALTER TABLE story_locations ADD COLUMN supporting_domains INTEGER DEFAULT 1;
ANALYZE;
