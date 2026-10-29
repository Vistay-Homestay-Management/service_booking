package com.vistay.booking.application;

import java.util.UUID;

public interface PropertyPartitionClient {
    Resolution resolve(UUID propertyId);

    enum Status { MAPPED, MISSING, UNAVAILABLE }

    record Resolution(Status status, UUID branchId, long membershipVersion) {
        public static Resolution mapped(UUID branchId, long membershipVersion) {
            return new Resolution(Status.MAPPED, branchId, membershipVersion);
        }

        public static Resolution missing() {
            return new Resolution(Status.MISSING, null, 0);
        }

        public static Resolution unavailable() {
            return new Resolution(Status.UNAVAILABLE, null, 0);
        }
    }
}
