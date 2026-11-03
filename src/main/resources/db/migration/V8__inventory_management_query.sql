SET search_path TO app, extensions, public;

-- Keyset pagination for the branch-scoped Admin inventory management query.
-- The existing timeline index remains authoritative for availability overlap.
CREATE INDEX ix_inventory_management_page
    ON inventory_blocks(property_id, updated_at DESC, id DESC);

-- Range filtering is independent from active-overlap enforcement: this covers
-- both current and retained historical rows in the management projection.
CREATE INDEX ix_inventory_management_range
    ON inventory_blocks USING gist (
        property_id,
        daterange(start_date, end_date_exclusive, '[)')
    );
