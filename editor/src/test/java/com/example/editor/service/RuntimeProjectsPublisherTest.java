package com.example.editor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeProjectsPublisherTest {

    @Test
    void значение_несёт_номер_prod_а_выключенный_проект_это_tombstone() {
        assertThat(RuntimeProjectsPublisher.valueOf(5L, true, 3))
                .isEqualTo("{\"projectId\":5,\"inOperation\":true,\"prodVersionNo\":3}");
        assertThat(RuntimeProjectsPublisher.valueOf(5L, false, 3)).isNull();
    }
}
