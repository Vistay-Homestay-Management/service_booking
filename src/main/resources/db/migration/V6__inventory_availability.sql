SET search_path TO app, extensions, public;

-- CHANNEL is an inventory source in its own right. Its provider identity is
-- deliberately retained in the owning Booking database, while the public
-- availability read exposes only the provider name.
ALTER TABLE inventory_blocks
    ADD COLUMN channel_provider varchar(60),
    ADD COLUMN channel_external_block_id varchar(180);

ALTER TABLE inventory_blocks DROP CONSTRAINT ck_inventory_type;
ALTER TABLE inventory_blocks ADD CONSTRAINT ck_inventory_type
    CHECK (block_type IN (
        'BOOKING', 'MAINTENANCE', 'CLEANING', 'OWNER_USE', 'MANUAL', 'CHANNEL'
    ));
ALTER TABLE inventory_blocks ADD CONSTRAINT ck_inventory_channel_identity CHECK (
    (
        block_type = 'CHANNEL'
        AND channel_provider IS NOT NULL
        AND channel_provider = btrim(channel_provider)
        AND channel_provider <> ''
        AND channel_external_block_id IS NOT NULL
        AND channel_external_block_id = btrim(channel_external_block_id)
        AND channel_external_block_id <> ''
        AND booking_id IS NULL
    )
    OR (
        block_type <> 'CHANNEL'
        AND channel_provider IS NULL
        AND channel_external_block_id IS NULL
    )
);

-- Provider names are case-insensitive at the integration boundary. Historical
-- RELEASED/CANCELLED rows keep their identity, so a replay cannot create a
-- second logical channel block under another status or property.
CREATE UNIQUE INDEX ux_inventory_channel_identity
    ON inventory_blocks (lower(channel_provider), channel_external_block_id)
    WHERE block_type = 'CHANNEL';

CREATE TABLE inventory_property_revisions (
    property_id uuid PRIMARY KEY,
    revision bigint NOT NULL CHECK (revision > 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Preserve a useful monotonic baseline for properties that already have rows.
-- Exact pre-V6 update history is unknowable, so the number of retained rows is
-- the minimum safe starting revision.
INSERT INTO inventory_property_revisions(property_id, revision, updated_at)
SELECT property_id, count(*)::bigint, now()
FROM inventory_blocks
GROUP BY property_id;

CREATE OR REPLACE FUNCTION advance_inventory_property_revision()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = app, pg_temp
AS $$
DECLARE
    old_property_id uuid;
    new_property_id uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_property_id := OLD.property_id;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_property_id := NEW.property_id;
    END IF;

    IF old_property_id IS NOT NULL THEN
        INSERT INTO inventory_property_revisions(property_id, revision, updated_at)
        VALUES (old_property_id, 1, clock_timestamp())
        ON CONFLICT (property_id) DO UPDATE
        SET revision = inventory_property_revisions.revision + 1,
            updated_at = EXCLUDED.updated_at;
    END IF;

    IF new_property_id IS NOT NULL
        AND (old_property_id IS NULL OR new_property_id <> old_property_id) THEN
        INSERT INTO inventory_property_revisions(property_id, revision, updated_at)
        VALUES (new_property_id, 1, clock_timestamp())
        ON CONFLICT (property_id) DO UPDATE
        SET revision = inventory_property_revisions.revision + 1,
            updated_at = EXCLUDED.updated_at;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_property_revision
AFTER INSERT OR UPDATE OR DELETE ON inventory_blocks
FOR EACH ROW EXECUTE FUNCTION advance_inventory_property_revision();
