package com.markettec.users.application.ports.out;

import com.markettec.users.domain.model.User;
import com.markettec.users.domain.model.UserId;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(UserId id);

    /** Atomic insert; duplicate IDs must fail without overwriting an account. */
    void insert(User user);

    /** Atomic compare-and-set; fails if the stored aggregate differs from expected. */
    void replace(User expected, User replacement);
}
