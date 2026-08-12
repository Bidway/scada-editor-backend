package com.example.editor.service.version;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
        return repository.save(version);
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
            byte[] bytes = objectMapper.writeValueAsBytes(content);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать содержимое версии", e);
        }
    }
}
