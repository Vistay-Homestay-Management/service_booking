SET search_path TO app, extensions, public;

CREATE UNIQUE INDEX ux_property_rate_profile_identity
    ON property_rate_profile_versions(id, property_id);

-- A manual override is authorization evidence bound to one deterministic base
-- quote. It is not a published quote or booking snapshot; those are created by
-- the later pricing lifecycle and must reference this artifact explicitly.
CREATE TABLE pricing_manual_overrides (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key varchar(160) NOT NULL UNIQUE,
    request_hash char(64) NOT NULL,
    property_id uuid NOT NULL,
    rate_profile_version_id uuid NOT NULL,
    base_quote_fingerprint char(64) NOT NULL,
    calculation_version varchar(32) NOT NULL,
    quote_input_snapshot jsonb NOT NULL,
    base_quote_snapshot jsonb NOT NULL,
    applied_override_snapshot jsonb NOT NULL,
    before_lodging_amount bigint NOT NULL,
    target_lodging_amount bigint NOT NULL,
    adjustment_amount bigint NOT NULL,
    reduction_bps integer NOT NULL,
    commission_bps integer NOT NULL,
    before_commission_amount bigint NOT NULL,
    after_commission_amount bigint NOT NULL,
    before_total_amount bigint NOT NULL,
    after_total_amount bigint NOT NULL,
    currency char(3) NOT NULL,
    large_approval_required boolean NOT NULL,
    actor_admin_account_id uuid NOT NULL,
    actor_admin_profile_id uuid NOT NULL,
    authorization_version bigint NOT NULL,
    override_permission_code varchar(100) NOT NULL,
    override_scope_type varchar(16) NOT NULL,
    override_partition_id uuid,
    large_approval_authorization_version bigint,
    large_approval_permission_code varchar(100),
    large_approval_scope_type varchar(16),
    large_approval_partition_id uuid,
    reason varchar(1000) NOT NULL,
    correlation_id varchar(100) NOT NULL,
    quote_ttl_minutes integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT fk_pricing_override_profile_property FOREIGN KEY (
        rate_profile_version_id, property_id
    ) REFERENCES property_rate_profile_versions(id, property_id) ON DELETE RESTRICT,
    CONSTRAINT ck_pricing_override_identity CHECK (
        idempotency_key ~ '^[A-Za-z0-9._:-]{8,160}$'
        AND calculation_version = trim(calculation_version)
        AND length(calculation_version) BETWEEN 1 AND 32
    ),
    CONSTRAINT ck_pricing_override_fingerprint CHECK (
        base_quote_fingerprint ~ '^[0-9a-f]{64}$'
        AND request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_pricing_override_snapshots CHECK (
        jsonb_typeof(quote_input_snapshot) = 'object'
        AND jsonb_typeof(base_quote_snapshot) = 'object'
        AND jsonb_typeof(applied_override_snapshot) = 'object'
    ),
    CONSTRAINT ck_pricing_override_amounts CHECK (
        before_lodging_amount > 0
        AND target_lodging_amount >= 0
        AND target_lodging_amount < before_lodging_amount
        AND adjustment_amount = target_lodging_amount - before_lodging_amount
        AND reduction_bps BETWEEN 0 AND 10000
        AND commission_bps BETWEEN 0 AND 2000
        AND before_commission_amount >= 0
        AND after_commission_amount >= 0
        AND before_total_amount = before_lodging_amount + before_commission_amount
        AND after_total_amount = target_lodging_amount + after_commission_amount
    ),
    CONSTRAINT ck_pricing_override_commission CHECK (
        before_commission_amount = floor(
            (before_lodging_amount::numeric * commission_bps + 5000) / 10000
        )::bigint
        AND after_commission_amount = floor(
            (target_lodging_amount::numeric * commission_bps + 5000) / 10000
        )::bigint
    ),
    CONSTRAINT ck_pricing_override_reduction CHECK (
        reduction_bps = floor(
            ((before_lodging_amount::numeric - target_lodging_amount) * 10000
                + before_lodging_amount::numeric / 2)
            / before_lodging_amount::numeric
        )::integer
        AND large_approval_required = (
            (before_lodging_amount::numeric - target_lodging_amount) * 10000
                > before_lodging_amount::numeric * 2000
        )
    ),
    CONSTRAINT ck_pricing_override_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_pricing_override_actor CHECK (
        authorization_version >= 1
        AND override_permission_code = 'pricing:override'
    ),
    CONSTRAINT ck_pricing_override_scope CHECK (
        (override_scope_type = 'ALL' AND override_partition_id IS NULL)
        OR (override_scope_type = 'BRANCH' AND override_partition_id IS NOT NULL)
    ),
    CONSTRAINT ck_pricing_override_large_scope CHECK (
        (NOT large_approval_required
            AND large_approval_authorization_version IS NULL
            AND large_approval_permission_code IS NULL
            AND large_approval_scope_type IS NULL
            AND large_approval_partition_id IS NULL)
        OR (large_approval_required
            AND large_approval_authorization_version IS NOT NULL
            AND large_approval_authorization_version >= 1
            AND large_approval_permission_code = 'pricing:approve-large-override'
            AND large_approval_scope_type IS NOT NULL
            AND (
                (large_approval_scope_type = 'ALL' AND large_approval_partition_id IS NULL)
                OR (large_approval_scope_type = 'BRANCH'
                    AND large_approval_partition_id IS NOT NULL)
            ))
    ),
    CONSTRAINT ck_pricing_override_reason CHECK (
        reason = trim(reason) AND length(reason) BETWEEN 10 AND 1000
    ),
    CONSTRAINT ck_pricing_override_correlation CHECK (
        correlation_id = trim(correlation_id)
        AND length(correlation_id) BETWEEN 1 AND 100
    ),
    CONSTRAINT ck_pricing_override_expiry CHECK (
        quote_ttl_minutes BETWEEN 1 AND 1440
        AND expires_at = created_at + quote_ttl_minutes * interval '1 minute'
    )
);

CREATE INDEX ix_pricing_manual_override_property
    ON pricing_manual_overrides(property_id, created_at DESC);
CREATE INDEX ix_pricing_manual_override_profile
    ON pricing_manual_overrides(rate_profile_version_id, created_at DESC);
CREATE INDEX ix_pricing_manual_override_expiry
    ON pricing_manual_overrides(expires_at);

CREATE OR REPLACE FUNCTION reject_pricing_manual_override_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'pricing manual overrides are immutable; create a new artifact'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_pricing_manual_override_immutable
BEFORE UPDATE OR DELETE ON pricing_manual_overrides
FOR EACH ROW EXECUTE FUNCTION reject_pricing_manual_override_mutation();
