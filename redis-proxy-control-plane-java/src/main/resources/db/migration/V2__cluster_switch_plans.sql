CREATE TABLE IF NOT EXISTS cluster_switch_plans (
    plan_id BIGINT PRIMARY KEY,
    source_cluster VARCHAR(128) NOT NULL,
    target_cluster VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    plan_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cluster_switch_plans_source_status
    ON cluster_switch_plans(source_cluster, status);
