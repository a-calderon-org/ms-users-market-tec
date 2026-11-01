package com.markettec.users.application.ports.out;

import java.util.function.Supplier;

/**
 * Both repositories must participate in the same transaction.
 * Requires serializable account/identity operations, atomic commit and full rollback.
 * A persistent adapter must combine a UNIQUE(issuer, subject) constraint and foreign key
 * with transaction isolation/locking and bounded retry of serialization/unique races,
 * rerunning the entire unit of work to resolve a concurrent registration idempotently.
 */
public interface UserTransactions {
    <T> T execute(Supplier<T> work);
}
