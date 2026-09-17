package com.example.runtime.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstanceTopicRepository extends JpaRepository<InstanceTopicEntity, String> {

    List<InstanceTopicEntity> findByInstanceId(String instanceId);

    boolean existsByCommandsTopicAndTelemetryTopicNot(String commandsTopic, String telemetryTopic);

    boolean existsByResultsTopicAndTelemetryTopicNot(String resultsTopic, String telemetryTopic);
}
