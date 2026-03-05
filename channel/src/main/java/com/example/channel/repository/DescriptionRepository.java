package com.example.channel.repository;

import com.example.channel.model.Description;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DescriptionRepository extends JpaRepository<Description, Long> {
    @Query("SELECT d FROM Description d ORDER BY d.id ASC")
    List<Description> findAll();

    Description findByName(String name);
    Description findById(long id);
}