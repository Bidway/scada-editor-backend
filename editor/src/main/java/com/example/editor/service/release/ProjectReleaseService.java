package com.example.editor.service.release;

import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.release.ProjectReleaseDto;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.example.editor.service.ComponentService;
import com.example.editor.service.version.DocumentVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выпуск проекта — снимок живого дерева версией документа PROJECT. Prod не меняет: выпустить и
 * переключить — разные действия (ProjectRuntimeController).
 */
@Service
@RequiredArgsConstructor
public class ProjectReleaseService {

    private final DocumentVersionService versions;
    private final DocumentVersionRepository repository;
    private final ComponentService componentService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ProjectReleaseDto release(Long projectId, String comment, String userName) {
        ComponentResponseDto project = componentService.getById(projectId);
        if (!ComponentTypes.PROJECT.equals(project.getType())) {
            throw new IllegalArgumentException("Компонент " + projectId + " не проект, а " + project.getType());
        }
        Integer before = versions.currentVersionNo(DocumentType.PROJECT, projectId);
        DocumentVersion version = versions.record(DocumentType.PROJECT, projectId,
                objectMapper.valueToTree(project), userName, VersionKind.MANUAL, null);
        boolean unchanged = version.getVersionNo().equals(before);
        if (!unchanged) {
            version.setComment(comment);
            repository.save(version);
        }
        return new ProjectReleaseDto(version.getVersionNo(), version.getCreatedAt(), version.getComment(), unchanged);
    }
}
