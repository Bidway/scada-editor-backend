package com.example.runtime.project;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.scriptcore.ProjectData;
import lombok.Getter;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Поднятый проект: живёт, пока стоит флаг «в эксплуатации», и не зависит от того, открыт ли
 * у кого-то монитор. Держит индекс тегов, данные проекта, общее состояние свойств и
 * наблюдателей.
 * <p>
 * {@code propertyValues} общая на проект, а не на сессию: её пишут серверные скрипты
 * ({@code on_change} по смене тега и {@code ACTION} по действию оператора), а это состояние
 * объекта, а не экрана. Пока карта была сессионной, два оператора на одном проекте видели
 * разные значения и их копии никогда не сходились обратно.
 */
@Getter
public class ProjectRuntime {

    private final Long projectId;
    /**
     * Дерево и индекс текущего выпуска. Заменяемые целиком (ProjectRuntimeService.reload): сессии
     * читают через getIndex(), поэтому видят новый выпуск без переподключения. Кто читает индекс
     * несколько раз за одно действие, берёт getModel() один раз — иначе на стыке смешает выпуски.
     */
    private volatile ProjectModel model;
    /**
     * Снимок таблиц данных проекта. Заменяемый: правку таблиц скрипты кнопок и шаги процедур
     * обязаны увидеть без снятия флага «в эксплуатации» — иначе кнопка, подгружающая готовый
     * вариант уставок, писала бы в ПЛК старые значения (scada-zd1w). Сам снимок неизменяемый,
     * поэтому достаточно {@code volatile}: читатель видит либо старый, либо новый целиком.
     */
    private volatile ProjectData projectData;
    private final Map<Long, Object> propertyValues;

    private final Map<String, RuntimeSession> observers = new ConcurrentHashMap<>();

    /** Для тестов, которым дерево не нужно. */
    public ProjectRuntime(Long projectId, TagSubscriptionIndex index, ProjectData projectData) {
        this(projectId, new ProjectModel(0, null, null, index), projectData);
    }

    public ProjectRuntime(Long projectId, EditorComponentDto tree, TagSubscriptionIndex index,
                          ProjectData projectData) {
        this(projectId, new ProjectModel(0, null, tree, index), projectData);
    }

    public ProjectRuntime(Long projectId, ProjectModel model, ProjectData projectData) {
        this.projectId = projectId;
        this.model = model;
        this.projectData = projectData;
        this.propertyValues = new ConcurrentHashMap<>(model.index().getInitialPropertyValues());
    }

    /** Дерево текущего выпуска — отдаётся открывающему монитор вместе с индексом из того же выпуска. */
    public EditorComponentDto getTree() {
        return model.tree();
    }

    public TagSubscriptionIndex getIndex() {
        return model.index();
    }

    /** Только ProjectRuntimeService.reload: подмена после загрузки нового выпуска. */
    void replaceModel(ProjectModel next) {
        this.model = next;
    }

    /** Получатель изменений значений свойств — для сохранения в базу (scada-vrkf). */
    @FunctionalInterface
    public interface PropertyValueSink {
        void changed(Long propertyId, Object value);
    }

    /** По умолчанию изменения никуда не уходят: так проект собирают тесты. */
    private volatile PropertyValueSink propertyValueSink = (propertyId, value) -> {
    };

    public void setPropertyValueSink(PropertyValueSink sink) {
        this.propertyValueSink = sink;
    }

    /**
     * Единственная точка записи значения свойства скриптом ({@code on_change}, кнопка): карта в
     * памяти плюс отметка для сохранения. {@code null} — свойство сброшено: {@code ConcurrentHashMap}
     * не хранит null, отсутствие ключа и значит «не задано».
     */
    public boolean putPropertyValue(Long propertyId, Object value) {
        // Скрипт, начатый на прошлом выпуске, мог закончиться после reload: свойства в текущем
        // выпуске уже нет — значение не пишем ни в память, ни в базу, и мониторам его не шлют.
        if (model.index().propertyName(propertyId) == null) {
            return false;
        }
        if (value == null) {
            propertyValues.remove(propertyId);
        } else {
            propertyValues.put(propertyId, value);
        }
        propertyValueSink.changed(propertyId, value);
        return true;
    }
    public void replaceProjectData(ProjectData projectData) {
        this.projectData = projectData;
    }

    public void addObserver(RuntimeSession session) {
        observers.put(session.getId(), session);
    }

    public void removeObserver(String sessionId) {
        observers.remove(sessionId);
    }

    /** Наблюдатели: те, кому есть куда слать кадры. Их может не быть вовсе — проект работает. */
    public Collection<RuntimeSession> sessions() {
        return observers.values();
    }
}
