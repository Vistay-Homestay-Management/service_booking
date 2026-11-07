package com.vistay.booking.adapter.in.graphql;

import com.vistay.contracts.common.v1.AccountType;
import com.vistay.contracts.common.v1.DataScopeType;
import com.vistay.contracts.user.v1.GetRolePoliciesRequest;
import com.vistay.contracts.user.v1.UserAuthorizationServiceGrpc;
import graphql.GraphqlErrorException;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** One role-policy MGET and at most one User gRPC batch per GraphQL request. */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class AuthorizationContext {
    private final StringRedisTemplate redis;
    private final UserAuthorizationServiceGrpc.UserAuthorizationServiceBlockingStub user;
    private final ObjectMapper json;
    private final Duration deadline;
    private final String apiClientId;
    private final int schemaVersion;
    private Map<String, Policy> policies;

    @Autowired
    public AuthorizationContext(
        StringRedisTemplate redis,
        GrpcChannelFactory channels,
        ObjectMapper json,
        @Value("${vistay.authorization.grpc-deadline:2s}") Duration deadline,
        @Value("${vistay.security.audience:vistay-backoffice-api}") String apiClientId,
        @Value("${vistay.authorization.role-policy-schema-version:2}") int schemaVersion
    ) {
        this.redis = redis;
        this.user = UserAuthorizationServiceGrpc.newBlockingStub(channels.createChannel("user"));
        this.json = json;
        this.deadline = deadline;
        this.apiClientId = apiClientId;
        this.schemaVersion = schemaVersion;
    }

    AuthorizationContext(
        StringRedisTemplate redis,
        UserAuthorizationServiceGrpc.UserAuthorizationServiceBlockingStub user,
        ObjectMapper json,
        Duration deadline,
        String apiClientId,
        int schemaVersion
    ) {
        this.redis = redis;
        this.user = user;
        this.json = json;
        this.deadline = deadline;
        this.apiClientId = apiClientId;
        this.schemaVersion = schemaVersion;
    }

    public Set<String> require(Jwt jwt, String permission) {
        var scopes = scopes(jwt, permission);
        if (scopes.isEmpty()) {
            throw error("FORBIDDEN_PERMISSION", "Missing permission " + permission);
        }
        return scopes;
    }

    public Set<String> scopes(Jwt jwt, String permission) {
        initialize(jwt);
        var result = new LinkedHashSet<String>();
        policies.values().forEach(policy -> policy.permissions().stream()
            .filter(item -> permission.equals(item.permissionCode()))
            .forEach(item -> result.addAll(item.allowedScopes())));
        return Set.copyOf(result);
    }

    private void initialize(Jwt jwt) {
        if (policies != null) return;
        if (jwt == null) throw error("UNAUTHENTICATED", "Authentication is required");
        var accountType = accountType(jwt);
        var roles = roles(jwt);
        if (roles.isEmpty()) {
            policies = Map.of();
            return;
        }
        var roleCodes = List.copyOf(roles);
        var keys = roleCodes.stream().map(role ->
            "vistay:authz:role-policy:v2:" + accountType + ":" + role).toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException redisUnavailable) {
            policies = loadFromUser(accountType, roleCodes, redisUnavailable);
            return;
        }

        Map<String, Policy> found = new LinkedHashMap<>();
        var misses = new ArrayList<String>();
        for (var index = 0; index < roleCodes.size(); index++) {
            var raw = values != null && index < values.size() ? values.get(index) : null;
            var policy = decode(raw, accountType, roleCodes.get(index));
            if (policy == null) misses.add(roleCodes.get(index));
            else found.put(policy.roleCode(), policy);
        }
        if (!misses.isEmpty()) found.putAll(loadFromUser(accountType, misses, null));
        policies = Map.copyOf(found);
    }

    private Map<String, Policy> loadFromUser(String accountType, List<String> roles, Throwable redisFailure) {
        try {
            var response = user.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .getRolePolicies(GetRolePoliciesRequest.newBuilder()
                    .setAccountType(AccountType.valueOf("ACCOUNT_TYPE_" + accountType))
                    .addAllRoleCodes(roles).build());
            if (response.getSchemaVersion() != schemaVersion) {
                throw new IllegalStateException("Unsupported role-policy schema");
            }
            Map<String, Policy> result = new LinkedHashMap<>();
            response.getPoliciesList().forEach(policy -> result.put(policy.getRoleCode(), new Policy(
                policy.getRoleCode(), policy.getRoleVersion(),
                policy.getPermissionsList().stream().map(permission -> new Permission(
                    permission.getPermissionCode(),
                    permission.getAllowedScopesList().stream()
                        .filter(scope -> scope != DataScopeType.DATA_SCOPE_TYPE_UNSPECIFIED)
                        .map(scope -> scope.name().substring("DATA_SCOPE_TYPE_".length())).toList(),
                    permission.getPartitionTypesList()
                )).toList()
            )));
            return result;
        } catch (StatusRuntimeException | IllegalStateException userUnavailable) {
            if (redisFailure != null) userUnavailable.addSuppressed(redisFailure);
            throw error("AUTHORIZATION_UNAVAILABLE", "Authorization policy is unavailable");
        }
    }

    private Policy decode(String raw, String accountType, String roleCode) {
        if (raw == null) return null;
        try {
            var entry = json.readValue(raw, CacheEntry.class);
            if (!entry.active() || entry.schemaVersion() != schemaVersion
                || !accountType.equals(entry.accountType()) || !roleCode.equals(entry.roleCode())
                || entry.policy() == null) return null;
            return entry.policy();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> roles(Jwt jwt) {
        var resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null || !(resourceAccess.get(apiClientId) instanceof Map<?, ?> client)
            || !(client.get("roles") instanceof List<?> rawRoles)) return Set.of();
        var result = new LinkedHashSet<String>();
        rawRoles.forEach(value -> {
            if (value instanceof String role && role.matches("^[A-Z][A-Z0-9_]{1,63}$")) result.add(role);
        });
        return Set.copyOf(result);
    }

    private static String accountType(Jwt jwt) {
        var value = jwt.getClaimAsString("account_type");
        var normalized = value == null ? "ADMIN" : value.trim().toUpperCase();
        if (!Set.of("ADMIN", "CUSTOMER", "HOST").contains(normalized)) {
            throw error("INVALID_TOKEN", "Token account type is invalid");
        }
        return normalized;
    }

    private static GraphqlErrorException error(String code, String message) {
        return GraphqlErrorException.newErrorException().message(message)
            .errorClassification(code.equals("AUTHORIZATION_UNAVAILABLE")
                ? ErrorType.INTERNAL_ERROR : ErrorType.FORBIDDEN)
            .extensions(Map.of("code", code)).build();
    }

    private record CacheEntry(String accountType, String roleCode, boolean active,
        long catalogVersion, int schemaVersion, Policy policy) {}
    private record Policy(String roleCode, long roleVersion, List<Permission> permissions) {}
    private record Permission(String permissionCode, List<String> allowedScopes,
        List<String> partitionTypes) {}
}
