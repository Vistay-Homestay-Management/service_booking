package com.vistay.booking.adapter.in.graphql;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class ServiceInfoQuery {

    @QueryMapping
    public ServiceInfo bookingServiceInfo() {
        return new ServiceInfo("booking", "0.1.0");
    }

    public record ServiceInfo(String name, String version) {
    }
}

