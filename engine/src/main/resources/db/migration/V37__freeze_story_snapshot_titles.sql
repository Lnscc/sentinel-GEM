ALTER TABLE story_snapshot_members
    ADD COLUMN normalized_title TEXT;

ALTER TABLE story_snapshot_members
    DISABLE TRIGGER trg_story_snapshot_members_frozen;

UPDATE story_snapshot_members member
SET normalized_title = input.normalized_title
FROM story_article_inputs input
WHERE input.id = member.article_input_id;

ALTER TABLE story_snapshot_members
    ENABLE TRIGGER trg_story_snapshot_members_frozen;
