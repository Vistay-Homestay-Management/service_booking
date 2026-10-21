SET search_path TO app, extensions, public;

CREATE TABLE booking_policies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    hold_minutes integer NOT NULL DEFAULT 30,
    minimum_notice_hours integer NOT NULL DEFAULT 2,
    maximum_advance_days integer NOT NULL DEFAULT 365,
    cancellation_rules jsonb NOT NULL DEFAULT '{}'::jsonb,
    active boolean NOT NULL DEFAULT true,
    is_default boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_policy_values CHECK (hold_minutes > 0 AND minimum_notice_hours >= 0 AND maximum_advance_days > 0),
    CONSTRAINT ck_booking_policy_rules CHECK (jsonb_typeof(cancellation_rules) = 'object')
);
CREATE UNIQUE INDEX ux_booking_policy_default ON booking_policies(is_default) WHERE is_default;

CREATE TABLE booking_quotes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL,
    customer_id uuid,
    check_in_date date NOT NULL,
    check_out_date date NOT NULL,
    adults smallint NOT NULL DEFAULT 1,
    children smallint NOT NULL DEFAULT 0,
    price_unit varchar(16) NOT NULL,
    duration_value numeric(12,3) NOT NULL,
    unit_price numeric(19,4) NOT NULL,
    subtotal_amount numeric(19,4) NOT NULL,
    discount_amount numeric(19,4) NOT NULL DEFAULT 0,
    fee_amount numeric(19,4) NOT NULL DEFAULT 0,
    tax_amount numeric(19,4) NOT NULL DEFAULT 0,
    total_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    price_breakdown jsonb NOT NULL DEFAULT '{}'::jsonb,
    policy_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_quote_dates CHECK (check_out_date > check_in_date),
    CONSTRAINT ck_quote_guests CHECK (adults > 0 AND children >= 0),
    CONSTRAINT ck_quote_price_unit CHECK (price_unit IN ('NIGHT', 'MONTH', 'STAY')),
    CONSTRAINT ck_quote_amounts CHECK (
        duration_value > 0 AND unit_price >= 0 AND subtotal_amount >= 0
        AND discount_amount >= 0 AND fee_amount >= 0 AND tax_amount >= 0 AND total_amount >= 0
        AND total_amount = subtotal_amount - discount_amount + fee_amount + tax_amount
    ),
    CONSTRAINT ck_quote_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_quote_json CHECK (jsonb_typeof(price_breakdown) = 'object' AND jsonb_typeof(policy_snapshot) = 'object'),
    CONSTRAINT ck_quote_status CHECK (status IN ('ACTIVE', 'USED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_quote_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_quote_expiry ON booking_quotes(status, expires_at) WHERE status = 'ACTIVE';

CREATE TABLE bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_code varchar(40) NOT NULL UNIQUE,
    quote_id uuid REFERENCES booking_quotes(id) ON DELETE SET NULL,
    property_id uuid NOT NULL,
    contract_id uuid,
    contract_property_term_id uuid,
    customer_id uuid,
    booking_policy_id uuid REFERENCES booking_policies(id) ON DELETE SET NULL,
    source_channel varchar(24) NOT NULL,
    source_provider varchar(60),
    external_booking_id varchar(180),
    stay_type varchar(24) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    check_in_date date NOT NULL,
    check_out_date date NOT NULL,
    adults smallint NOT NULL DEFAULT 1,
    children smallint NOT NULL DEFAULT 0,
    guest_name varchar(180) NOT NULL,
    guest_phone varchar(32) NOT NULL,
    guest_email varchar(254),
    guest_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    property_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    contract_term_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    price_unit varchar(16) NOT NULL,
    duration_value numeric(12,3) NOT NULL,
    unit_price_snapshot numeric(19,4) NOT NULL,
    subtotal_amount numeric(19,4) NOT NULL,
    discount_amount numeric(19,4) NOT NULL DEFAULT 0,
    fee_amount numeric(19,4) NOT NULL DEFAULT 0,
    tax_amount numeric(19,4) NOT NULL DEFAULT 0,
    total_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    price_breakdown jsonb NOT NULL DEFAULT '{}'::jsonb,
    policy_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by_account_type varchar(16) NOT NULL,
    created_by_account_id uuid,
    assigned_admin_user_id uuid,
    hold_expires_at timestamptz,
    confirmed_at timestamptz,
    checked_in_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    cancellation_reason varchar(1000),
    notes varchar(3000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_dates CHECK (check_out_date > check_in_date),
    CONSTRAINT ck_booking_guests CHECK (adults > 0 AND children >= 0),
    CONSTRAINT ck_booking_source CHECK (source_channel IN ('CUSTOMER_WEB', 'ADMIN_PORTAL', 'OTA')),
    CONSTRAINT ck_booking_stay_type CHECK (stay_type IN ('SHORT_TERM', 'MEDIUM_TERM', 'LONG_TERM')),
    CONSTRAINT ck_booking_status CHECK (status IN ('PENDING_CONFIRMATION', 'HELD', 'CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED')),
    CONSTRAINT ck_booking_actor_type CHECK (created_by_account_type IN ('CUSTOMER', 'ADMIN', 'SYSTEM', 'OTA')),
    CONSTRAINT ck_booking_actor CHECK ((created_by_account_type IN ('SYSTEM', 'OTA')) OR created_by_account_id IS NOT NULL),
    CONSTRAINT ck_booking_price_unit CHECK (price_unit IN ('NIGHT', 'MONTH', 'STAY')),
    CONSTRAINT ck_booking_amounts CHECK (
        duration_value > 0 AND unit_price_snapshot >= 0 AND subtotal_amount >= 0
        AND discount_amount >= 0 AND fee_amount >= 0 AND tax_amount >= 0 AND total_amount >= 0
        AND total_amount = subtotal_amount - discount_amount + fee_amount + tax_amount
    ),
    CONSTRAINT ck_booking_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_booking_snapshots CHECK (
        jsonb_typeof(guest_snapshot) = 'object'
        AND jsonb_typeof(property_snapshot) = 'object'
        AND jsonb_typeof(contract_term_snapshot) = 'object'
        AND jsonb_typeof(price_breakdown) = 'object'
        AND jsonb_typeof(policy_snapshot) = 'object'
    ),
    CONSTRAINT ck_booking_contract_reference CHECK (
        (contract_id IS NULL AND contract_property_term_id IS NULL)
        OR (contract_id IS NOT NULL AND contract_property_term_id IS NOT NULL)
    ),
    CONSTRAINT ck_booking_cancellation CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);
CREATE INDEX ix_booking_property_dates ON bookings(property_id, check_in_date, check_out_date);
CREATE INDEX ix_booking_contract_term ON bookings(contract_property_term_id, created_at DESC)
    WHERE contract_property_term_id IS NOT NULL;
CREATE INDEX ix_booking_customer ON bookings(customer_id, created_at DESC) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_booking_status_dates ON bookings(status, check_in_date, check_out_date);
CREATE UNIQUE INDEX ux_booking_external_reference
    ON bookings(source_provider, external_booking_id)
    WHERE source_provider IS NOT NULL AND external_booking_id IS NOT NULL;

CREATE TABLE booking_guests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    full_name varchar(180) NOT NULL,
    date_of_birth date,
    nationality_code char(3),
    identity_document_last_four char(4),
    identity_document_lookup_hash char(64),
    encrypted_identity_document bytea,
    is_primary boolean NOT NULL DEFAULT false,
    temporary_residence_status varchar(24) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_guest_registration CHECK (temporary_residence_status IN ('PENDING', 'SUBMITTED', 'REGISTERED', 'FAILED', 'NOT_REQUIRED')),
    CONSTRAINT ck_booking_guest_identity_pair CHECK (
        (identity_document_lookup_hash IS NULL AND encrypted_identity_document IS NULL)
        OR (identity_document_lookup_hash IS NOT NULL AND encrypted_identity_document IS NOT NULL)
    )
);
CREATE UNIQUE INDEX ux_booking_primary_guest ON booking_guests(booking_id) WHERE is_primary;

-- The exclusion constraint is the concurrency-safe source of truth for
-- availability. Application code creates/releases this row in the same
-- transaction as the booking state change.
CREATE TABLE inventory_blocks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL,
    booking_id uuid REFERENCES bookings(id) ON DELETE CASCADE,
    block_type varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    start_date date NOT NULL,
    end_date_exclusive date NOT NULL,
    reason varchar(1000),
    created_by_admin_user_id uuid,
    released_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_inventory_dates CHECK (end_date_exclusive > start_date),
    CONSTRAINT ck_inventory_type CHECK (block_type IN ('BOOKING', 'MAINTENANCE', 'CLEANING', 'OWNER_USE', 'MANUAL')),
    CONSTRAINT ck_inventory_status CHECK (status IN ('TENTATIVE', 'ACTIVE', 'RELEASED', 'CANCELLED')),
    CONSTRAINT ck_inventory_booking CHECK ((block_type = 'BOOKING' AND booking_id IS NOT NULL) OR block_type <> 'BOOKING'),
    CONSTRAINT ck_inventory_release CHECK ((status IN ('RELEASED', 'CANCELLED') AND released_at IS NOT NULL) OR status IN ('TENTATIVE', 'ACTIVE'))
);
ALTER TABLE inventory_blocks ADD CONSTRAINT ex_inventory_block_overlap
    EXCLUDE USING gist (
        property_id WITH =,
        daterange(start_date, end_date_exclusive, '[)') WITH &&
    ) WHERE (status IN ('TENTATIVE', 'ACTIVE'));
