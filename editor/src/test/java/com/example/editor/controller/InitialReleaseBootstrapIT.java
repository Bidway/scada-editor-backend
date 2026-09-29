package com.example.editor.controller;

import com.example.editor.model.ProjectRuntimeFlag;
import com.example.editor.repository.ProjectRuntimeFlagRepository;
import com.example.editor.service.release.InitialReleaseBootstrap;
import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Проект, крутившийся до выпусков, получает выпуск №1 сам — иначе runtime его не поднял бы. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InitialReleaseBootstrapIT extends EditorApiTestSupport {

    @Autowired
    ProjectRuntimeFlagRepository flags;
    @Autowired
    InitialReleaseBootstrap bootstrap;

    @Test
    void работающий_проект_без_prod_получает_начальный_выпуск() throws Exception {
        long projectId = createProject("Старый");
        ProjectRuntimeFlag flag = new ProjectRuntimeFlag();
        flag.setProjectId(projectId);
        flag.setInOperation(true);
        flags.save(flag);

        bootstrap.run();
        bootstrap.run();

        assertThat(flags.findById(projectId).orElseThrow().getProdVersionNo()).isEqualTo(1);
        assertThat(versionsOf(projectId, "projects")).hasSize(1);
    }
}
