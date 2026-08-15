package com.example.editor.service.version;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.exception.MergeConflictException;
import com.example.editor.exception.VersionMismatchException;
import com.example.editor.merge.MergeChange;
import com.example.editor.merge.MergeShape;
import com.example.editor.merge.SceneMerge;
import com.example.editor.merge.SceneMerger;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Слияние сохранения с чужими правками.
 * <p>
 * Здесь живёт всё, что связано с версиями и базой; сам алгоритм — чистый {@link SceneMerger}.
 */
@Service
@RequiredArgsConstructor
public class SceneMergeService {

    private final DocumentVersionRepository versionRepository;
    private final SceneDocumentSource sceneDocumentSource;
    private final ObjectMapper objectMapper;

    /** Результат: дерево, которое надо записать, и чем оно отличается от присланного. */
    public record MergeOutcome(List<ComponentCreateDto> tree, Integer baseVersion,
                               Integer mergedWithVersion, String theirUser,
                               List<MergeChange> changes) {
    }

    /**
     * Сливает присланное дерево с текущим состоянием сцены.
     * <p>
     * Автосохранение сюда не попадает: решение владельца — {@code AUTOSAVE} при расхождении
     * всегда отвергается, потому что блок {@code merged} некому прочитать, когда сохранение
     * сделал таймер.
     */
    @Transactional(readOnly = true)
    public MergeOutcome merge(Long sceneId, List<ComponentCreateDto> mine, Integer basedOnVersion,
                              Integer currentVersion) {
        Optional<DocumentVersion> base = versionRepository
                .findByTargetTypeAndTargetIdOrderByVersionNoDesc(DocumentType.SCENE, sceneId)
                .stream()
                .filter(version -> version.getVersionNo().equals(basedOnVersion))
                .findFirst();
        if (base.isEmpty()) {
            // Версию могло прорезать прореживание автосохранений: слить не от чего. Это не
            // ошибка клиента и не 500 — обычное «перезапросите документ».
            throw new VersionMismatchException(basedOnVersion, currentVersion);
        }

        DocumentVersion current = versionRepository
                .findTopByTargetTypeAndTargetIdOrderByVersionNoDesc(DocumentType.SCENE, sceneId)
                .orElseThrow(() -> new VersionMismatchException(basedOnVersion, currentVersion));

        List<ComponentCreateDto> baseTree = MergeShape.childrenOf(base.get().getContent(), objectMapper);
        // Чужое берём из живого состояния, а не из снимка: писать мы будем именно туда.
        // Расходиться им негде — сохранение и запись версии идут одной транзакцией (scada-78j).
        List<ComponentCreateDto> theirTree =
                MergeShape.childrenOf(sceneDocumentSource.contentOf(sceneId), objectMapper);

        SceneMerge result = new SceneMerger(objectMapper).merge(baseTree, mine, theirTree);
        if (!result.isClean()) {
            throw new MergeConflictException(basedOnVersion, currentVersion,
                    result.conflicts(), current.getUserName());
        }
        return new MergeOutcome(result.merged(), basedOnVersion, current.getVersionNo(),
                current.getUserName(), result.changes());
    }
}
