package com.example.runtime.assignment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface InstanceTopicPrefixRepository extends JpaRepository<InstanceTopicPrefixEntity, String> {

    List<InstanceTopicPrefixEntity> findByTelemetryTopic(String telemetryTopic);

    List<InstanceTopicPrefixEntity> findByTelemetryTopicIn(List<String> telemetryTopics);

    @Transactional
    void deleteByTelemetryTopic(String telemetryTopic);
}
