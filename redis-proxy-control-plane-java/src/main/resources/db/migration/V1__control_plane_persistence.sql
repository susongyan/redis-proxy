CREATE TABLE IF NOT EXISTS config_versions (
    version_id BIGINT PRIMARY KEY,
    published_at VARCHAR(64) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    action VARCHAR(64) NOT NULL,
    approval_status VARCHAR(64) NOT NULL,
    rollback_from_version_id BIGINT,
    route_epoch BIGINT NOT NULL,
    config_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_config_versions_route_epoch ON config_versions(route_epoch);

CREATE TABLE IF NOT EXISTS current_config (
    id INTEGER PRIMARY KEY,
    version_id BIGINT NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    CONSTRAINT fk_current_config_version
        FOREIGN KEY (version_id) REFERENCES config_versions(version_id)
);

CREATE TABLE IF NOT EXISTS observability_targets (
    proxy_id VARCHAR(128) PRIMARY KEY,
    admin_url VARCHAR(1024) NOT NULL,
    dataplane VARCHAR(64) NOT NULL,
    cluster_name VARCHAR(128) NOT NULL,
    poll_interval_seconds INTEGER NOT NULL,
    service_namespace VARCHAR(128) NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    service_instance_id VARCHAR(128) NOT NULL,
    deployment_environment_name VARCHAR(128) NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL
);
