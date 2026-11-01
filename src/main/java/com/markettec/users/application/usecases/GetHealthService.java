package com.markettec.users.application.usecases;

import com.markettec.users.application.ports.in.GetHealthQuery;
import com.markettec.users.domain.model.ServiceHealth;

import java.time.Clock;

public final class GetHealthService implements GetHealthQuery {

    private final String serviceName;
    private final Clock clock;

    public GetHealthService(String serviceName, Clock clock) {
        this.serviceName = serviceName;
        this.clock = clock;
    }

    @Override
    public ServiceHealth getHealth() {
        return new ServiceHealth("UP", serviceName, clock.instant().getEpochSecond());
    }
}
