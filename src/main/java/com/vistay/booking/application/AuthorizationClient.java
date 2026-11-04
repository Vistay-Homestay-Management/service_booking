package com.vistay.booking.application;

import com.vistay.contracts.common.v1.AccountRef;
import com.vistay.contracts.common.v1.AccountType;
import com.vistay.contracts.common.v1.DataScopeType;
import com.vistay.contracts.user.v1.GetAuthorizationSnapshotRequest;
import com.vistay.contracts.user.v1.GetAuthorizationSnapshotResponse;
import com.vistay.contracts.user.v1.UserAuthorizationServiceGrpc;
import graphql.GraphqlErrorException;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuthorizationClient {
    private final UserAuthorizationServiceGrpc.UserAuthorizationServiceBlockingStub stub;
    private final Duration deadline;
    private final Duration cacheTtl;
    private final ObjectMapper json;
    private final Map<UUID, CachedSnapshot> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> minimumVersions = new ConcurrentHashMap<>();

    public AuthorizationClient(
        GrpcChannelFactory channels,
        ObjectMapper json,
        @Value("${vistay.authorization.grpc-deadline:2s}") Duration deadline,
        @Value("${vistay.authorization.local-cache-ttl:30s}") Duration cacheTtl
    ) {
        this.stub = UserAuthorizationServiceGrpc.newBlockingStub(channels.createChannel("user"));
        this.json = json;
        this.deadline = deadline;
        this.cacheTtl = cacheTtl;
    }

    public Snapshot require(UUID accountId, String permission) {
        return require(accountId, permission, null);
    }

    public Snapshot require(UUID accountId, String permission, String forbiddenCode) {
        return enforce(get(accountId), permission, forbiddenCode);
    }

    static Snapshot enforce(Snapshot snapshot, String permission, String forbiddenCode) {
        if (!snapshot.active() || snapshot.profileId() == null) {
            if (forbiddenCode != null) {
                throw error(forbiddenCode, forbiddenMessage(forbiddenCode));
            }
            throw error("PROFILE_INACTIVE", "Administrator profile is inactive");
        }
        if (!snapshot.grants().containsKey(permission) || snapshot.grants().get(permission).isEmpty()) {
            if (forbiddenCode != null) {
                throw error(forbiddenCode, forbiddenMessage(forbiddenCode));
            }
            throw error("FORBIDDEN_PERMISSION", "Missing permission " + permission);
        }
        return snapshot;
    }

    private static String forbiddenMessage(String code) {
        return "INVENTORY_BLOCK_FORBIDDEN".equals(code)
            ? "Inventory block command is not authorized"
            : "Inventory read is not authorized";
    }

    public Snapshot get(UUID accountId) {
        var now = Instant.now();
        var minimumVersion = minimumVersions.getOrDefault(accountId, 0L);
        var cached = cache.get(accountId);
        if (cached != null && cached.expiresAt().isAfter(now)
            && cached.snapshot().authorizationVersion() >= minimumVersion) {
            return cached.snapshot();
        }
        try {
            var response = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .getAuthorizationSnapshot(GetAuthorizationSnapshotRequest.newBuilder()
                    .setAccount(AccountRef.newBuilder().setAccountId(accountId.toString())
                        .setAccountType(AccountType.ACCOUNT_TYPE_ADMIN).build())
                    .setMinimumAuthorizationVersion(minimumVersion)
                    .build());
            var snapshot = decode(response);
            cache.put(accountId, new CachedSnapshot(snapshot, now.plus(cacheTtl)));
            return snapshot;
        } catch (StatusRuntimeException unavailable) {
            throw error("AUTHORIZATION_UNAVAILABLE", "Authorization service is unavailable");
        }
    }

    static Snapshot decode(GetAuthorizationSnapshotResponse response) {
        try {
            if (response == null || response.getAuthorizationVersion() < 0) {
                throw new IllegalArgumentException("invalid authorization version");
            }
            UUID profileId = response.hasProfileId()
                ? UUID.fromString(response.getProfileId()) : null;
            if (response.getActive() && profileId == null) {
                throw new IllegalArgumentException("active snapshot has no profile");
            }
            var roles = new LinkedHashSet<String>();
            for (var role : response.getRolesList()) {
                if (role == null || role.isBlank()) {
                    throw new IllegalArgumentException("blank role");
                }
                roles.add(role);
            }
            var declaredPermissions = new LinkedHashSet<String>();
            for (var permission : response.getPermissionsList()) {
                if (permission == null || permission.isBlank()
                    || !declaredPermissions.add(permission)) {
                    throw new IllegalArgumentException("invalid permission list");
                }
            }

            Map<String, Set<Scope>> grants = new LinkedHashMap<>();
            Map<String, Set<Partition>> partitions = new LinkedHashMap<>();
            for (var grant : response.getGrantsList()) {
                var permission = grant.getPermissionCode();
                if (permission == null || permission.isBlank() || grants.containsKey(permission)) {
                    throw new IllegalArgumentException("invalid permission grant");
                }
                Set<Scope> scopes = new LinkedHashSet<>();
                for (var value : grant.getScopesList()) {
                    if (value == DataScopeType.DATA_SCOPE_TYPE_UNSPECIFIED) {
                        throw new IllegalArgumentException("unspecified scope");
                    }
                    scopes.add(Scope.valueOf(
                        value.name().substring("DATA_SCOPE_TYPE_".length())));
                }
                if (scopes.isEmpty()) {
                    throw new IllegalArgumentException("permission grant has no scope");
                }
                Set<Partition> permissionPartitions = new LinkedHashSet<>();
                for (var partition : grant.getPartitionsList()) {
                    if (partition.getPartitionType().isBlank()) {
                        throw new IllegalArgumentException("blank partition type");
                    }
                    permissionPartitions.add(new Partition(partition.getPartitionType(),
                        UUID.fromString(partition.getPartitionId())));
                }
                if (!permissionPartitions.isEmpty() && !scopes.contains(Scope.PARTITION)) {
                    throw new IllegalArgumentException("partition grant has no partition scope");
                }
                grants.put(permission, Set.copyOf(scopes));
                partitions.put(permission, Set.copyOf(permissionPartitions));
            }
            if (!declaredPermissions.equals(grants.keySet())) {
                throw new IllegalArgumentException("permission evidence is inconsistent");
            }
            return new Snapshot(response.getActive(), profileId,
                response.getAuthorizationVersion(), Map.copyOf(grants),
                Map.copyOf(partitions), Set.copyOf(roles));
        } catch (RuntimeException malformed) {
            throw error("AUTHORIZATION_UNAVAILABLE",
                "Authorization service returned an invalid snapshot");
        }
    }

    @KafkaListener(
        topics = "${vistay.topics.authorization-changed:user.authorization-changed.v1}",
        groupId = "${spring.application.name}-authorization-cache"
    )
    public void authorizationChanged(String rawEnvelope) {
        JsonNode data = json.readTree(rawEnvelope).get("data");
        if (data == null || !"ADMIN".equals(data.get("accountType").asString())) return;
        var accountId = UUID.fromString(data.get("accountId").asString());
        minimumVersions.merge(accountId, data.get("authorizationVersion").asLong(), Math::max);
        cache.remove(accountId);
    }

    private static GraphqlErrorException error(String code, String message) {
        var classification = code.equals("AUTHORIZATION_UNAVAILABLE") ? ErrorType.INTERNAL_ERROR : ErrorType.FORBIDDEN;
        return GraphqlErrorException.newErrorException().message(message)
            .errorClassification(classification).extensions(Map.of("code", code)).build();
    }

    public enum Scope { SELF, OWN, ASSIGNED, ALL, PARTITION }
    public record Partition(String type, UUID id) {}
    public record Snapshot(boolean active, UUID profileId, long authorizationVersion,
        Map<String, Set<Scope>> grants, Map<String, Set<Partition>> partitionGrants,
        Set<String> roles) {
        public Snapshot(
            boolean active,
            UUID profileId,
            long authorizationVersion,
            Map<String, Set<Scope>> grants,
            Map<String, Set<Partition>> partitionGrants
        ) {
            this(active, profileId, authorizationVersion, grants, partitionGrants, Set.of());
        }

        public Set<Scope> scopes(String permission) { return grants.getOrDefault(permission, Set.of()); }
        public Set<Partition> partitions(String permission) {
            return partitionGrants.getOrDefault(permission, Set.of());
        }
    }
    private record CachedSnapshot(Snapshot snapshot, Instant expiresAt) {}
}
