package com.example.editor.service.version;

import com.example.editor.dto.version.DocumentVersionDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.exception.VersionMismatchException;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Запись снимков документов. Единственное место, где появляются строки
 * {@code editor.document_version}.
 * <p>
 * Дедупликация по хешу содержимого: если содержимое совпало с последней версией документа,
 * новая строка не создаётся и возвращается прежняя. Без этого автосохранение раз в 15 минут
 * набивало бы историю пустыми версиями, и «отменить последнее действие» упиралось бы в них.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentVersionService {

    private final DocumentVersionRepository repository;
    private final ObjectMapper objectMapper;
    private final List<DocumentSource> sources;

    /**
     * Записывает снимок. Возвращает созданную версию либо прежнюю, если содержимое не менялось.
     *
     * @param restoredFrom номер восстановленной версии для {@code kind = RESTORE}, иначе null
     */
    @Transactional
    public DocumentVersion record(DocumentType targetType, Long targetId, JsonNode content,
                                  String userName, VersionKind kind, Integer restoredFrom) {
        String hash = hashOf(content);
        Optional<DocumentVersion> last =
                repository.findTopByTargetTypeAndTargetIdOrderByVersionNoDesc(targetType, targetId);

        if (last.isPresent() && hash.equals(last.get().getContentHash())) {
            return last.get();
        }

        DocumentVersion version = new DocumentVersion();
        version.setTargetType(targetType);
        version.setTargetId(targetId);
        version.setVersionNo(last.map(v -> v.getVersionNo() + 1).orElse(1));
        version.setKind(kind);
        version.setContent(content);
        version.setContentHash(hash);
        version.setUserName(userName);
        version.setCreatedAt(LocalDateTime.now());
        version.setRestoredFrom(restoredFrom);
        try {
            // saveAndFlush, а не save: INSERT обязан уйти в базу здесь, внутри try. Отложенный до
            // коммита, он выбросил бы нарушение уже за границей метода, где номер версии не виден
            // и перевести его в 409 нечем.
            return repository.saveAndFlush(version);
        } catch (DataIntegrityViolationException e) {
            if (!isVersionCollision(e)) {
                throw e;
            }
            // Номер заняли, пока мы готовили свой, — это и есть проигранная гонка. Оба числа
            // известны без дополнительного чтения (а его тут и не сделать: транзакция уже
            // помечена rollback-only): наш номер занят чужим сохранением, значит он и есть
            // текущий, а отталкивались мы от предыдущего.
            throw new VersionMismatchException(version.getVersionNo() - 1, version.getVersionNo());
        }
    }

    /**
     * Только столкновение по {@code document_version_uk} означает гонку. Любое другое нарушение
     * целостности — это ошибка, и подменять её на 409 значит прятать её от себя же.
     */
    private boolean isVersionCollision(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && "document_version_uk".equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains("document_version_uk")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверяет, что клиент основывался на текущей версии документа.
     * <p>
     * Обязательность определяется состоянием документа, а не HTTP-методом: сохранение
     * существующей сцены через {@code POST} иначе прошло бы мимо проверки, и «последний
     * победил» вернулось бы с другой стороны.
     */
    @Transactional(readOnly = true)
    public void requireBase(DocumentType targetType, Long targetId, Integer basedOnVersion) {
        Optional<DocumentVersion> last =
                repository.findTopByTargetTypeAndTargetIdOrderByVersionNoDesc(targetType, targetId);
        if (last.isEmpty()) {
            return;
        }
        Integer current = last.get().getVersionNo();
        if (basedOnVersion == null) {
            throw new IllegalArgumentException(
                    "based_on_version is required: document already has version " + current);
        }
        if (!basedOnVersion.equals(current)) {
            throw new VersionMismatchException(basedOnVersion, current);
        }
    }

    /**
     * Восстановление дописывает историю, а не отматывает её: содержимое версии N уходит обратно
     * в документ, и результат записывается новой версией с {@code kind = RESTORE} и ссылкой
     * {@code restoredFrom = N}. Отсюда бесплатно получается «отменить отмену», а номера версий
     * никогда не убывают.
     * <p>
     * Снимок делает и сам путь сохранения внутри {@code documentSource.restore} — он не знает,
     * что его позвали ради восстановления, и пишет версию без {@code restoredFrom}. Второй
     * вызов {@link #record} увидит совпадение хеша и вернёт ту же строку, поэтому ссылку
     * проставляем здесь: одна версия на одно восстановление, а не две.
     */
    @Transactional
    public DocumentVersion restore(DocumentType targetType, Long targetId, Integer versionNo,
                                   String userName) {
        DocumentVersion source = require(targetType, targetId, versionNo);
        DocumentSource documentSource = sourceOf(targetType);
        documentSource.restore(targetId, source.getContent(), userName);

        DocumentVersion created = record(targetType, targetId, documentSource.contentOf(targetId),
                userName, VersionKind.RESTORE, versionNo);
        if (created.getRestoredFrom() == null) {
            created.setKind(VersionKind.RESTORE);
            created.setRestoredFrom(versionNo);
            repository.save(created);
        }
        return created;
    }

    private DocumentSource sourceOf(DocumentType targetType) {
        return sources.stream()
                .filter(s -> s.type() == targetType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No DocumentSource for " + targetType));
    }

    /** Потолок выборки: история сцены растёт на ~32 версии в день при автосохранении. */
    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 100;

    @Transactional(readOnly = true)
    public List<DocumentVersionDto> list(DocumentType targetType, Long targetId) {
        return list(targetType, targetId, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersionDto> list(DocumentType targetType, Long targetId,
                                         LocalDateTime from, LocalDateTime to,
                                         List<VersionKind> kinds, Integer limit) {
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIMIT + ", got " + effectiveLimit);
        }
        // Фильтр не задан — подставляем все значения: пустой список в JPQL `in` биндить нельзя.
        List<VersionKind> effectiveKinds = kinds == null || kinds.isEmpty()
                ? List.of(VersionKind.values())
                : kinds;
        return repository
                .findFiltered(targetType, targetId, from, to, effectiveKinds,
                        PageRequest.of(0, effectiveLimit))
                .stream()
                .map(v -> new DocumentVersionDto(v.getVersionNo(), v.getKind(), v.getUserName(),
                        v.getCreatedAt(), v.getRestoredFrom()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentVersion require(DocumentType targetType, Long targetId, Integer versionNo) {
        return repository.findByTargetTypeAndTargetIdAndVersionNo(targetType, targetId, versionNo)
                .orElseThrow(() -> new NotFoundException(
                        "Version " + versionNo + " not found for " + targetType + " " + targetId));
    }

    /**
     * Состояние на момент времени — последняя версия, созданная не позже него. История состоит
     * из точек сохранения, а не из непрерывной записи.
     */
    @Transactional(readOnly = true)
    public JsonNode contentAt(DocumentType targetType, Long targetId, LocalDateTime moment) {
        return repository
                .findTopByTargetTypeAndTargetIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                        targetType, targetId, moment)
                .map(DocumentVersion::getContent)
                .orElseThrow(() -> new NotFoundException(
                        "No version of " + targetType + " " + targetId + " existed at " + moment));
    }

    /**
     * Хеш от сериализованного содержимого: {@link ObjectMapper#writeValueAsBytes} у
     * {@link JsonNode} обходит дерево в порядке ключей, а дерево строится маппером одинаково при
     * одинаковых данных. Порядок полей поэтому стабилен, и сравнение хешей означает то же, что
     * сравнение содержимого.
     */
    private String hashOf(JsonNode content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = objectMapper.writeValueAsBytes(withoutLockCounters(content.deepCopy()));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать содержимое версии", e);
        }
    }

    /**
     * {@code version} у компонента — счётчик оптимистичной блокировки Hibernate: он растёт на
     * каждом сохранении независимо от того, менялись ли данные. В хеш он попадать не должен,
     * иначе дедупликация не срабатывает никогда — пересохранение без правок отличается от
     * предыдущей версии ровно этим числом и ничем больше (проверено на задаче 4).
     * <p>
     * Из самого содержимого счётчик не убираем: снимок обязан совпадать по форме с обычным
     * {@code GET} документа, чтобы фронт рисовал старую версию тем же кодом.
     * <p>
     * {@code version_no} вырезается как структурная страховка, а не по факту текущего поведения:
     * сегодня {@code TemplateDocumentSource.contentOf} собирает свой экземпляр
     * {@code TemplateResponseDto} и номер версии в нём остаётся {@code null}, так что в снимок
     * он и не попадает. Но держится это лишь на том, что два места сборки одного DTO ведут себя
     * по-разному: стоит {@code contentOf} перейти на {@code templateService.getTemplateById} —
     * шаг с виду безобидный, — как растущий номер версии окажется в хеше, дедупликация умрёт и
     * история шаблона начнёт набиваться пустыми записями на каждое сохранение. Легитимного
     * {@code version_no} в содержимом документа нет.
     */
    private JsonNode withoutLockCounters(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("version");
            object.remove("version_no");
            object.fields().forEachRemaining(entry -> withoutLockCounters(entry.getValue()));
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::withoutLockCounters);
        }
        return node;
    }
}
