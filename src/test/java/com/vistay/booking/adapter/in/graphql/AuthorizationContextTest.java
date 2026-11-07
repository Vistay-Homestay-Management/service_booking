package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vistay.contracts.user.v1.UserAuthorizationServiceGrpc;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;

class AuthorizationContextTest {
    @Test
    void declaresTheRuntimeConstructorForSpringInjection() {
        var injectable = Arrays.stream(AuthorizationContext.class.getDeclaredConstructors())
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .toList();

        assertThat(injectable).hasSize(1);
        assertThat(injectable.getFirst().getParameterCount()).isEqualTo(6);
    }

    @Test
    void performsOneRoleBatchForAliasesAndMultipleRootFields() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        var user = mock(UserAuthorizationServiceGrpc.UserAuthorizationServiceBlockingStub.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of("vistay:authz:role-policy:v2:ADMIN:STAFF")))
            .thenReturn(List.of("""
                {"accountType":"ADMIN","roleCode":"STAFF","active":true,
                 "catalogVersion":9,"schemaVersion":2,
                 "policy":{"roleCode":"STAFF","roleVersion":4,"permissions":[
                   {"permissionCode":"booking:read","allowedScopes":["ASSIGNED"],"partitionTypes":[]},
                   {"permissionCode":"booking-sensitive:read","allowedScopes":["ASSIGNED"],"partitionTypes":[]}
                 ]}}
                """));
        var context = new AuthorizationContext(redis, user, new ObjectMapper(),
            Duration.ofSeconds(2), "vistay-backoffice-api", 2);
        var jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
            Map.of("alg", "none"), Map.of(
                "sub", "00000000-0000-0000-0000-000000000001",
                "account_type", "ADMIN",
                "resource_access", Map.of("vistay-backoffice-api", Map.of("roles", List.of("STAFF")))
            ));

        assertThat(context.require(jwt, "booking:read")).containsExactly("ASSIGNED");
        assertThat(context.scopes(jwt, "booking-sensitive:read")).containsExactly("ASSIGNED");
        verify(values).multiGet(List.of("vistay:authz:role-policy:v2:ADMIN:STAFF"));
        verifyNoInteractions(user);
    }
}
