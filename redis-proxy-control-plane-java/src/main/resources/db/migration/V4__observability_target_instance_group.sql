ALTER TABLE observability_targets
    ADD COLUMN IF NOT EXISTS group_name VARCHAR(128) NOT NULL DEFAULT 'default';

ALTER TABLE observability_targets
    ADD COLUMN IF NOT EXISTS advertise_ip VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE observability_targets
    ADD COLUMN IF NOT EXISTS advertise_port INTEGER NOT NULL DEFAULT 0;
