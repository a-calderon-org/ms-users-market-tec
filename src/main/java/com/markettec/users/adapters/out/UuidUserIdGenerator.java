package com.markettec.users.adapters.out;

import com.markettec.users.application.ports.out.UserIdGenerator;
import com.markettec.users.domain.model.UserId;
import java.util.UUID;

public final class UuidUserIdGenerator implements UserIdGenerator {
    @Override
    public UserId nextId() {
        return new UserId("usr_" + UUID.randomUUID());
    }
}
