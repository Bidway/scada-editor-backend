package com.example.editor.service.release;

import com.example.editor.model.ProjectRuntimeFlag;
import com.example.editor.repository.ProjectRuntimeFlagRepository;
import com.example.editor.service.RuntimeProjectsPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Переход на выпуски (29.09.2026): проект, введённый в эксплуатацию до них, получает выпуск №1 из
 * живого дерева и назначается prod — иначе runtime после обновления не нашёл бы, что крутить.
 * Повторный старт ничего не делает: prod уже есть.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InitialReleaseBootstrap {

    private final ProjectRuntimeFlagRepository flags;
    private final ProjectReleaseService releases;
    private final RuntimeProjectsPublisher publisher;

    /**
     * Без общей транзакции: сбой выпуска одного проекта (флаг на удалённый проект → NotFound)
     * пометил бы её rollback-only и откатил выпуски остальных.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        for (ProjectRuntimeFlag flag : flags.findAll()) {
            if (!flag.isInOperation() || flag.getProdVersionNo() != null) {
                continue;
            }
            try {
                Integer versionNo = releases.release(flag.getProjectId(), "начальный выпуск", "system").versionNo();
                flag.setProdVersionNo(versionNo);
                flags.save(flag);
                publisher.publish(flag.getProjectId(), true, versionNo);
                log.info("Проект {}: начальный выпуск {} назначен prod", flag.getProjectId(), versionNo);
            } catch (RuntimeException e) {
                // Флаг на удалённый проект не должен ронять старт editor.
                log.warn("Проект {}: начальный выпуск не создан: {}", flag.getProjectId(), e.getMessage());
            }
        }
    }
}
