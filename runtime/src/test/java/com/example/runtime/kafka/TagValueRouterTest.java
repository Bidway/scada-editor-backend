package com.example.runtime.kafka;

import com.example.runtime.script.OnChangeDispatcher;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.OnChangeBinding;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.TagCommandService;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.runtime.stream.SessionOutboundBuffer;
import com.example.runtime.stream.TagUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Разбор телеметрии и решение «пускать ли значение в скрипты» — самая горячая и самая
 * опасная точка runtime: именно здесь недостоверное значение может стать «фактом» на
 * мнемосхеме оператора.
 */
class TagValueRouterTest {

    private static final String TAG = "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST";
    private static final String SESSION = "s-1";

    private RuntimeSessionStore sessionStore;
    private OnChangeDispatcher onChangeDispatcher;
    private TagSubscriptionIndex index;
    private RuntimeSession session;
    private SessionOutboundBuffer buffer;
    private TagValueRouter router;

    @BeforeEach
    void setUp() {
        sessionStore = mock(RuntimeSessionStore.class);
        onChangeDispatcher = mock(OnChangeDispatcher.class);
        index = mock(TagSubscriptionIndex.class);
        session = mock(RuntimeSession.class);
        buffer = new SessionOutboundBuffer();

        when(index.getAllTagIds()).thenReturn(Set.of(TAG));
        when(index.onChangeBindingsForTag(anyString())).thenReturn(List.of());
        when(session.getId()).thenReturn(SESSION);
        when(session.getIndex()).thenReturn(index);
        when(session.getOutboundBuffer()).thenReturn(buffer);
        when(sessionStore.get(SESSION)).thenReturn(session);

        router = new TagValueRouter(sessionStore, mock(ScriptEngineService.class),
                mock(TagCommandService.class), onChangeDispatcher, new ObjectMapper());
        router.registerSession(session);
    }

    @Test
    @DisplayName("подписка на тег без телеметрии даёт кадр «нет данных», а не пустоту")
    void coldStartIsReportedAsNoData() {
        // Холодный старт реального шлюза — до полутора минут: оператор должен видеть,
        // что данных нет, а не смотреть на неотрисованный компонент.
        TagUpdate first = drainTags().get(0);

        assertThat(first.tagId()).isEqualTo(TAG);
        assertThat(first.value()).isNull();
        assertThat(first.quality()).isEqualTo(TagUpdate.BAD);
        assertThat(first.ts()).isZero();
    }

    @Test
    @DisplayName("достоверное значение уходит с меткой времени источника, а не приёма")
    void goodValueCarriesSourceTimestamp() {
        Instant sourceTime = Instant.parse("2026-08-05T09:14:22.183Z");
        drainTags();

        router.onMessage(message("true", "GOOD", sourceTime.toString()));

        TagUpdate update = drainTags().get(0);
        assertThat(update.value()).isEqualTo("true");
        assertThat(update.quality()).isEqualTo(TagUpdate.GOOD);
        assertThat(update.ts()).isEqualTo(sourceTime.toEpochMilli());
    }

    @Test
    @DisplayName("обрыв связи не стирает последнее достоверное значение — снимает только качество")
    void badQualityKeepsLastGoodValueAndItsTimestamp() {
        Instant sourceTime = Instant.parse("2026-08-05T09:14:22.183Z");
        router.onMessage(message("true", "GOOD", sourceTime.toString()));
        drainTags();

        router.onMessage(message(null, "BAD", "2026-08-05T09:20:00.000Z"));

        TagUpdate update = drainTags().get(0);
        // Оператору полезнее «было открыто, связь потеряна» чем пустое место; метка
        // времени остаётся от значения, иначе now-ts показывал бы возраст не значения,
        // а последней неудачной попытки чтения.
        assertThat(update.value()).isEqualTo("true");
        assertThat(update.quality()).isEqualTo(TagUpdate.BAD);
        assertThat(update.ts()).isEqualTo(sourceTime.toEpochMilli());
    }

    @Test
    @DisplayName("epoch-секунды от шлюза не превращаются в 1970 год")
    void fractionalEpochSecondsAreScaledToMillis() {
        drainTags();
        // Так шлюз пишет сегодня: Jackson без JavaTimeModule сериализует Instant в
        // epoch-СЕКУНДЫ с дробью. Принятое за миллисекунды, это дало бы январь 1970-го,
        // и «возраст значения» на экране оператора составил бы 56 лет.
        router.onMessage(new KafkaTagMessageEvent(TAG,
                "{\"value\":true,\"quality\":\"GOOD\",\"timestamp\":1785935496.271793106}"));

        assertThat(drainTags().get(0).ts()).isEqualTo(1785935496272L);
    }

