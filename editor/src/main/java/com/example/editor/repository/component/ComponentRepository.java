package com.example.editor.repository.component;


import com.example.editor.model.component.Component;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComponentRepository extends JpaRepository<Component, Long> {

    List<Component> findByParentId(Long parentId);

    List<Component> findByType(String type);

    List<Component> findByParentIsNullAndType(String type);

    List<Component> findByParentIdAndType(Long parentId, String type);

    /**
     * Сцена под блокировкой строки — вход в сохранение. Два одновременных PUT одной сцены
     * сериализуются здесь: второй ждёт коммита первого, видит его версию и уходит в слияние,
     * вместо того чтобы затереть его правку или упасть на @Version (scada-ddk).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Component c where c.id = :id")
    Optional<Component> findByIdForUpdate(@Param("id") Long id);
}