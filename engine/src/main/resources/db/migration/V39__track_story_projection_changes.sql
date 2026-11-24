CREATE TABLE story_projection_dirty_sources (
    source_key TEXT PRIMARY KEY,
    article_id BIGINT,
    global_event_id BIGINT,
    mention_identifier TEXT
);

CREATE INDEX idx_story_memberships_current_article
    ON story_memberships (article_id, story_id) WHERE current_marker = 1;

INSERT INTO story_projection_dirty_sources (source_key, article_id)
SELECT DISTINCT 'a:' || member.article_id, member.article_id
FROM story_memberships member
JOIN stories story ON story.id = member.story_id
JOIN story_clustering_versions version ON version.id = member.clustering_version_id
WHERE member.current_marker = 1 AND story.state <> 'SUPERSEDED'
  AND version.status = 'ACTIVE';

-- Row updates synchronize source transactions with the publisher's queue deletion.
-- A source transaction finishing after a publish therefore leaves work for the next run.
CREATE FUNCTION track_story_projection_article_change() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    previous_article BIGINT;
    next_article BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF TG_TABLE_NAME = 'articles' THEN
            previous_article := OLD.id;
        ELSE
            previous_article := OLD.article_id;
        END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        IF TG_TABLE_NAME = 'articles' THEN
            next_article := NEW.id;
        ELSE
            next_article := NEW.article_id;
        END IF;
    END IF;
    INSERT INTO story_projection_dirty_sources (source_key, article_id)
    SELECT 'a:' || source.article_id, source.article_id
    FROM (SELECT DISTINCT article_id
          FROM (VALUES (previous_article), (next_article)) changed(article_id)
          WHERE article_id IS NOT NULL) source
    ORDER BY source.article_id
    ON CONFLICT (source_key) DO UPDATE SET source_key = EXCLUDED.source_key;
    RETURN NULL;
END;
$$;

CREATE FUNCTION track_story_projection_mention_change() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    previous_event BIGINT;
    next_event BIGINT;
    previous_identifier TEXT;
    next_identifier TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        previous_event := OLD.global_event_id;
        previous_identifier := OLD.mention_identifier;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        next_event := NEW.global_event_id;
        next_identifier := NEW.mention_identifier;
    END IF;
    INSERT INTO story_projection_dirty_sources
        (source_key, global_event_id, mention_identifier)
    SELECT 'm:' || source.event_id || ':'
           || encode(sha256(convert_to(source.identifier, 'UTF8')), 'hex'),
           source.event_id, source.identifier
    FROM (SELECT DISTINCT event_id, identifier
          FROM (VALUES (previous_event, previous_identifier),
                       (next_event, next_identifier)) changed(event_id, identifier)
          WHERE event_id IS NOT NULL AND identifier IS NOT NULL) source
    ORDER BY source.event_id, source.identifier
    ON CONFLICT (source_key) DO UPDATE SET source_key = EXCLUDED.source_key;
    RETURN NULL;
END;
$$;

CREATE TRIGGER track_story_projection_changes
AFTER INSERT OR UPDATE OR DELETE ON articles
FOR EACH ROW EXECUTE FUNCTION track_story_projection_article_change();

CREATE TRIGGER track_story_projection_changes
AFTER INSERT OR UPDATE OR DELETE ON gdelt_events
FOR EACH ROW EXECUTE FUNCTION track_story_projection_article_change();

CREATE TRIGGER track_story_projection_changes
AFTER INSERT OR UPDATE OR DELETE ON gdelt_gkg
FOR EACH ROW EXECUTE FUNCTION track_story_projection_article_change();

CREATE TRIGGER track_story_projection_changes
AFTER INSERT OR UPDATE OR DELETE ON gdelt_mentions
FOR EACH ROW EXECUTE FUNCTION track_story_projection_mention_change();

-- Shadow publications do not consume the active projection queue. Promotion schedules
-- a refresh even when the formerly active version already consumed earlier source changes.
CREATE FUNCTION track_story_projection_activation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO story_projection_dirty_sources (source_key, article_id)
    SELECT DISTINCT 'a:' || member.article_id, member.article_id
    FROM story_memberships member
    WHERE member.clustering_version_id = NEW.id AND member.current_marker = 1
    ORDER BY member.article_id
    ON CONFLICT (source_key) DO UPDATE SET source_key = EXCLUDED.source_key;
    RETURN NULL;
END;
$$;

CREATE TRIGGER track_story_projection_activation
AFTER UPDATE OF status ON story_clustering_versions
FOR EACH ROW WHEN (NEW.status = 'ACTIVE' AND OLD.status <> NEW.status)
EXECUTE FUNCTION track_story_projection_activation();
