ALTER TABLE stories ADD COLUMN representative_article_id BIGINT;
ALTER TABLE stories ADD COLUMN representative_canonical_url TEXT;
ALTER TABLE stories ADD COLUMN representative_domain TEXT;
ALTER TABLE stories ADD COLUMN representative_first_seen_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stories ADD COLUMN representative_title TEXT;
ALTER TABLE stories ADD COLUMN representative_published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stories ADD COLUMN representative_main_image_url TEXT;
ALTER TABLE stories ADD COLUMN member_count INTEGER NOT NULL DEFAULT 0;

UPDATE stories story
SET representative_article_id = (
        SELECT article.id FROM articles article
        WHERE article.url_hash = story.representative_article_ref),
    representative_canonical_url = (
        SELECT article.canonical_url FROM articles article
        WHERE article.url_hash = story.representative_article_ref),
    representative_domain = (
        SELECT article.domain FROM articles article
        WHERE article.url_hash = story.representative_article_ref),
    representative_first_seen_at = (
        SELECT article.first_seen_at FROM articles article
        WHERE article.url_hash = story.representative_article_ref),
    representative_title = (
        SELECT gkg.page_title
        FROM articles article
        JOIN gdelt_gkg gkg ON gkg.article_id = article.id
        WHERE article.url_hash = story.representative_article_ref
          AND gkg.page_title IS NOT NULL AND TRIM(gkg.page_title) <> ''
        ORDER BY gkg.source_timestamp, gkg.id
        LIMIT 1),
    representative_published_at = (
        SELECT gkg.page_precise_pub_timestamp
        FROM articles article
        JOIN gdelt_gkg gkg ON gkg.article_id = article.id
        WHERE article.url_hash = story.representative_article_ref
          AND gkg.page_precise_pub_timestamp IS NOT NULL
        ORDER BY gkg.source_timestamp, gkg.id
        LIMIT 1),
    representative_main_image_url = (
        SELECT gkg.main_image_url
        FROM articles article
        JOIN gdelt_gkg gkg ON gkg.article_id = article.id
        WHERE article.url_hash = story.representative_article_ref
          AND gkg.main_image_url IS NOT NULL
        ORDER BY gkg.source_timestamp, gkg.id
        LIMIT 1),
    member_count = (
        SELECT COUNT(*)
        FROM story_memberships member
        WHERE member.story_id = story.id
          AND member.clustering_version_id = story.clustering_version_id
          AND member.current_marker = 1);

ALTER TABLE stories
    ADD CONSTRAINT ck_stories_member_count CHECK (member_count >= 0);

CREATE INDEX idx_stories_list_time
    ON stories (clustering_version_id, effective_to DESC, public_id);

CREATE INDEX idx_stories_list_member_count
    ON stories (clustering_version_id, member_count DESC, public_id);
