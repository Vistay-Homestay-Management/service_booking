SET search_path TO app, extensions, public;

-- Publishing is append-only. Authored profile/rule rows remain immutable and a
-- later overlapping publication affects only newly created quote snapshots.
CREATE TABLE pricing_profile_publications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key varchar(160) NOT NULL UNIQUE,
    request_hash char(64) NOT NULL,
    property_id uuid NOT NULL,
    rate_profile_version_id uuid NOT NULL UNIQUE,
    previous_publication_id uuid REFERENCES pricing_profile_publications(id) ON DELETE RESTRICT,
    publication_revision bigint NOT NULL,
    profile_fingerprint char(64) NOT NULL,
    snapshot_schema_version varchar(32) NOT NULL,
    profile_snapshot jsonb NOT NULL,
    actor_admin_account_id uuid NOT NULL,
    actor_admin_profile_id uuid NOT NULL,
    authorization_version bigint NOT NULL,
    permission_code varchar(100) NOT NULL,
    scope_type varchar(16) NOT NULL,
    partition_id uuid,
    reason varchar(1000) NOT NULL,
    correlation_id varchar(100) NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_pricing_publication_profile_property FOREIGN KEY (
        rate_profile_version_id, property_id
    ) REFERENCES property_rate_profile_versions(id, property_id) ON DELETE RESTRICT,
    CONSTRAINT ux_pricing_publication_identity UNIQUE (
        id, rate_profile_version_id, property_id
    ),
    CONSTRAINT ux_pricing_publication_revision UNIQUE (
        property_id, publication_revision
    ),
    CONSTRAINT ck_pricing_publication_idempotency CHECK (
        idempotency_key ~ '^[A-Za-z0-9._:-]{8,160}$'
        AND request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_pricing_publication_fingerprint CHECK (
        profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND snapshot_schema_version = 'PRICING_PUBLICATION_V1'
        AND jsonb_typeof(profile_snapshot) = 'object'
    ),
    CONSTRAINT ck_pricing_publication_revision CHECK (
        publication_revision >= 1
        AND ((publication_revision = 1 AND previous_publication_id IS NULL)
             OR (publication_revision > 1 AND previous_publication_id IS NOT NULL))
    ),
    CONSTRAINT ck_pricing_publication_authorization CHECK (
        authorization_version >= 1
        AND permission_code = 'pricing:approve'
        AND (
            (scope_type = 'ALL' AND partition_id IS NULL)
            OR (scope_type = 'BRANCH' AND partition_id IS NOT NULL)
        )
    ),
    CONSTRAINT ck_pricing_publication_reason CHECK (
        reason = trim(reason) AND length(reason) BETWEEN 10 AND 1000
    ),
    CONSTRAINT ck_pricing_publication_correlation CHECK (
        correlation_id = trim(correlation_id)
        AND correlation_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    )
);

CREATE INDEX ix_pricing_publication_active_selection
    ON pricing_profile_publications(property_id, publication_revision DESC);

CREATE OR REPLACE FUNCTION reject_pricing_publication_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'pricing publications are immutable; publish a new profile version'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_pricing_publication_immutable
BEFORE UPDATE OR DELETE ON pricing_profile_publications
FOR EACH ROW EXECUTE FUNCTION reject_pricing_publication_mutation();

CREATE UNIQUE INDEX ux_pricing_override_subject_identity
    ON pricing_manual_overrides(id, rate_profile_version_id, property_id);

