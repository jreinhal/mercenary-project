package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository
extends MongoRepository<User, String> {
    public Optional<User> findByUsername(String var1);

    public Optional<User> findByExternalId(String var1);

    public Optional<User> findByEmail(String var1);

    public List<User> findByActiveTrue();
}
