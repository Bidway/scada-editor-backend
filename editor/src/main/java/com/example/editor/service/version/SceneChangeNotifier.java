package com.example.editor.service.version;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Рассылает {@code SCENE_CHANGED} открывшим сцену (scada-fqr): без него о чужом сохранении человек
 * узнавал только при своём — в момент слияния или конфликта, когда работа уже сделана.
 * <p>
 * Только после коммита: откаченное сохранение (409, ошибка снимка) не должно рассылать версию,
 * которой в базе нет. Это уведомление, а не синхронизация — правки в открытый редактор не
 * подмешиваются, клиент сам решает, показать ли плашку «обновить».
 */
@Component
public class SceneChangeNotifier {

    static final String TOPIC_PREFIX = "/topic/scenes/";

    private final SimpMessagingTemplate messaging;

    public SceneChangeNotifier(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSceneVersion(SceneVersionRecorded event) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "SCENE_CHANGED");
        message.put("scene_id", event.sceneId());
        message.put("version_no", event.versionNo());
        message.put("user_name", event.userName());
        messaging.convertAndSend(TOPIC_PREFIX + event.sceneId(), message);
    }
}
