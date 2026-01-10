package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

/**
 * Repository for user data access.
 */
@Repository
public interface UserRepository extends MongoRepository<User, String> {

    /**
     * Find user by username.
     */
    Optional<User> findByUsername(String username);

    /**
     * Find user by external ID (OIDC subject or CAC DN).
     */
    Optional<User> findByExternalId(String externalId);

    /**
     * Find user by email.
     */
    Optional<User> findByEmail(String email);

    /**
     * Find all active users.
     */
    List<User> findByActiveTrue();
}
