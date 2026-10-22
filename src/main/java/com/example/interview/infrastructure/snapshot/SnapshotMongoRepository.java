package com.example.interview.infrastructure.snapshot;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SnapshotMongoRepository
        extends MongoRepository<SnapshotDocument, String> {

    Optional<SnapshotDocument> findBySessionId(String sessionId);
}
