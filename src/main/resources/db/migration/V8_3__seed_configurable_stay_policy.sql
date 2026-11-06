SET search_path TO app, extensions, public;

INSERT INTO booking_policies(code, name, hold_minutes, minimum_notice_hours,
  maximum_advance_days, cancellation_rules, active, is_default, version, policy_type, row_version)
VALUES ('standard-stay', 'Standard stay', 30, 2, 365,
  '{"refundableUntilHours":48,"lateCancellationChargePercent":100}'::jsonb,
  true, NOT EXISTS(SELECT 1 FROM booking_policies WHERE is_default), 0, 'RESERVATION', 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO booking_policy_versions(policy_id, version, status, effective_from,
  hold_minutes, minimum_notice_hours, maximum_advance_days, minimum_stay_nights,
  minimum_guest_age, allowed_stay_types, allowed_channels, cancellation_rules,
  fingerprint, created_by_account_id, approved_by_account_id, reason, published_at)
SELECT policy.id, 1, 'PUBLISHED', now(), 30, 2, 365, 1, 18,
  '["SHORT","MEDIUM","LONG"]'::jsonb,
  '["ADMIN_PORTAL","CUSTOMER_WEB","OTA"]'::jsonb,
  policy.cancellation_rules,
  md5(policy.id::text || ':standard-v1') || md5(policy.id::text || ':standard-v1:2'),
  '00000000-0000-0000-0000-000000000001'::uuid,
  '00000000-0000-0000-0000-000000000002'::uuid,
  'Safe initial policy migrated as configurable seed', now()
FROM booking_policies policy
WHERE policy.code = 'standard-stay'
  AND NOT EXISTS(SELECT 1 FROM booking_policy_versions version WHERE version.policy_id = policy.id);

INSERT INTO booking_policy_bindings(policy_version_id, scope_type, priority)
SELECT version.id, 'GLOBAL', 100
FROM booking_policy_versions version
JOIN booking_policies policy ON policy.id = version.policy_id
WHERE policy.code = 'standard-stay'
  AND NOT EXISTS(SELECT 1 FROM booking_policy_bindings binding WHERE binding.policy_version_id = version.id);
