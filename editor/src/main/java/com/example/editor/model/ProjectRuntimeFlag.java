package com.example.editor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Введён ли проект в эксплуатацию: по этому признаку runtime поднимает проект и держит
 * его живым независимо от открытых мониторов. Отдельная таблица, а не колонка в
 * component: проект — это компонент с type='project', и колонка висела бы на всех
 * пятнадцати тысячах компонентов сцены.
 */
@Entity
@Table(name = "project_runtime", schema = "editor")
@Getter
@Setter
public class ProjectRuntimeFlag {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "in_operation", nullable = false)
    private boolean inOperation;
}
