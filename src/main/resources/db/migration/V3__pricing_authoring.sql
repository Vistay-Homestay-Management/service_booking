SET search_path TO app, extensions, public;

-- Pricing versions are append-only. Publication is deliberately modeled by a
-- separate lifecycle in SCRUM-357 so authoring never rewrites historical input.
CREATE TABLE property_rate_profile_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL,
    version integer NOT NULL,
    effective_from date NOT NULL,
    effective_to_exclusive date,
    base_daily_rate bigint NOT NULL,
    medium_daily_factor_bps integer NOT NULL DEFAULT 7000,
    long_daily_factor_bps integer NOT NULL DEFAULT 5500,
    currency char(3) NOT NULL DEFAULT 'VND',
    check_in_time time NOT NULL DEFAULT '14:00',
    check_out_time time NOT NULL DEFAULT '12:00',
    turnover_minutes integer NOT NULL DEFAULT 120,
    quote_ttl_minutes integer NOT NULL DEFAULT 15,
    commission_default_bps integer NOT NULL DEFAULT 500,
    commission_max_bps integer NOT NULL DEFAULT 2000,
    reason varchar(1000) NOT NULL,
    authored_by_admin_account_id uuid NOT NULL,
    correlation_id varchar(100),
    authored_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_property_rate_profile_version UNIQUE (property_id, version),
    CONSTRAINT ck_rate_profile_dates CHECK (
        effective_to_exclusive IS NULL OR effective_to_exclusive > effective_from
    ),
    CONSTRAINT ck_rate_profile_base CHECK (base_daily_rate > 0),
    CONSTRAINT ck_rate_profile_factors CHECK (
        medium_daily_factor_bps BETWEEN 1 AND 10000
        AND long_daily_factor_bps BETWEEN 1 AND medium_daily_factor_bps
    ),
    CONSTRAINT ck_rate_profile_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_rate_profile_turnover CHECK (
        check_in_time > check_out_time
        AND turnover_minutes BETWEEN 0 AND 720
        AND turnover_minutes <= extract(epoch FROM (check_in_time - check_out_time)) / 60
    ),
    CONSTRAINT ck_rate_profile_quote_ttl CHECK (quote_ttl_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_rate_profile_commission CHECK (
        commission_default_bps BETWEEN 0 AND commission_max_bps
        AND commission_max_bps BETWEEN 0 AND 2000
    ),
    CONSTRAINT ck_rate_profile_reason CHECK (length(trim(reason)) BETWEEN 10 AND 1000)
);
CREATE INDEX ix_property_rate_profile_history
    ON property_rate_profile_versions(property_id, version DESC);

CREATE TABLE pricing_rule_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_profile_version_id uuid NOT NULL
        REFERENCES property_rate_profile_versions(id) ON DELETE RESTRICT,
    code varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    effective_from date NOT NULL,
    effective_to_exclusive date,
    priority integer NOT NULL,
    conditions jsonb NOT NULL DEFAULT '{}'::jsonb,
    action_type varchar(32) NOT NULL,
    action_value bigint NOT NULL,
    authored_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pricing_rule_code UNIQUE (rate_profile_version_id, code),
    CONSTRAINT ck_pricing_rule_code CHECK (code ~ '^[a-z][a-z0-9_-]{1,79}$'),
    CONSTRAINT ck_pricing_rule_dates CHECK (
        effective_to_exclusive IS NULL OR effective_to_exclusive > effective_from
    ),
    CONSTRAINT ck_pricing_rule_priority CHECK (priority BETWEEN 0 AND 100000),
    CONSTRAINT ck_pricing_rule_conditions CHECK (jsonb_typeof(conditions) = 'object'),
    CONSTRAINT ck_pricing_rule_action CHECK (action_type IN (
        'FIXED_DAILY_RATE', 'PERCENT_DISCOUNT', 'FIXED_DISCOUNT',
        'PERCENT_SURCHARGE', 'FIXED_SURCHARGE'
    )),
    CONSTRAINT ck_pricing_rule_value CHECK (
        (action_type IN ('PERCENT_DISCOUNT', 'PERCENT_SURCHARGE') AND action_value BETWEEN 1 AND 10000)
        OR (action_type IN ('FIXED_DAILY_RATE', 'FIXED_DISCOUNT', 'FIXED_SURCHARGE') AND action_value > 0)
    )
);
CREATE INDEX ix_pricing_rule_profile
    ON pricing_rule_versions(rate_profile_version_id, priority, code);

CREATE OR REPLACE FUNCTION reject_pricing_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'pricing versions are immutable; create a new version'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_property_rate_profile_immutable
BEFORE UPDATE OR DELETE ON property_rate_profile_versions
FOR EACH ROW EXECUTE FUNCTION reject_pricing_version_mutation();

CREATE TRIGGER trg_pricing_rule_immutable
BEFORE UPDATE OR DELETE ON pricing_rule_versions
FOR EACH ROW EXECUTE FUNCTION reject_pricing_version_mutation();
