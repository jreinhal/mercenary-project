package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.workspace.Workspace;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkspaceRepository extends MongoRepository<Workspace, String> {
    List<Workspace> findByIdIn(Collection<String> ids);

    Optional<Workspace> findByNameIgnoreCase(String name);
}
