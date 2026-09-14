package com.example.editor.repository.automation;

import com.example.editor.model.automation.AutomationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AutomationOutboxRepository extends JpaRepository<AutomationOutbox, Long> {

    /**
     * Неотправленные строки в порядке записи, с блокировкой. {@code SKIP LOCKED}: второй экземпляр
     * {@code editor} не возьмёт те же строки и не опубликует версию дважды одновременно.
     * Вызывать только внутри транзакции.
     */
    @Query(value = "SELECT * FROM editor.automation_outbox WHERE published_at IS NULL "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<AutomationOutbox> lockPending(@Param("limit") int limit);
}
