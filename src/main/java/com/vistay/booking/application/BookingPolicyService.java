package com.vistay.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class BookingPolicyService {
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,59}$");
    private static final Set<String> TYPES = Set.of("RESERVATION", "CANCELLATION", "CHECK_IN", "CHECK_OUT", "OCCUPANCY", "PAYMENT");
    private static final Set<String> SCOPES = Set.of("GLOBAL", "ORGANIZATION", "BRANCH", "PROPERTY");
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public BookingPolicyService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<PolicyView> catalog(boolean includeDrafts) {
        return jdbc.sql("""
            SELECT policy.id, policy.code, policy.name, policy.policy_type, policy.active,
                   policy.row_version, version.id AS version_id, version.version,
                   version.status, version.effective_from, version.effective_to,
                   version.hold_minutes, version.minimum_notice_hours,
                   version.maximum_advance_days, version.minimum_stay_nights,
                   version.maximum_stay_nights, version.minimum_guest_age,
                   version.security_deposit_amount::text AS security_deposit_amount,
                   version.currency, version.allowed_stay_types::text AS allowed_stay_types_json,
                   version.allowed_channels::text AS allowed_channels_json,
                   version.cancellation_rules::text AS cancellation_rules_json,
                   version.guest_rules::text AS guest_rules_json, version.fingerprint,
                   version.created_by_account_id, version.approved_by_account_id, version.reason
            FROM booking_policies policy
            JOIN LATERAL (
              SELECT candidate.* FROM booking_policy_versions candidate
              WHERE candidate.policy_id = policy.id
                AND (:includeDrafts OR candidate.status = 'PUBLISHED')
              ORDER BY CASE candidate.status WHEN 'PUBLISHED' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                       candidate.version DESC LIMIT 1
            ) version ON true
            WHERE policy.active OR :includeDrafts
            ORDER BY policy.code
            """).param("includeDrafts", includeDrafts).query(PolicyView.class).list();
    }

    public List<ChangeRequestView> changeRequests(String status) {
        return jdbc.sql("""
            SELECT request.id, request.policy_id, policy.code AS policy_code,
                   request.policy_version_id, request.status, request.expected_policy_version,
                   request.requested_by_account_id, request.decided_by_account_id,
                   request.reason, request.decision_reason, request.requested_at, request.decided_at
            FROM booking_policy_change_requests request
            JOIN booking_policies policy ON policy.id = request.policy_id
            WHERE (:status IS NULL OR request.status = :status)
            ORDER BY request.requested_at DESC
            """).param("status", status == null ? null : status.toUpperCase(Locale.ROOT))
            .query(ChangeRequestView.class).list();
    }

    @Transactional
    public ChangeRequestView request(UUID actorId, PolicyChangeInput input) {
        validate(input);
        String requestHash = hash(json(input));
        var existing = jdbc.sql("""
            SELECT id, request_hash FROM booking_policy_change_requests
            WHERE requested_by_account_id=:actor AND idempotency_key=:key
            """).param("actor", actorId).param("key", input.idempotencyKey())
            .query(Idempotency.class).optional().orElse(null);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) throw error("IDEMPOTENCY_CONFLICT", "Idempotency key was reused with a different policy");
            return findRequest(existing.id());
        }

        String code = normalize(input.code());
        UUID policyId = jdbc.sql("""
            INSERT INTO booking_policies(code,name,hold_minutes,minimum_notice_hours,maximum_advance_days,
              cancellation_rules,active,is_default,version,policy_type,row_version)
            VALUES (:code,:name,:hold,:notice,:advance,CAST(:cancel AS jsonb),true,false,0,:type,1)
            ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, updated_at=now()
            RETURNING id
            """).param("code", code).param("name", required(input.name(), "name"))
            .param("hold", input.holdMinutes()).param("notice", input.minimumNoticeHours())
            .param("advance", input.maximumAdvanceDays()).param("cancel", json(input.cancellationRules()))
            .param("type", upper(input.policyType())).query(UUID.class).single();
        long currentVersion = jdbc.sql("SELECT row_version FROM booking_policies WHERE id=:id FOR UPDATE")
            .param("id", policyId).query(Long.class).single();
        if (currentVersion != input.expectedPolicyVersion()) throw error("STALE_VERSION", "Booking policy changed after it was loaded");
        int version = jdbc.sql("SELECT COALESCE(MAX(version),0)+1 FROM booking_policy_versions WHERE policy_id=:id")
            .param("id", policyId).query(Integer.class).single();
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO booking_policy_versions(id,policy_id,version,status,effective_from,effective_to,
              hold_minutes,minimum_notice_hours,maximum_advance_days,minimum_stay_nights,
              maximum_stay_nights,minimum_guest_age,security_deposit_amount,currency,
              allowed_stay_types,allowed_channels,cancellation_rules,guest_rules,fingerprint,
              created_by_account_id,reason)
            VALUES (:id,:policy,:version,'DRAFT',:from,:to,:hold,:notice,:advance,:minStay,
              :maxStay,:age,:deposit,:currency,CAST(:stayTypes AS jsonb),CAST(:channels AS jsonb),
              CAST(:cancel AS jsonb),CAST(:guest AS jsonb),:fingerprint,:actor,:reason)
            """).param("id", versionId).param("policy", policyId).param("version", version)
            .param("from", OffsetDateTime.parse(input.effectiveFrom())).param("to", input.effectiveTo() == null ? null : OffsetDateTime.parse(input.effectiveTo()))
            .param("hold", input.holdMinutes()).param("notice", input.minimumNoticeHours()).param("advance", input.maximumAdvanceDays())
            .param("minStay", input.minimumStayNights()).param("maxStay", input.maximumStayNights())
            .param("age", input.minimumGuestAge()).param("deposit", input.securityDepositAmount())
            .param("currency", upper(input.currency())).param("stayTypes", json(input.allowedStayTypes()))
            .param("channels", json(input.allowedChannels())).param("cancel", json(input.cancellationRules()))
            .param("guest", json(input.guestRules())).param("fingerprint", requestHash).param("actor", actorId)
            .param("reason", requiredReason(input.reason())).update();
        for (var binding : input.bindings()) {
            String scope = upper(binding.scopeType());
            if (!SCOPES.contains(scope) || ("GLOBAL".equals(scope) != (binding.scopeId() == null))) {
                throw error("INVALID_POLICY_SCOPE", "Global scope has no id; other scopes require one");
            }
            jdbc.sql("""
                INSERT INTO booking_policy_bindings(policy_version_id,scope_type,scope_id,priority)
                VALUES (:version,:scope,:scopeId,:priority)
                """).param("version", versionId).param("scope", scope).param("scopeId", binding.scopeId())
                .param("priority", binding.priority() == null ? 0 : binding.priority()).update();
        }
        UUID requestId = jdbc.sql("""
            INSERT INTO booking_policy_change_requests(policy_id,policy_version_id,status,
              expected_policy_version,request_hash,idempotency_key,requested_by_account_id,reason)
            VALUES (:policy,:version,'PENDING',:expected,:hash,:key,:actor,:reason) RETURNING id
            """).param("policy", policyId).param("version", versionId).param("expected", currentVersion)
            .param("hash", requestHash).param("key", input.idempotencyKey()).param("actor", actorId)
            .param("reason", requiredReason(input.reason())).query(UUID.class).single();
        audit(actorId, "BOOKING_POLICY_CHANGE_REQUESTED", policyId, Map.of("requestId", requestId, "version", version));
        return findRequest(requestId);
    }

    @Transactional
    public ChangeRequestView decide(UUID actorId, UUID requestId, boolean approve, String reason) {
        var request = jdbc.sql("""
            SELECT id, policy_id, policy_version_id, requested_by_account_id,
                   expected_policy_version, status
            FROM booking_policy_change_requests WHERE id=:id FOR UPDATE
            """).param("id", requestId).query(Pending.class).optional()
            .orElseThrow(() -> error("BOOKING_POLICY_REQUEST_NOT_FOUND", "Change request was not found"));
        if (!"PENDING".equals(request.status())) return findRequest(requestId);
        if (actorId.equals(request.requestedByAccountId())) throw error("SELF_APPROVAL_FORBIDDEN", "Requester cannot approve or reject their own policy");
        String decision = approve ? "APPROVED" : "REJECTED";
        if (approve) {
            long rowVersion = jdbc.sql("SELECT row_version FROM booking_policies WHERE id=:id FOR UPDATE")
                .param("id", request.policyId()).query(Long.class).single();
            if (rowVersion != request.expectedPolicyVersion()) throw error("STALE_VERSION", "Booking policy changed before approval");
            jdbc.sql("UPDATE booking_policy_versions SET status='RETIRED' WHERE policy_id=:id AND status='PUBLISHED'")
                .param("id", request.policyId()).update();
            jdbc.sql("""
                UPDATE booking_policy_versions SET status='PUBLISHED',approved_by_account_id=:actor,
                  reason=:reason,published_at=now() WHERE id=:id AND status='DRAFT'
                """).param("actor", actorId).param("reason", requiredReason(reason)).param("id", request.policyVersionId()).update();
            jdbc.sql("UPDATE booking_policies SET row_version=row_version+1,updated_at=now() WHERE id=:id")
                .param("id", request.policyId()).update();
            outbox(request.policyId(), rowVersion + 1, request.policyVersionId());
        } else {
            jdbc.sql("UPDATE booking_policy_versions SET status='REJECTED',reason=:reason WHERE id=:id AND status='DRAFT'")
                .param("reason", requiredReason(reason)).param("id", request.policyVersionId()).update();
        }
        jdbc.sql("""
            UPDATE booking_policy_change_requests SET status=:status,decided_by_account_id=:actor,
              decision_reason=:reason,decided_at=now() WHERE id=:id
            """).param("status", decision).param("actor", actorId).param("reason", requiredReason(reason))
            .param("id", requestId).update();
        audit(actorId, "BOOKING_POLICY_CHANGE_" + decision, request.policyId(), Map.of("requestId", requestId));
        return findRequest(requestId);
    }

    @Transactional
    public SimulationView simulate(UUID actorId, PolicySimulationInput input) {
        var resolved = resolve(input.policyType(), input.organizationId(), input.branchId(), input.propertyId(), OffsetDateTime.parse(input.at()));
        int nights = input.stayNights();
        boolean allowed = nights >= resolved.minimumStayNights()
            && (resolved.maximumStayNights() == null || nights <= resolved.maximumStayNights())
            && input.noticeHours() >= resolved.minimumNoticeHours()
            && input.advanceDays() <= resolved.maximumAdvanceDays();
        var reasons = new java.util.ArrayList<String>();
        if (nights < resolved.minimumStayNights()) reasons.add("MINIMUM_STAY_NOT_MET");
        if (resolved.maximumStayNights() != null && nights > resolved.maximumStayNights()) reasons.add("MAXIMUM_STAY_EXCEEDED");
        if (input.noticeHours() < resolved.minimumNoticeHours()) reasons.add("MINIMUM_NOTICE_NOT_MET");
        if (input.advanceDays() > resolved.maximumAdvanceDays()) reasons.add("MAXIMUM_ADVANCE_EXCEEDED");
        var context = Map.of("stayNights", nights, "noticeHours", input.noticeHours(), "advanceDays", input.advanceDays());
        var result = Map.of("allowed", allowed, "reasons", reasons);
        UUID id = jdbc.sql("""
            INSERT INTO booking_policy_simulations(actor_account_id,policy_version_id,evaluation_context,result)
            VALUES (:actor,:version,CAST(:context AS jsonb),CAST(:result AS jsonb)) RETURNING id
            """).param("actor", actorId).param("version", resolved.versionId()).param("context", json(context))
            .param("result", json(result)).query(UUID.class).single();
        return new SimulationView(id, allowed, List.copyOf(reasons), resolved);
    }

    public ResolvedPolicy resolve(String policyType, UUID organizationId, UUID branchId, UUID propertyId, OffsetDateTime at) {
        return jdbc.sql("""
            SELECT policy.id AS policy_id, policy.code, version.id AS version_id, version.version,
                   binding.id AS binding_id, binding.scope_type, binding.scope_id,
                   version.hold_minutes,version.minimum_notice_hours,version.maximum_advance_days,
                   version.minimum_stay_nights,version.maximum_stay_nights,version.fingerprint
            FROM booking_policy_versions version
            JOIN booking_policies policy ON policy.id=version.policy_id AND policy.active
            JOIN booking_policy_bindings binding ON binding.policy_version_id=version.id
            WHERE policy.policy_type=:type AND version.status='PUBLISHED'
              AND version.effective_from<=:at AND (version.effective_to IS NULL OR version.effective_to>:at)
              AND ((binding.scope_type='PROPERTY' AND binding.scope_id=:property)
                OR (binding.scope_type='BRANCH' AND binding.scope_id=:branch)
                OR (binding.scope_type='ORGANIZATION' AND binding.scope_id=:organization)
                OR binding.scope_type='GLOBAL')
            ORDER BY CASE binding.scope_type WHEN 'PROPERTY' THEN 4 WHEN 'BRANCH' THEN 3
              WHEN 'ORGANIZATION' THEN 2 ELSE 1 END DESC, binding.priority DESC,
              version.version DESC LIMIT 1
            """).param("type", upper(policyType)).param("at", at).param("property", propertyId)
            .param("branch", branchId).param("organization", organizationId).query(ResolvedPolicy.class)
            .optional().orElseThrow(() -> error("BOOKING_POLICY_NOT_RESOLVED", "No effective policy matches this booking context"));
    }

    private ChangeRequestView findRequest(UUID id) {
        return changeRequests(null).stream().filter(value -> value.id().equals(id)).findFirst()
            .orElseThrow(() -> error("BOOKING_POLICY_REQUEST_NOT_FOUND", "Change request was not found"));
    }
    private void validate(PolicyChangeInput input) {
        if (input == null || !CODE.matcher(normalize(input.code())).matches()) throw error("INVALID_POLICY_CODE", "Policy code must be kebab-case");
        if (!TYPES.contains(upper(input.policyType()))) throw error("INVALID_POLICY_TYPE", "Unsupported policy type");
        if (input.holdMinutes() <= 0 || input.minimumNoticeHours() < 0 || input.maximumAdvanceDays() <= 0
            || input.minimumStayNights() <= 0) throw error("INVALID_POLICY_LIMIT", "Policy limits must be positive");
        if (input.bindings() == null || input.bindings().isEmpty()) throw error("INVALID_POLICY_SCOPE", "At least one policy binding is required");
        required(input.idempotencyKey(), "idempotencyKey"); requiredReason(input.reason());
    }
    private void audit(UUID actor, String action, UUID aggregate, Map<String, ?> changes) {
        jdbc.sql("INSERT INTO audit_events(actor_account_type,actor_account_id,action,aggregate_type,aggregate_id,changes) VALUES ('ADMIN',:actor,:action,'BookingPolicy',:aggregate,CAST(:changes AS jsonb))")
            .param("actor", actor).param("action", action).param("aggregate", aggregate).param("changes", json(changes)).update();
    }
    private void outbox(UUID policyId, long version, UUID policyVersionId) {
        jdbc.sql("INSERT INTO outbox_events(aggregate_type,aggregate_id,aggregate_version,event_type,event_version,payload) VALUES ('BookingPolicy',:id,:version,'vistay.booking.policy-published',1,CAST(:payload AS jsonb))")
            .param("id", policyId).param("version", version).param("payload", json(Map.of("policyId", policyId, "policyVersionId", policyVersionId))).update();
    }
    private String json(Object value) { try { return json.writeValueAsString(value == null ? Map.of() : value); } catch (RuntimeException invalid) { throw error("INVALID_POLICY_JSON", "Policy data is invalid"); } }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw error("INVALID_INPUT", field + " is required"); return value.trim(); }
    private static String requiredReason(String reason) { var value=required(reason,"reason"); if(value.length()<8) throw error("INVALID_REASON","Reason must contain at least 8 characters"); return value; }
    private static PricingPolicyException error(String code, String message) { return new PricingPolicyException(code, message); }

    public record PolicyChangeInput(String code,String name,String policyType,String effectiveFrom,String effectiveTo,
        int holdMinutes,int minimumNoticeHours,int maximumAdvanceDays,int minimumStayNights,Integer maximumStayNights,
        int minimumGuestAge,String securityDepositAmount,String currency,List<String> allowedStayTypes,List<String> allowedChannels,
        Map<String,Object> cancellationRules,Map<String,Object> guestRules,List<PolicyBindingInput> bindings,
        long expectedPolicyVersion,String reason,String idempotencyKey) {}
    public record PolicyBindingInput(String scopeType,UUID scopeId,Integer priority) {}
    public record PolicySimulationInput(String policyType,UUID organizationId,UUID branchId,UUID propertyId,String at,int stayNights,int noticeHours,int advanceDays) {}
    public record PolicyView(UUID id,String code,String name,String policyType,boolean active,long rowVersion,UUID versionId,int version,
        String status,OffsetDateTime effectiveFrom,OffsetDateTime effectiveTo,int holdMinutes,int minimumNoticeHours,int maximumAdvanceDays,
        int minimumStayNights,Integer maximumStayNights,int minimumGuestAge,String securityDepositAmount,String currency,
        String allowedStayTypesJson,String allowedChannelsJson,String cancellationRulesJson,String guestRulesJson,String fingerprint,
        UUID createdByAccountId,UUID approvedByAccountId,String reason) {}
    public record ChangeRequestView(UUID id,UUID policyId,String policyCode,UUID policyVersionId,String status,long expectedPolicyVersion,
        UUID requestedByAccountId,UUID decidedByAccountId,String reason,String decisionReason,OffsetDateTime requestedAt,OffsetDateTime decidedAt) {}
    public record ResolvedPolicy(UUID policyId,String code,UUID versionId,int version,UUID bindingId,String scopeType,UUID scopeId,
        int holdMinutes,int minimumNoticeHours,int maximumAdvanceDays,int minimumStayNights,Integer maximumStayNights,String fingerprint) {}
    public record SimulationView(UUID id,boolean allowed,List<String> reasons,ResolvedPolicy policy) {}
    private record Idempotency(UUID id,String requestHash) {}
    private record Pending(UUID id,UUID policyId,UUID policyVersionId,UUID requestedByAccountId,long expectedPolicyVersion,String status) {}
}
