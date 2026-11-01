package com.markettec.users.application.ports.in;

import com.markettec.users.domain.model.ServiceHealth;

public interface GetHealthQuery {
    ServiceHealth getHealth();
}