-- The existing booking_quotes aggregate becomes the durable pricing snapshot
-- boundary. Legacy rows may keep all new columns NULL; every pricing-v1 row must
-- supply the complete evidence bundle.
ALTER TABLE booking_quotes
    ADD COLUMN idempotency_key varchar(160),
    ADD COLUMN request_hash char(64),
    ADD COLUMN pricing_publication_id uuid,
    ADD COLUMN rate_profile_version_id uuid,
    ADD COLUMN manual_override_id uuid,
    ADD COLUMN base_quote_fingerprint char(64),
    ADD COLUMN calculation_version varchar(32),
    ADD COLUMN quote_input_snapshot jsonb,
    ADD COLUMN base_quote_snapshot jsonb,
    ADD COLUMN final_quote_snapshot jsonb,
    ADD COLUMN created_by_admin_account_id uuid,
    ADD COLUMN created_by_admin_profile_id uuid,
    ADD COLUMN authorization_version bigint,
    ADD COLUMN permission_code varchar(100),
    ADD COLUMN scope_type varchar(16),
    ADD COLUMN partition_id uuid,
    ADD COLUMN reason varchar(1000),
    ADD COLUMN correlation_id varchar(100),
    ADD CONSTRAINT fk_booking_quote_pricing_publication FOREIGN KEY (
        pricing_publication_id, rate_profile_version_id, property_id
    ) REFERENCES pricing_profile_publications(
        id, rate_profile_version_id, property_id
    ) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_booking_quote_manual_override FOREIGN KEY (
        manual_override_id, rate_profile_version_id, property_id
    ) REFERENCES pricing_manual_overrides(
        id, rate_profile_version_id, property_id
    ) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_booking_quote_pricing_bundle CHECK (
        (pricing_publication_id IS NULL
            AND idempotency_key IS NULL
            AND request_hash IS NULL
            AND rate_profile_version_id IS NULL
            AND manual_override_id IS NULL
            AND base_quote_fingerprint IS NULL
            AND calculation_version IS NULL
            AND quote_input_snapshot IS NULL
            AND base_quote_snapshot IS NULL
            AND final_quote_snapshot IS NULL
            AND created_by_admin_account_id IS NULL
            AND created_by_admin_profile_id IS NULL
            AND authorization_version IS NULL
            AND permission_code IS NULL
            AND scope_type IS NULL
            AND partition_id IS NULL
            AND reason IS NULL
            AND correlation_id IS NULL)
        OR
        (pricing_publication_id IS NOT NULL
            AND idempotency_key IS NOT NULL
            AND request_hash IS NOT NULL
            AND rate_profile_version_id IS NOT NULL
            AND base_quote_fingerprint IS NOT NULL
            AND calculation_version IS NOT NULL
            AND quote_input_snapshot IS NOT NULL
            AND base_quote_snapshot IS NOT NULL
            AND final_quote_snapshot IS NOT NULL
            AND created_by_admin_account_id IS NOT NULL
            AND created_by_admin_profile_id IS NOT NULL
            AND authorization_version IS NOT NULL
            AND permission_code IS NOT NULL
            AND scope_type IS NOT NULL
            AND reason IS NOT NULL
            AND correlation_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_booking_quote_pricing_identity CHECK (
        pricing_publication_id IS NULL OR (
            idempotency_key ~ '^[A-Za-z0-9._:-]{8,160}$'
            AND request_hash ~ '^[0-9a-f]{64}$'
            AND base_quote_fingerprint ~ '^[0-9a-f]{64}$'
            AND calculation_version = trim(calculation_version)
            AND length(calculation_version) BETWEEN 1 AND 32
        )
    ),
    ADD CONSTRAINT ck_booking_quote_pricing_snapshots CHECK (
        pricing_publication_id IS NULL OR (
            jsonb_typeof(quote_input_snapshot) = 'object'
            AND jsonb_typeof(base_quote_snapshot) = 'object'
            AND jsonb_typeof(final_quote_snapshot) = 'object'
        )
    ),
    ADD CONSTRAINT ck_booking_quote_pricing_authorization CHECK (
        pricing_publication_id IS NULL OR (
            authorization_version >= 1
            AND permission_code = 'pricing:read'
            AND (
                (scope_type = 'ALL' AND partition_id IS NULL)
                OR (scope_type = 'BRANCH' AND partition_id IS NOT NULL)
            )
        )
    ),
    ADD CONSTRAINT ck_booking_quote_pricing_reason CHECK (
        pricing_publication_id IS NULL OR (
            reason = trim(reason) AND length(reason) BETWEEN 10 AND 1000
        )
    ),
    ADD CONSTRAINT ck_booking_quote_pricing_correlation CHECK (
        pricing_publication_id IS NULL OR (
            correlation_id = trim(correlation_id)
            AND correlation_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        )
    );

CREATE UNIQUE INDEX ux_booking_quote_pricing_idempotency
    ON booking_quotes(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX ux_booking_quote_manual_override_consumption
    ON booking_quotes(manual_override_id) WHERE manual_override_id IS NOT NULL;
CREATE INDEX ix_booking_quote_pricing_profile
    ON booking_quotes(rate_profile_version_id, created_at DESC)
    WHERE rate_profile_version_id IS NOT NULL;

CREATE OR REPLACE FUNCTION protect_booking_quote_pricing_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.pricing_publication_id IS NOT NULL THEN
        RAISE EXCEPTION 'published pricing quote snapshots cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.pricing_publication_id IS NOT NULL
       AND (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
        RAISE EXCEPTION 'published pricing quote evidence is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_booking_quote_pricing_snapshot_immutable
BEFORE UPDATE OR DELETE ON booking_quotes
FOR EACH ROW EXECUTE FUNCTION protect_booking_quote_pricing_snapshot();
