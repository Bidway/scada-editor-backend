package com.example.channel.repository;

import com.example.channel.model.template.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Long> {
    @Query(""" 
            select distinct t
            from Template t
            join fetch t.templateParams
            where t.name = :name
            """)
    Template findByNameWithParams(String name);
}