    @Test
    @DisplayName("целые epoch-миллисекунды берутся как есть")
    void integerEpochMillisAreUsedVerbatim() {
        drainTags();

        router.onMessage(new KafkaTagMessageEvent(TAG,
                "{\"value\":true,\"quality\":\"GOOD\",\"timestamp\":1785935496272}"));

        assertThat(drainTags().get(0).ts()).isEqualTo(1785935496272L);
    }

    @Test
    @DisplayName("неизвестное качество трактуется как недостоверное, а не как BAD-строка")
    void unknownQualityIsNotTrusted() {
        router.onMessage(message("true", "GOOD", null));
        drainTags();

        router.onMessage(message("false", "UNCERTAIN", null));

        // Проверять на равенство "BAD" нельзя: контракт расширяемый.
        assertThat(drainTags().get(0).quality()).isEqualTo(TagUpdate.BAD);
    }

    @Test
    @DisplayName("старый формат без quality остаётся достоверным — выкладка сторон независима")
    void legacyEnvelopeWithoutQualityIsTrusted() {
        drainTags();   // кадр регистрации сессии

        router.onMessage(new KafkaTagMessageEvent(TAG,
                "{\"tagId\":385,\"tagName\":\"" + TAG + "\",\"value\":true,\"numericValue\":1.0}"));

        assertThat(drainTags().get(0).quality()).isEqualTo(TagUpdate.GOOD);
    }

    @Test
    @DisplayName("голый скаляр (kafka-sim) разбирается как значение")
    void bareScalarIsAValue() {
        drainTags();   // кадр регистрации сессии

        router.onMessage(new KafkaTagMessageEvent(TAG, "72.7"));

        TagUpdate update = drainTags().get(0);
        assertThat(update.value()).isEqualTo("72.7");
        assertThat(update.quality()).isEqualTo(TagUpdate.GOOD);
    }

    @Test
    @DisplayName("недостоверное значение до скриптов не доходит")
    void scriptsDoNotRunOnBadQuality() {
        when(index.onChangeBindingsForTag(TAG)).thenReturn(List.of(binding()));
        router.onMessage(message("true", "GOOD", null));
        // Первое значение (null -> "true") — законное изменение, скрипт на нём обязан
        // отработать. Проверяем то, что происходит ПОСЛЕ него.
        clearInvocations(onChangeDispatcher);

        router.onMessage(message(null, "BAD", null));

        // Ключевая гарантия: null в JS falsy, и setState(tag ? 'Открыт' : 'Закрыт')
        // нарисовал бы клапан ЗАКРЫТЫМ при потере связи. Скрипт не должен запускаться.
        verify(onChangeDispatcher, never()).submit(anyString(), any());
    }

    @Test
    @DisplayName("onChange срабатывает при изменении значения, а не на каждое сообщение")
    void onChangeFiresOnlyWhenValueChanges() {
        when(index.onChangeBindingsForTag(TAG)).thenReturn(List.of(binding()));

        router.onMessage(message("true", "GOOD", null));
        router.onMessage(message("true", "GOOD", null));
        router.onMessage(message("true", "GOOD", null));

        // Шлюз шлёт значение каждого тега каждый цикл опроса, меняется оно или нет.
        verify(onChangeDispatcher).submit(anyString(), any());
    }

    @Test
    @DisplayName("возврат связи с тем же значением не пересчитывает скрипт")
    void recoveryWithSameValueIsNotAChange() {
        when(index.onChangeBindingsForTag(TAG)).thenReturn(List.of(binding()));
        router.onMessage(message("true", "GOOD", null));   // 1-е и единственное срабатывание
        router.onMessage(message(null, "BAD", null));

        router.onMessage(message("true", "GOOD", null));

        // Пока значение было недостоверным, скрипты не работали, поэтому состояние
        // компонента всё это время отражало ровно это значение — пересчитывать нечего.
        verify(onChangeDispatcher).submit(anyString(), any());
    }

    private static OnChangeBinding binding() {
        return new OnChangeBinding(10L, 20L, "props.x = tag;");
    }

    /** Сообщение в текущем контракте шлюза: значение + качество + метка времени. */
    private static KafkaTagMessageEvent message(String value, String quality, String timestamp) {
        String json = "{\"value\":" + (value == null ? "null" : value)
                + ",\"quality\":\"" + quality + "\""
                + (timestamp == null ? "" : ",\"timestamp\":\"" + timestamp + "\"")
                + "}";
        return new KafkaTagMessageEvent(TAG, json);
    }

    private List<TagUpdate> drainTags() {
        return buffer.drainAll().tags();
    }
}
