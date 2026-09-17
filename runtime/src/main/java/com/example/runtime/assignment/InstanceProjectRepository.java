package com.example.runtime.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstanceProjectRepository extends JpaRepository<InstanceProjectEntity, Long> {

    List<InstanceProjectEntity> findByInstanceId(String instanceId);
}
