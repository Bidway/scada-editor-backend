package com.example.runtime.automation.engine;

import com.example.scriptcore.ProjectData;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProjectDataHolderTest {

    /** editor поднялся после запуска проекта — данные подхватываются сами, без перезапуска. */
    @Test
    void retriesUntilEditorAnswers() throws Exception {
        ScheduledExecutorService loader = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger calls = new AtomicInteger();
        ProjectDataHolder holder = new ProjectDataHolder(7L, projectId -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("editor down");
            }
            return ProjectData.EMPTY;
        }, loader, 10, 40);
        try {
            holder.start();
            long deadline = System.currentTimeMillis() + 2000;
            while (holder.current() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            assertSame(ProjectData.EMPTY, holder.current());
            assertEquals(3, calls.get());
        } finally {
            holder.stop();
            loader.shutdownNow();
        }
    }
}
