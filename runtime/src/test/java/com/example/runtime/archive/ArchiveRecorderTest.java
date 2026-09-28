package com.example.runtime.archive;

import com.example.scriptcore.TelemetryEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Архив пишет только изменения: повтор того же значения каждый цикл опроса в очередь не попадает. */
class ArchiveRecorderTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Minsk");
    private static final String V1 = "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST";

    private static long at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static TelemetryEnvelope good(String v) {
        return new TelemetryEnvelope(v, true, null);
    }

    private static TelemetryEnvelope bad() {
        return new TelemetryEnvelope(null, false, null);
    }

    private ArchiveRecorder recorder(int capacity) {
        ArchiveProperties props = new ArchiveProperties();
        props.setQueueCapacity(capacity);
        return new ArchiveRecorder(props, new ObjectMapper(), ZONE);
    }

    @Test
    void повтор_того_же_значения_не_пишется_смена_значения_и_качества_пишутся() {
        ArchiveRecorder r = recorder(100);
        r.record(V1, good("1"), at("2026-09-28T10:00:00"));
        r.record(V1, good("1"), at("2026-09-28T10:00:01"));
        r.record(V1, good("1.0"), at("2026-09-28T10:00:02"));   // то же число другим написанием
        r.record(V1, good("0"), at("2026-09-28T10:00:03"));
        r.record(V1, bad(), at("2026-09-28T10:00:04"));
        r.record(V1, bad(), at("2026-09-28T10:00:05"));
        r.record(V1, good("0"), at("2026-09-28T10:00:06"));      // связь вернулась — качество сменилось

        List<ArchivePoint> points = r.queue().drain(100);
        assertThat(points).extracting(ArchivePoint::num).containsExactly(1.0, 0.0, null, 0.0);
        assertThat(points).extracting(ArchivePoint::good).containsExactly(true, true, false, true);
    }

    @Test
    void опорная_точка_в_новых_сутках_даже_без_изменения() {
        ArchiveRecorder r = recorder(100);
        r.record(V1, good("1"), at("2026-09-28T23:59:59"));
        r.record(V1, good("1"), at("2026-09-29T00:00:01"));
        r.record(V1, good("1"), at("2026-09-29T00:00:02"));

        assertThat(r.queue().drain(100)).hasSize(2);
    }

    @Test
    void bool_и_строки_раскладываются_по_колонкам() {
        ArchiveRecorder r = recorder(100);
        r.record("a", good("true"), at("2026-09-28T10:00:00"));
        r.record("b", good("Мойка"), at("2026-09-28T10:00:00"));

        List<ArchivePoint> points = r.queue().drain(100);
        assertThat(points.get(0).num()).isEqualTo(1.0);
        assertThat(points.get(0).text()).isNull();
        assertThat(points.get(1).num()).isNull();
        assertThat(points.get(1).text()).isEqualTo("Мойка");
    }

    // Ревью, Important-2: STRING-тег ПЛК часто дополнен нулями, а Postgres не принимает 0x00 в text.
    @Test
    void нулевые_байты_строкового_тега_вычищаются() {
        ArchiveRecorder r = recorder(100);
        r.record("s", good("Мойка\u0000\u0000"), at("2026-09-28T10:00:00"));

        assertThat(r.queue().drain(10).get(0).text()).isEqualTo("Мойка");
    }

    // Review Focus 1: курсор воспроизведения идёт по (ts, tag) — у тега не может быть двух точек с одним ts.
    @Test
    void две_точки_тега_в_одну_миллисекунду_получают_разные_ts() {
        ArchiveRecorder r = recorder(100);
        long t = at("2026-09-28T10:00:00");
        r.record(V1, good("1"), t);
        r.record(V1, bad(), t);
        r.record(V1, good("0"), t);

        assertThat(r.queue().drain(100)).extracting(ArchivePoint::ts).containsExactly(t, t + 1, t + 2);
    }

    // Review Focus 2: сброшенная из-за переполнения точка не должна «залипнуть» как записанная.
    @Test
    void после_сброса_из_за_переполнения_то_же_значение_пишется_снова() {
        ArchiveRecorder r = recorder(1);
        r.record("a", good("5"), at("2026-09-28T10:00:00"));
        r.record(V1, good("1"), at("2026-09-28T10:00:01"));     // очередь полна — сброшено
        assertThat(r.queue().dropped()).isEqualTo(1);

        r.queue().drain(10);
        r.record(V1, good("1"), at("2026-09-28T10:00:02"));

        assertThat(r.queue().drain(10)).extracting(ArchivePoint::tag).containsExactly(V1);
    }
}
