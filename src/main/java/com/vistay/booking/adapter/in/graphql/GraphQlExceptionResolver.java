package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.PricingPolicyException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.GraphqlErrorException;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (exception instanceof PricingPolicyException error) {
            var type = switch (error.code()) {
                case "UNAUTHENTICATED", "INVALID_TOKEN" -> ErrorType.UNAUTHORIZED;
                case "FORBIDDEN_PRICING_SCOPE" -> ErrorType.FORBIDDEN;
                case "PRICING_VERSION_NOT_FOUND" -> ErrorType.NOT_FOUND;
                case "PROPERTY_PARTITION_UNAVAILABLE", "INVALID_STORED_PRICING_POLICY" ->
                    ErrorType.INTERNAL_ERROR;
                default -> ErrorType.BAD_REQUEST;
            };
            return GraphqlErrorBuilder.newError(environment)
                .message(error.getMessage())
                .errorType(type)
                .extensions(java.util.Map.of("code", error.code()))
                .build();
        }
        if (!(exception instanceof GraphqlErrorException error)) {
            return null;
        }
        return GraphqlErrorBuilder.newError(environment)
            .message(error.getMessage())
            .errorType(error.getErrorType())
            .extensions(error.getExtensions())
            .build();
    }
}
