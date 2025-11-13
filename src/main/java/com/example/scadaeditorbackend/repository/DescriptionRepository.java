package com.example.scadaeditorbackend.repository;

import com.example.scadaeditorbackend.model.Description;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DescriptionRepository extends JpaRepository<Description, Long> {
    @Query("SELECT d FROM Description d ORDER BY d.id ASC")
    List<Description> findAll();

    Description findByName(String name);
}