CREATE INDEX ix_inventory_timeline ON inventory_blocks(property_id, start_date, end_date_exclusive);

CREATE TABLE booking_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    previous_status varchar(32),
    new_status varchar(32) NOT NULL,
    reason varchar(1000),
    actor_account_type varchar(16) NOT NULL,
    actor_account_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_history_actor CHECK (actor_account_type IN ('CUSTOMER', 'ADMIN', 'SYSTEM', 'OTA'))
);
CREATE INDEX ix_booking_status_history ON booking_status_history(booking_id, occurred_at);

CREATE TABLE booking_attributions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    admin_user_id uuid NOT NULL,
    attribution_role varchar(24) NOT NULL,
    attribution_percent numeric(7,4),
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_attribution_role CHECK (attribution_role IN ('CREATOR', 'OWNER', 'SALE', 'CTV', 'SUPPORT')),
    CONSTRAINT ck_booking_attribution_percent CHECK (attribution_percent IS NULL OR attribution_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_booking_attribution_dates CHECK (valid_until IS NULL OR valid_until > valid_from),
    UNIQUE (booking_id, admin_user_id, attribution_role, valid_from)
);

CREATE TABLE command_idempotency (
    idempotency_key varchar(160) PRIMARY KEY,
    command_type varchar(100) NOT NULL,
    actor_account_type varchar(16) NOT NULL,
    actor_account_id uuid,
    request_hash char(64) NOT NULL,
    resource_type varchar(100),
    resource_id uuid,
    response_code integer,
    response_body jsonb,
    status varchar(16) NOT NULL DEFAULT 'PROCESSING',
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_command_actor_type CHECK (actor_account_type IN ('CUSTOMER', 'ADMIN', 'SYSTEM', 'OTA')),
    CONSTRAINT ck_command_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_command_response CHECK (response_body IS NULL OR jsonb_typeof(response_body) IN ('object', 'array')),
    CONSTRAINT ck_command_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_command_idempotency_expiry ON command_idempotency(expires_at);

CREATE TABLE processed_events (
    event_id uuid NOT NULL,
    consumer_name varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL DEFAULT 1 CHECK (event_version > 0),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    last_error text,
    correlation_id varchar(100),
    causation_id varchar(100)
);
CREATE INDEX ix_booking_outbox_pending ON outbox_events(available_at, occurred_at) WHERE published_at IS NULL;

CREATE TABLE audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_account_type varchar(16),
    actor_account_id uuid,
    action varchar(100) NOT NULL,
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    changes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(changes) = 'object'),
    correlation_id varchar(100),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_booking_audit_aggregate ON audit_events(aggregate_type, aggregate_id, created_at DESC);
