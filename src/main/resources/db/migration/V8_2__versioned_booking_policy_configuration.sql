SET search_path TO app, extensions, public;

ALTER TABLE booking_policies
  ADD COLUMN policy_type varchar(40) NOT NULL DEFAULT 'RESERVATION',
  ADD COLUMN row_version bigint NOT NULL DEFAULT 1,
  ADD COLUMN retired_at timestamptz;

ALTER TABLE booking_policies ADD CONSTRAINT ck_booking_policy_type
  CHECK (policy_type IN ('RESERVATION', 'CANCELLATION', 'CHECK_IN', 'CHECK_OUT', 'OCCUPANCY', 'PAYMENT'));

CREATE TABLE booking_policy_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id uuid NOT NULL REFERENCES booking_policies(id) ON DELETE RESTRICT,
    version integer NOT NULL CHECK (version > 0),
    status varchar(20) NOT NULL DEFAULT 'DRAFT',
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    hold_minutes integer NOT NULL CHECK (hold_minutes > 0),
    minimum_notice_hours integer NOT NULL CHECK (minimum_notice_hours >= 0),
    maximum_advance_days integer NOT NULL CHECK (maximum_advance_days > 0),
    minimum_stay_nights integer NOT NULL DEFAULT 1 CHECK (minimum_stay_nights > 0),
    maximum_stay_nights integer CHECK (maximum_stay_nights IS NULL OR maximum_stay_nights >= minimum_stay_nights),
    minimum_guest_age integer NOT NULL DEFAULT 18 CHECK (minimum_guest_age BETWEEN 0 AND 120),
    security_deposit_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK (security_deposit_amount >= 0),
    currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency ~ '^[A-Z]{3}$'),
    allowed_stay_types jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(allowed_stay_types) = 'array'),
    allowed_channels jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(allowed_channels) = 'array'),
    cancellation_rules jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(cancellation_rules) = 'object'),
    guest_rules jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(guest_rules) = 'object'),
    fingerprint char(64) NOT NULL UNIQUE,
    created_by_account_id uuid NOT NULL,
    approved_by_account_id uuid,
    reason varchar(500) NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_policy_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED', 'REJECTED')),
    CONSTRAINT ck_booking_policy_effective_window CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_booking_policy_maker_checker CHECK (approved_by_account_id IS NULL OR approved_by_account_id <> created_by_account_id),
    UNIQUE (policy_id, version)
);
CREATE INDEX ix_booking_policy_version_runtime
  ON booking_policy_versions(policy_id, status, effective_from, effective_to);

CREATE TABLE booking_policy_bindings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_version_id uuid NOT NULL REFERENCES booking_policy_versions(id) ON DELETE CASCADE,
    scope_type varchar(20) NOT NULL,
    scope_id uuid,
    priority integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_policy_binding_scope CHECK (
      (scope_type = 'GLOBAL' AND scope_id IS NULL)
      OR (scope_type IN ('ORGANIZATION', 'BRANCH', 'PROPERTY') AND scope_id IS NOT NULL)
    ),
    UNIQUE NULLS NOT DISTINCT (policy_version_id, scope_type, scope_id)
);
CREATE INDEX ix_booking_policy_binding_resolution
  ON booking_policy_bindings(scope_type, scope_id, priority DESC);

CREATE TABLE booking_policy_change_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id uuid NOT NULL REFERENCES booking_policies(id) ON DELETE RESTRICT,
    policy_version_id uuid NOT NULL REFERENCES booking_policy_versions(id) ON DELETE RESTRICT,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    expected_policy_version bigint NOT NULL,
    request_hash char(64) NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    requested_by_account_id uuid NOT NULL,
    decided_by_account_id uuid,
    reason varchar(500) NOT NULL,
    decision_reason varchar(500),
    requested_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    CONSTRAINT ck_booking_policy_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_booking_policy_request_checker CHECK (decided_by_account_id IS NULL OR decided_by_account_id <> requested_by_account_id),
    UNIQUE (requested_by_account_id, idempotency_key)
);

CREATE TABLE booking_policy_simulations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_account_id uuid NOT NULL,
    policy_version_id uuid NOT NULL REFERENCES booking_policy_versions(id) ON DELETE RESTRICT,
    evaluation_context jsonb NOT NULL CHECK (jsonb_typeof(evaluation_context) = 'object'),
    result jsonb NOT NULL CHECK (jsonb_typeof(result) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE booking_policy_evaluation_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_quote_id uuid REFERENCES booking_quotes(id) ON DELETE SET NULL,
    booking_id uuid REFERENCES bookings(id) ON DELETE SET NULL,
    policy_id uuid NOT NULL REFERENCES booking_policies(id) ON DELETE RESTRICT,
    policy_version_id uuid NOT NULL REFERENCES booking_policy_versions(id) ON DELETE RESTRICT,
    binding_id uuid NOT NULL REFERENCES booking_policy_bindings(id) ON DELETE RESTRICT,
    policy_fingerprint char(64) NOT NULL,
    evaluation_context jsonb NOT NULL CHECK (jsonb_typeof(evaluation_context) = 'object'),
    result jsonb NOT NULL CHECK (jsonb_typeof(result) = 'object'),
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_policy_snapshot_target CHECK (booking_quote_id IS NOT NULL OR booking_id IS NOT NULL)
);

INSERT INTO booking_policy_versions(
  policy_id, version, status, effective_from, hold_minutes, minimum_notice_hours,
  maximum_advance_days, cancellation_rules, fingerprint, created_by_account_id,
  approved_by_account_id, reason, published_at
)
SELECT id, 1, 'PUBLISHED', created_at, hold_minutes, minimum_notice_hours,
       maximum_advance_days, cancellation_rules,
       md5(id::text || ':legacy-v1') || md5(id::text || ':legacy-v1:2'),
       '00000000-0000-0000-0000-000000000001'::uuid,
       '00000000-0000-0000-0000-000000000002'::uuid,
       'Migrated from the legacy booking policy', created_at
FROM booking_policies;

INSERT INTO booking_policy_bindings(policy_version_id, scope_type, priority)
SELECT version.id, 'GLOBAL', CASE WHEN policy.is_default THEN 100 ELSE 0 END
FROM booking_policy_versions version
JOIN booking_policies policy ON policy.id = version.policy_id
WHERE version.version = 1;

CREATE OR REPLACE FUNCTION protect_booking_policy_version() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'PUBLISHED' AND NEW.status = 'RETIRED' THEN RETURN NEW; END IF;
  IF OLD.status <> 'DRAFT' THEN RAISE EXCEPTION 'published booking policy versions are immutable'; END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_booking_policy_version_immutable
BEFORE UPDATE OR DELETE ON booking_policy_versions
FOR EACH ROW EXECUTE FUNCTION protect_booking_policy_version();
