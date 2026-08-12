package com.example.editor.service.version;

import com.example.editor.model.version.DocumentType;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Всё, что механизму версий нужно знать о конкретном виде документа: как прочитать его
 * содержимое и как записать содержимое обратно.
 * <p>
 * Реализаций две, потому что деревьев два: сцены построены на {@code Component}, шаблоны — на
 * отдельной модели {@code TemplateComponent} со своим маппером и своим путём сохранения.
 * Считать шаблон частным случаем сцены нельзя. Зато третий вид документа, если появится,
 * обойдётся одной реализацией, а не параллельной системой.
 */
public interface DocumentSource {

    DocumentType type();

    /**
     * Текущее содержимое документа в той же форме, в какой его отдаёт обычный {@code GET}.
     * Именно поэтому показ старой версии не требует нового кода на фронте.
     */
    JsonNode contentOf(Long targetId);

    /**
     * Записывает содержимое обратно в документ. Вызывается при восстановлении версии; снимок
     * результата делает вызывающий, а не реализация.
     */
    void restore(Long targetId, JsonNode content, String userName);
}
