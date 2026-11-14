ALTER TABLE stories ADD COLUMN source_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stories ADD COLUMN independent_report_count INTEGER NOT NULL DEFAULT 0;

UPDATE stories story
SET source_count = counts.source_count,
    independent_report_count = counts.independent_report_count
FROM (
    SELECT member.story_id,
           COUNT(DISTINCT article.domain)::INTEGER AS source_count,
           COUNT(DISTINCT input.title_input_hash)::INTEGER AS independent_report_count
    FROM story_memberships member
    JOIN articles article ON article.id = member.article_id
    JOIN story_article_inputs input
      ON input.clustering_version_id = member.clustering_version_id
     AND input.article_ref = member.article_ref
     AND input.article_input_fingerprint = member.article_input_fingerprint
    WHERE member.current_marker = 1
    GROUP BY member.story_id
) counts
WHERE story.id = counts.story_id;

ALTER TABLE stories
    ADD CONSTRAINT ck_stories_source_count CHECK (source_count >= 0),
    ADD CONSTRAINT ck_stories_independent_report_count CHECK (independent_report_count >= 0);

CREATE INDEX idx_stories_list_priority
    ON stories (clustering_version_id, independent_report_count DESC, effective_to DESC, public_id);
