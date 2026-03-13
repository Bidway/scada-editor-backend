package com.example.channel.repository;

import com.example.channel.model.template.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Long> {
    @Query(""" 
            select distinct t
            from Template t
            join fetch t.templateParams
            where t.name = :name
            """)
    Optional<Template> findByNameWithParams(String name);

    @Query(""" 
            select distinct t
            from Template t
            join fetch t.templateParams
            where t.id = :id
            """)
    Optional<Template> findByIdWithParams(Long id);

    @Query("SELECT t FROM Template t ORDER BY t.id ASC")
    List<Template> findAll();
}
