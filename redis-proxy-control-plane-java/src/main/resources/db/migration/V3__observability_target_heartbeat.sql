ALTER TABLE observability_targets
    ADD COLUMN IF NOT EXISTS registration_source VARCHAR(64) NOT NULL DEFAULT 'manual';

ALTER TABLE observability_targets
    ADD COLUMN IF NOT EXISTS last_heartbeat_at VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE observability_targets
    ADD COLUMN IF NOT EXISTS heartbeat_ttl_seconds INTEGER NOT NULL DEFAULT 45;
