package com.vistay.booking.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface PropertyPartitionClient {
    Resolution resolve(UUID propertyId);

    default Map<UUID, Resolution> resolveAll(Collection<UUID> propertyIds) {
        var result = new java.util.LinkedHashMap<UUID, Resolution>();
        propertyIds.forEach(id -> result.put(id, resolve(id)));
        return Map.copyOf(result);
    }

    enum Status { MAPPED, NOT_FOUND, UNMAPPED, UNAVAILABLE }

    record Resolution(Status status, UUID branchId, long membershipVersion) {
        public static Resolution mapped(UUID branchId, long membershipVersion) {
            return new Resolution(Status.MAPPED, branchId, membershipVersion);
        }

        public static Resolution missing() {
            return unmapped();
        }

        public static Resolution notFound() {
            return new Resolution(Status.NOT_FOUND, null, 0);
        }

        public static Resolution unmapped() {
            return new Resolution(Status.UNMAPPED, null, 0);
        }

        public static Resolution unavailable() {
            return new Resolution(Status.UNAVAILABLE, null, 0);
        }
    }
}
