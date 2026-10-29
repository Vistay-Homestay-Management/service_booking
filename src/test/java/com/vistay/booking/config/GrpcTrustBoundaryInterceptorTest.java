package com.vistay.booking.config;

import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrpcTrustBoundaryInterceptorTest {
    private static final String PAYMENT_CALLER = "spiffe://vistay/service/payment";
    private final GrpcTrustBoundaryInterceptor interceptor =
        new GrpcTrustBoundaryInterceptor(List.of(PAYMENT_CALLER), Duration.ofSeconds(2));

    @Test
    void acceptsPaymentCertificateAndBoundedDeadline() throws Exception {
        assertThat(interceptor.validate(
            callWithUriSan(PAYMENT_CALLER), Deadline.after(1500, TimeUnit.MILLISECONDS)))
            .isNull();
    }

    @Test
    void exposesTheAuthenticatedSpiffeIdentityToTheGrpcServiceContext() throws Exception {
        var observedCaller = new AtomicReference<String>();
        ServerCallHandler<Object, Object> next = (call, headers) -> {
            observedCaller.set(GrpcTrustBoundaryInterceptor.AUTHENTICATED_CALLER_URI.get());
            return new ServerCall.Listener<>() {};
        };
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Context.current().withDeadlineAfter(1500, TimeUnit.MILLISECONDS, scheduler)
                .call(() -> interceptor.interceptCall(
                    callWithUriSan(PAYMENT_CALLER), new Metadata(), next));
        } finally {
            scheduler.shutdownNow();
        }

        assertThat(observedCaller).hasValue(PAYMENT_CALLER);
    }

    @Test
    void rejectsCallerOutsideAllowList() throws Exception {
        var rejection = interceptor.validate(
            callWithUriSan("spiffe://vistay/service/unknown"),
            Deadline.after(1500, TimeUnit.MILLISECONDS));
        assertThat(rejection.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void rejectsCallsWithoutDeadline() throws Exception {
        var rejection = interceptor.validate(callWithUriSan(PAYMENT_CALLER), null);
        assertThat(rejection.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> callWithUriSan(String uri) throws Exception {
        var certificate = mock(X509Certificate.class);
        Collection<List<?>> names = List.of(List.of(6, uri));
        when(certificate.getSubjectAlternativeNames()).thenReturn(names);
        var session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[] { certificate });
        var call = mock(ServerCall.class);
        when(call.getAttributes()).thenReturn(Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
        return call;
    }
}
