package com.markettec.users.application.ports.out;

import com.markettec.users.domain.model.UserId;

@FunctionalInterface
public interface UserIdGenerator {
    UserId nextId();
}
