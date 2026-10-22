package com.example.interview.infrastructure.snapshot;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashSet;
import java.util.Set;

// MongoDB 中保存的持久化文档，不把 Mongo 注解带进领域快照。
@Document("interview_session_snapshot")
public class SnapshotDocument {
    @Id
    private String id;
    private String sessionId;
    private String state;
    private long version;
    private Set<String> processedRequestIds = new HashSet<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Set<String> getProcessedRequestIds() {
        return processedRequestIds;
    }

    public void setProcessedRequestIds(
            Set<String> processedRequestIds
    ) {
        this.processedRequestIds = processedRequestIds;
    }
}