INSERT INTO story_clustering_versions (
    version_key,
    title_normalization_version,
    generic_title_rule_version,
    embedding_model_id,
    embedding_model_version,
    embedding_dimension,
    candidate_time_rule_version,
    candidate_window_hours,
    candidate_similarity_threshold,
    candidate_search_mode,
    pair_decision_rule_version,
    component_rule_version,
    feature_normalization_versions,
    status,
    created_at,
    updated_at,
    input_eligibility_rule_version
)
SELECT
    'story-mvp-title-embedding-24h-v1.3.0',
    title_normalization_version,
    generic_title_rule_version,
    embedding_model_id,
    embedding_model_version,
    embedding_dimension,
    candidate_time_rule_version,
    candidate_window_hours,
    candidate_similarity_threshold,
    candidate_search_mode,
    pair_decision_rule_version,
    'complete-linkage-template-coherent-v2',
    feature_normalization_versions,
    'SHADOW',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    input_eligibility_rule_version
FROM story_clustering_versions
WHERE version_key = 'story-mvp-title-embedding-24h-v1.2.0';

INSERT INTO story_clustering_version_status_history (
    clustering_version_id,
    previous_status,
    new_status,
    reason,
    changed_at
)
SELECT id, NULL, 'SHADOW', 'GEM-87 coherent story clustering', created_at
FROM story_clustering_versions
WHERE version_key = 'story-mvp-title-embedding-24h-v1.3.0';
