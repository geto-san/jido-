package com.geto.jido.server.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Case-insensitive lookup, mirroring the {@code users_email_ci_uniq} index.
     * Registration normalises to lowercase, but login must not depend on the
     * caller having done so.
     */
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
