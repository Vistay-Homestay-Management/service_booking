SET search_path TO app, extensions, public;

-- Tighten legacy invariants without rewriting retained inventory evidence. The
-- NOT VALID / VALIDATE sequence makes an inconsistent installation fail closed
-- before the weaker constraint or cascading foreign key is removed.
ALTER TABLE inventory_blocks
    ADD CONSTRAINT ck_inventory_booking_exact_v7 CHECK (
        (block_type = 'BOOKING' AND booking_id IS NOT NULL)
        OR (block_type <> 'BOOKING' AND booking_id IS NULL)
    ) NOT VALID;
ALTER TABLE inventory_blocks VALIDATE CONSTRAINT ck_inventory_booking_exact_v7;
ALTER TABLE inventory_blocks DROP CONSTRAINT ck_inventory_booking;
ALTER TABLE inventory_blocks RENAME CONSTRAINT ck_inventory_booking_exact_v7
    TO ck_inventory_booking;

ALTER TABLE inventory_blocks
    ADD CONSTRAINT ck_inventory_release_exact_v7 CHECK (
        (status IN ('RELEASED', 'CANCELLED') AND released_at IS NOT NULL)
        OR (status IN ('TENTATIVE', 'ACTIVE') AND released_at IS NULL)
    ) NOT VALID;
ALTER TABLE inventory_blocks VALIDATE CONSTRAINT ck_inventory_release_exact_v7;
ALTER TABLE inventory_blocks DROP CONSTRAINT ck_inventory_release;
ALTER TABLE inventory_blocks RENAME CONSTRAINT ck_inventory_release_exact_v7
    TO ck_inventory_release;

ALTER TABLE inventory_blocks
    ADD CONSTRAINT ck_inventory_version_v7 CHECK (version >= 0) NOT VALID;
ALTER TABLE inventory_blocks VALIDATE CONSTRAINT ck_inventory_version_v7;

ALTER TABLE inventory_blocks
    ADD CONSTRAINT fk_inventory_booking_restrict_v7
    FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE inventory_blocks VALIDATE CONSTRAINT fk_inventory_booking_restrict_v7;
ALTER TABLE inventory_blocks DROP CONSTRAINT inventory_blocks_booking_id_fkey;
ALTER TABLE inventory_blocks RENAME CONSTRAINT fk_inventory_booking_restrict_v7
    TO inventory_blocks_booking_id_fkey;

-- One row is the immutable durable outcome of one successful operator command.
-- command_idempotency reserves concurrent keys; this table preserves the exact
-- response and authorization/property evidence even if generic command records
-- are eventually expired.
CREATE TABLE inventory_block_commands (
    id uuid PRIMARY KEY,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    request_hash char(64) NOT NULL,
    command_type varchar(32) NOT NULL,
    inventory_block_id uuid NOT NULL REFERENCES inventory_blocks(id) ON DELETE RESTRICT,
    property_id uuid NOT NULL,
    block_type varchar(24) NOT NULL,
    resulting_status varchar(24) NOT NULL,
    start_date date NOT NULL,
    end_date_exclusive date NOT NULL,
    expected_inventory_revision bigint NOT NULL,
    resulting_inventory_revision bigint NOT NULL,
    resulting_block_version bigint NOT NULL,
    actor_admin_account_id uuid NOT NULL,
    actor_admin_profile_id uuid NOT NULL,
    authorization_version bigint NOT NULL,
    permission_code varchar(100) NOT NULL,
    scope_type varchar(16) NOT NULL,
    partition_id uuid,
    property_branch_id uuid NOT NULL,
    property_membership_version bigint NOT NULL,
    reason varchar(1000) NOT NULL,
    correlation_id varchar(100) NOT NULL,
    occurred_at timestamptz NOT NULL,
    released_at timestamptz,
    CONSTRAINT ux_inventory_command_property_revision
        UNIQUE (property_id, resulting_inventory_revision),
    CONSTRAINT ck_inventory_command_identity CHECK (
        idempotency_key ~ '^[A-Za-z0-9._:-]{8,160}$'
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND command_type IN ('CREATE_INVENTORY_BLOCK', 'RELEASE_INVENTORY_BLOCK')
    ),
    CONSTRAINT ck_inventory_command_operator_type CHECK (
        block_type IN ('MAINTENANCE', 'CLEANING', 'OWNER_USE', 'MANUAL')
    ),
    CONSTRAINT ck_inventory_command_outcome CHECK (
        (command_type = 'CREATE_INVENTORY_BLOCK'
            AND resulting_status = 'ACTIVE' AND released_at IS NULL)
        OR (command_type = 'RELEASE_INVENTORY_BLOCK'
            AND resulting_status = 'RELEASED' AND released_at IS NOT NULL)
    ),
    CONSTRAINT ck_inventory_command_range CHECK (end_date_exclusive > start_date),
    CONSTRAINT ck_inventory_command_versions CHECK (
        expected_inventory_revision >= 0
        AND resulting_inventory_revision = expected_inventory_revision + 1
        AND resulting_block_version >= 0
        AND authorization_version >= 1
        AND property_membership_version >= 0
    ),
    CONSTRAINT ck_inventory_command_authorization CHECK (
        permission_code = 'inventory:block'
        AND (
            (scope_type = 'ALL' AND partition_id IS NULL)
            OR (scope_type = 'BRANCH' AND partition_id = property_branch_id)
        )
    ),
    CONSTRAINT ck_inventory_command_reason CHECK (
        reason = btrim(reason) AND length(reason) BETWEEN 10 AND 1000
    ),
    CONSTRAINT ck_inventory_command_correlation CHECK (
        correlation_id = btrim(correlation_id)
        AND correlation_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    )
);

CREATE INDEX ix_inventory_command_block
    ON inventory_block_commands(inventory_block_id, occurred_at DESC);
CREATE INDEX ix_inventory_command_property
    ON inventory_block_commands(property_id, resulting_inventory_revision DESC);

CREATE OR REPLACE FUNCTION reject_inventory_block_command_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'inventory block command outcomes are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_inventory_block_command_immutable
BEFORE UPDATE OR DELETE ON inventory_block_commands
FOR EACH ROW EXECUTE FUNCTION reject_inventory_block_command_mutation();
