package com.example.editor.repository;

import com.example.editor.model.ProjectRuntimeFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ProjectRuntimeFlagRepository extends JpaRepository<ProjectRuntimeFlag, Long> {

    List<ProjectRuntimeFlag> findAllByInOperationTrue();

    /**
     * Записать только {@code image}, не трогая флаг и prod. Через {@code findById} → {@code save}
     * сущность писалась бы целиком, и закрепление вкладки, пришедшее одновременно с вводом в
     * эксплуатацию, вернуло бы в базу прежний {@code in_operation}. Строки нет — создаётся
     * выключенной.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO editor.project_runtime (project_id, in_operation, image)
            VALUES (:projectId, false, CAST(:image AS jsonb))
            ON CONFLICT (project_id) DO UPDATE SET image = EXCLUDED.image
            """, nativeQuery = true)
    void upsertImage(@Param("projectId") Long projectId, @Param("image") String image);
}
