package com.example.editor.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Введён ли проект в эксплуатацию: по этому признаку runtime поднимает проект и держит
 * его живым независимо от открытых мониторов. Отдельная таблица, а не колонка в
 * component: проект — это компонент с type='project', и колонка висела бы на всех
 * пятнадцати тысячах компонентов сцены.
 * <p>
 * Здесь же лежит {@link #image} — настройки рабочего места проекта (закреплённые схемы), общие
 * для всех пользователей. Не в {@code states} проекта: дерево компонентов целиком уходит в
 * выпуск, а закрепление вкладки не должно ни попадать в выпуск, ни создавать версий.
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

    /** Номер выпуска (версии PROJECT), который крутит runtime; null — выпуска ещё нет. */
    @Column(name = "prod_version_no")
    private Integer prodVersionNo;

    /** JSON-объект фронта, бэк его не разбирает; null — ещё не записывали. */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode image;
}
