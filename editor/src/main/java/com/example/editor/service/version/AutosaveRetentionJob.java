package com.example.editor.service.version;

import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Прореживание автосохранений: старше порога от каждого документа за каждый день остаётся одно,
 * последнее. Ручные сохранения и восстановления не трогаются — их мало, и именно они осознанные
 * точки возврата.
 * <p>
 * Политика заложена сразу, а не после того, как вырастет: телеметрия шлюза доросла до 11 ГБ и
 * заметили это по тому, что перестал подниматься Docker (scada-x2u). Основную часть роста
 * снимает дедупликация по хешу, это — остаток.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutosaveRetentionJob {

    private final DocumentVersionRepository repository;

    @Value("${editor.versions.autosave-retention-days:30}")
    private int retentionDays;

    /** Раз в сутки в 03:30 — время выбрано вне рабочей смены. */
    @Scheduled(cron = "${editor.versions.retention-cron:0 30 3 * * *}")
    @Transactional
    public int thinOut() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        List<DocumentVersion> old = repository
                .findByKindAndCreatedAtLessThanOrderByCreatedAtAsc(VersionKind.AUTOSAVE, threshold);

        Map<String, List<DocumentVersion>> byDocumentAndDay = old.stream()
                .collect(Collectors.groupingBy(v -> v.getTargetType() + "|" + v.getTargetId()
                        + "|" + LocalDate.from(v.getCreatedAt())));

        List<DocumentVersion> doomed = new ArrayList<>();
        for (List<DocumentVersion> ofDay : byDocumentAndDay.values()) {
            // Список отсортирован по времени: последнюю за день оставляем.
            doomed.addAll(ofDay.subList(0, ofDay.size() - 1));
        }

        if (!doomed.isEmpty()) {
            repository.deleteAll(doomed);
            log.info("Autosave retention: removed {} version(s) older than {} day(s)",
                    doomed.size(), retentionDays);
        }
        return doomed.size();
    }
}
