package com.vistay.booking.application;

import com.vistay.booking.application.PricingPolicyValidator.Draft;
import com.vistay.booking.application.PricingPolicyValidator.Rule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PricingPolicyService {
    private static final String COMMAND_TYPE = "CREATE_PROPERTY_RATE_PROFILE_VERSION";
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final PricingPolicyValidator validator;

    public PricingPolicyService(JdbcClient jdbc, ObjectMapper json, PricingPolicyValidator validator) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
    }

    @Transactional
    public ProfileVersion createVersion(UUID actorAccountId, Draft draft) {
        validator.validate(draft);
        var requestHash = hash(draft);
        int commandInserted = jdbc.sql("""
                INSERT INTO command_idempotency(
                    idempotency_key, command_type, actor_account_type, actor_account_id,
                    request_hash, status, expires_at
                ) VALUES (
                    :key, :commandType, 'ADMIN', :actorId, :requestHash, 'PROCESSING',
                    now() + interval '30 days'
                )
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .param("key", draft.idempotencyKey())
            .param("commandType", COMMAND_TYPE)
            .param("actorId", actorAccountId)
            .param("requestHash", requestHash)
            .update();

        var existing = command(draft.idempotencyKey());
        if (commandInserted == 0) {
            if (!COMMAND_TYPE.equals(existing.commandType())
                || !requestHash.equals(existing.requestHash())
                || !actorAccountId.equals(existing.actorAccountId())) {
                throw new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key was already used for a different pricing command");
            }
            if (existing.resourceId() == null || !"SUCCEEDED".equals(existing.status())) {
                throw new PricingPolicyException("IDEMPOTENCY_COMMAND_INCOMPLETE",
                    "The previous pricing command has no durable result");
            }
            return requireVersion(existing.resourceId());
        }

        acquirePropertyLock(draft.propertyId());
        int nextVersion = jdbc.sql("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM property_rate_profile_versions
                WHERE property_id = :propertyId
                """)
            .param("propertyId", draft.propertyId())
            .query(Integer.class)
            .single();

        UUID profileId = jdbc.sql("""
                INSERT INTO property_rate_profile_versions(
                    property_id, version, effective_from, effective_to_exclusive,
                    base_daily_rate, medium_daily_factor_bps, long_daily_factor_bps,
                    check_in_time, check_out_time, turnover_minutes, quote_ttl_minutes,
                    commission_default_bps, commission_max_bps, reason,
                    authored_by_admin_account_id, correlation_id
                ) VALUES (
                    :propertyId, :version, :effectiveFrom, :effectiveTo,
                    :baseDailyRate, :mediumFactor, :longFactor,
                    :checkInTime, :checkOutTime, :turnoverMinutes, :quoteTtlMinutes,
                    :commissionDefault, :commissionMax, :reason, :actorId, :correlationId
                )
                RETURNING id
                """)
            .param("propertyId", draft.propertyId())
            .param("version", nextVersion)
            .param("effectiveFrom", draft.effectiveFrom())
            .param("effectiveTo", draft.effectiveToExclusive())
            .param("baseDailyRate", draft.baseDailyRate())
            .param("mediumFactor", draft.mediumDailyFactorBps())
            .param("longFactor", draft.longDailyFactorBps())
            .param("checkInTime", draft.checkInTime())
            .param("checkOutTime", draft.checkOutTime())
            .param("turnoverMinutes", draft.turnoverMinutes())
            .param("quoteTtlMinutes", draft.quoteTtlMinutes())
            .param("commissionDefault", draft.commissionDefaultBps())
            .param("commissionMax", draft.commissionMaxBps())
            .param("reason", draft.reason().trim())
            .param("actorId", actorAccountId)
            .param("correlationId", blankToNull(draft.correlationId()))
            .query(UUID.class)
            .single();

        for (var rule : draft.rules()) insertRule(profileId, rule);

        var response = writeJson(Map.of("profileVersionId", profileId.toString()));
        jdbc.sql("""
                UPDATE command_idempotency
                SET resource_type = 'PropertyRateProfileVersion', resource_id = :resourceId,
                    response_code = 200, response_body = CAST(:response AS jsonb),
                    status = 'SUCCEEDED', completed_at = now()
                WHERE idempotency_key = :key
                """)
            .param("resourceId", profileId)
            .param("response", response)
            .param("key", draft.idempotencyKey())
            .update();

        var auditChanges = writeJson(Map.of(
            "propertyId", draft.propertyId().toString(),
            "profileVersion", nextVersion,
            "baseDailyRate", draft.baseDailyRate(),
            "ruleCount", draft.rules().size(),
            "reason", draft.reason().trim()
        ));
        jdbc.sql("""
                INSERT INTO audit_events(
                    actor_account_type, actor_account_id, action, aggregate_type,
                    aggregate_id, changes, correlation_id
                ) VALUES (
                    'ADMIN', :actorId, 'PRICING_VERSION_AUTHORED', 'PropertyRateProfileVersion',
                    :profileId, CAST(:changes AS jsonb), :correlationId
                )
                """)
            .param("actorId", actorAccountId)
            .param("profileId", profileId)
            .param("changes", auditChanges)
            .param("correlationId", blankToNull(draft.correlationId()))
            .update();
        return requireVersion(profileId);
    }

    @Transactional(readOnly = true)
    public ProfileVersion requireVersion(UUID id) {
        var profile = jdbc.sql("""
                SELECT id, property_id, version, effective_from,
                       effective_to_exclusive, base_daily_rate, medium_daily_factor_bps,
                       long_daily_factor_bps, currency, check_in_time, check_out_time,
                       turnover_minutes, quote_ttl_minutes, commission_default_bps,
                       commission_max_bps, reason, authored_by_admin_account_id,
                       correlation_id, authored_at
                FROM property_rate_profile_versions
                WHERE id = :id
                """)
            .param("id", id)
            .query(this::mapProfile)
            .optional()
            .orElseThrow(() -> new PricingPolicyException("PRICING_VERSION_NOT_FOUND",
                "Pricing profile version was not found"));
        return profile.withRules(rules(id));
    }

    @Transactional(readOnly = true)
    public List<ProfileVersion> versions(UUID propertyId) {
        return jdbc.sql("""
                SELECT id FROM property_rate_profile_versions
                WHERE property_id = :propertyId
                ORDER BY version DESC
                """)
            .param("propertyId", propertyId)
            .query(UUID.class)
            .list().stream().map(this::requireVersion).toList();
    }

    private void acquirePropertyLock(UUID propertyId) {
        jdbc.sql("""
                SELECT hashtextextended(CAST(:propertyId AS text), 0)
                FROM (SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(:propertyId AS text), 0))) AS acquired
                """)
            .param("propertyId", propertyId)
            .query(Long.class)
            .single();
    }

    private void insertRule(UUID profileId, Rule rule) {
        var conditions = new LinkedHashMap<String, Object>();
        conditions.put("daysOfWeek", rule.daysOfWeek());
        conditions.put("channels", rule.channels());
        if (rule.minimumStayDays() != null) conditions.put("minimumStayDays", rule.minimumStayDays());
        if (rule.maximumStayDays() != null) conditions.put("maximumStayDays", rule.maximumStayDays());
        if (rule.minimumGuests() != null) conditions.put("minimumGuests", rule.minimumGuests());
        if (rule.maximumGuests() != null) conditions.put("maximumGuests", rule.maximumGuests());
        jdbc.sql("""
                INSERT INTO pricing_rule_versions(
                    rate_profile_version_id, code, name, effective_from,
                    effective_to_exclusive, priority, conditions, action_type, action_value
                ) VALUES (
                    :profileId, :code, :name, :effectiveFrom, :effectiveTo, :priority,
                    CAST(:conditions AS jsonb), :actionType, :actionValue
                )
                """)
            .param("profileId", profileId)
            .param("code", rule.code())
            .param("name", rule.name().trim())
            .param("effectiveFrom", rule.effectiveFrom())
            .param("effectiveTo", rule.effectiveToExclusive())
            .param("priority", rule.priority())
            .param("conditions", writeJson(conditions))
            .param("actionType", rule.actionType())
            .param("actionValue", rule.actionValue())
            .update();
    }

    private List<RuleVersion> rules(UUID profileId) {
        return jdbc.sql("""
                SELECT id, code, name, effective_from, effective_to_exclusive,
                       priority, conditions::text AS conditions, action_type, action_value
                FROM pricing_rule_versions
                WHERE rate_profile_version_id = :profileId
                ORDER BY priority, code
                """)
            .param("profileId", profileId)
            .query((rs, rowNumber) -> mapRule(rs))
            .list();
    }

    private ProfileVersion mapProfile(ResultSet rs, int rowNumber) throws SQLException {
        return new ProfileVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("property_id", UUID.class),
            rs.getInt("version"),
            rs.getObject("effective_from", LocalDate.class).toString(),
            dateText(rs.getObject("effective_to_exclusive", LocalDate.class)),
            Long.toString(rs.getLong("base_daily_rate")),
            rs.getInt("medium_daily_factor_bps"),
            rs.getInt("long_daily_factor_bps"),
            rs.getString("currency").trim(),
            rs.getObject("check_in_time", LocalTime.class).toString(),
            rs.getObject("check_out_time", LocalTime.class).toString(),
            rs.getInt("turnover_minutes"),
            rs.getInt("quote_ttl_minutes"),
            rs.getInt("commission_default_bps"),
            rs.getInt("commission_max_bps"),
            rs.getString("reason"),
            rs.getObject("authored_by_admin_account_id", UUID.class),
            rs.getString("correlation_id"),
            rs.getObject("authored_at", OffsetDateTime.class).toString(),
            List.of()
        );
    }

    private RuleVersion mapRule(ResultSet rs) throws SQLException {
        JsonNode conditions;
        try {
            conditions = json.readTree(rs.getString("conditions"));
        } catch (JacksonException invalidStoredJson) {
            throw new IllegalStateException("Stored pricing conditions are invalid", invalidStoredJson);
        }
        return new RuleVersion(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("effective_from", LocalDate.class).toString(),
            dateText(rs.getObject("effective_to_exclusive", LocalDate.class)),
            rs.getInt("priority"),
            integerList(conditions.get("daysOfWeek")),
            stringList(conditions.get("channels")),
            optionalInt(conditions, "minimumStayDays"),
            optionalInt(conditions, "maximumStayDays"),
            optionalInt(conditions, "minimumGuests"),
            optionalInt(conditions, "maximumGuests"),
            rs.getString("action_type"),
            Long.toString(rs.getLong("action_value"))
        );
    }

    private CommandRow command(String key) {
        return jdbc.sql("""
                SELECT command_type, actor_account_id, request_hash, resource_id, status
                FROM command_idempotency WHERE idempotency_key = :key
                """)
            .param("key", key)
            .query((rs, rowNumber) -> new CommandRow(
                rs.getString("command_type"), rs.getObject("actor_account_id", UUID.class),
                rs.getString("request_hash").trim(), rs.getObject("resource_id", UUID.class),
                rs.getString("status")))
            .single();
    }

    private String hash(Draft draft) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                writeJson(draft).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Cannot serialize pricing data", failure);
        }
    }

    private static List<Integer> integerList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var values = new ArrayList<Integer>();
        node.forEach(value -> values.add(value.asInt()));
        return List.copyOf(values);
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var values = new ArrayList<String>();
        node.forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }

    private static Integer optionalInt(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String dateText(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private record CommandRow(String commandType, UUID actorAccountId, String requestHash,
        UUID resourceId, String status) {}

    public record ProfileVersion(
        UUID id,
        UUID propertyId,
        int version,
        String effectiveFrom,
        String effectiveToExclusive,
        String baseDailyRate,
        int mediumDailyFactorBps,
        int longDailyFactorBps,
        String currency,
        String checkInTime,
        String checkOutTime,
        int turnoverMinutes,
        int quoteTtlMinutes,
        int commissionDefaultBps,
        int commissionMaxBps,
        String reason,
        UUID authoredByAdminAccountId,
        String correlationId,
        String authoredAt,
        List<RuleVersion> rules
    ) {
        ProfileVersion withRules(List<RuleVersion> value) {
            return new ProfileVersion(id, propertyId, version, effectiveFrom,
                effectiveToExclusive, baseDailyRate, mediumDailyFactorBps, longDailyFactorBps,
                currency, checkInTime, checkOutTime, turnoverMinutes, quoteTtlMinutes,
                commissionDefaultBps, commissionMaxBps, reason, authoredByAdminAccountId,
                correlationId, authoredAt, value);
        }
    }

    public record RuleVersion(
        UUID id,
        String code,
        String name,
        String effectiveFrom,
        String effectiveToExclusive,
        int priority,
        List<Integer> daysOfWeek,
        List<String> channels,
        Integer minimumStayDays,
        Integer maximumStayDays,
        Integer minimumGuests,
        Integer maximumGuests,
        String actionType,
        String actionValue
    ) {}
}
