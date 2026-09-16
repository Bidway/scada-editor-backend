package com.example.runtime.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DriverLeaseRepository extends JpaRepository<DriverLeaseEntity, Long> {

    Optional<DriverLeaseEntity> findByDriver(String driver);
}
