package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorRecipeDto;
import com.example.runtime.client.dto.EditorRecipeStepActionDto;
import com.example.runtime.client.dto.EditorRecipeStepDto;
import com.example.runtime.client.dto.EditorRecipeTagDto;
import com.example.runtime.config.RuntimeProperties;
import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.persistence.ProcedureStateEntity;
import com.example.runtime.persistence.ProcedureStateRepository;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.TagSubscriptionIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Три риска, ради которых затевался перенос процедур с сессии на проект:
 * мойка обязана идти без открытых мониторов, переживать перезапуск сервиса и не сбиваться
 * повторным нажатием «Запустить».
 */
class ProcedureProjectScopeTest {

    private static final Long PROJECT = 8501L;
    private static final String RECIPE = "танк-сырого-молока-2-дезинфекция";
    private static final String TAG_PATH = "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST";

    private CommandProducer commandProducer;
    private ScriptEngineService scriptEngineService;
    private ProcedureExecutionService service;
    private ProcedureStateRepository states;
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        EditorClient editorClient = mock(EditorClient.class);
        when(editorClient.getRecipe(RECIPE)).thenReturn(recipe());

        commandProducer = mock(CommandProducer.class);
        when(commandProducer.send(anyString(), any())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(CommandOutcome.applied("ok"));
        });

        RuntimeProperties properties = new RuntimeProperties();
        properties.getScript().setContextPoolSize(1);
        properties.getScript().setOnChangeThreads(1);
        scriptEngineService = new ScriptEngineService(properties);
        scriptEngineService.initPool();

        TagSubscriptionIndex index = mock(TagSubscriptionIndex.class);
        when(index.resolveTagPath(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(index.getAllTagIds()).thenReturn(Set.of(TAG_PATH));
        when(index.getInitialPropertyValues()).thenReturn(Map.of());

        // Проект БЕЗ наблюдателей: именно так выглядит мойка после того, как оператор
        // закрыл монитор. Ни одной сессии в сторе тоже нет.
        ProjectRuntime project = new ProjectRuntime(PROJECT, index, null);
        ProjectRuntimeStore projectStore = mock(ProjectRuntimeStore.class);
        when(projectStore.get(PROJECT)).thenReturn(project);

        states = inMemoryStates();

        service = new ProcedureExecutionService(editorClient, commandProducer,
                mock(TagValueRouter.class), scriptEngineService, new RuntimeSessionStore(),
                projectStore, states);
    }

    @AfterEach
    void tearDown() {
        scriptEngineService.shutdown();
    }

    @Test
    void процедура_идёт_без_единого_наблюдателя() {
        service.start(PROJECT, RECIPE, null, "tester");

        // Условие первого шага тривиально истинно, поэтому процедура обязана уйти со шага 0
        // сама, без чьего-либо открытого экрана.
        assertThat(service.status(PROJECT, RECIPE).stepIndex()).isEqualTo(1);
    }

    @Test
    void повторный_старт_не_трогает_плк() {
        service.start(PROJECT, RECIPE, null, "tester");
        int writesAfterFirstStart = sent.size();

        assertThatThrownBy(() -> service.start(PROJECT, RECIPE, null, "tester"))
                .isInstanceOf(ProcedureAlreadyRunningException.class);

        // Шаг 0 у «Дезинфекции» — это «закрыть всё»: повторный старт погасил бы насос
        // под идущей мойкой. В ПЛК не должно уйти ни одной лишней команды.
        assertThat(sent).hasSize(writesAfterFirstStart);
    }

    @Test
    void восстановление_не_переприменяет_действия() {
        service.start(PROJECT, RECIPE, null, "tester");
        int writesAfterStart = sent.size();
        int stepBefore = service.status(PROJECT, RECIPE).stepIndex();

        service.forgetInMemory(PROJECT);   // имитация перезапуска сервиса
        service.restore(PROJECT);

        assertThat(service.status(PROJECT, RECIPE).stepIndex()).isEqualTo(stepBefore);
        // Мойка уже в этом состоянии: повторная запись дёрнула бы клапаны.
        assertThat(sent).hasSize(writesAfterStart);
    }

    @Test
    void автор_запуска_не_перезаписывается_подтверждением() {
        service.start(PROJECT, RECIPE, null, "tester");
        service.confirm(PROJECT, RECIPE, null, null, "second-operator");

        // После перезапуска runtime это единственный след того, кто запустил мойку.
        assertThat(states.findByProjectIdAndRecipeId(PROJECT, RECIPE))
                .get().extracting(ProcedureStateEntity::getStartedBy).isEqualTo("tester");
    }

    private static EditorRecipeDto recipe() {
        EditorRecipeTagDto tag = new EditorRecipeTagDto();
        tag.setName("V0");
        tag.setTag(TAG_PATH);

        EditorRecipeStepActionDto action = new EditorRecipeStepActionDto();
        action.setTag("V0");
        action.setValue(1);

        EditorRecipeStepDto first = new EditorRecipeStepDto();
        first.setName("0. Подготовка");
        first.setAction(List.of(action));
        first.setCondition_script("return true;");

        EditorRecipeStepDto second = new EditorRecipeStepDto();
        second.setName("1. Ожидание подтверждения");
        second.setAction(List.of());
        second.setCondition_script("return confirmed;");

        EditorRecipeDto recipe = new EditorRecipeDto();
        recipe.setId(RECIPE);
        recipe.setName("Танк сырого молока №2 — Дезинфекция");
        recipe.setTags(List.of(tag));
        recipe.setSteps(List.of(first, second));
        return recipe;
    }

    /**
     * Репозиторий в памяти: реализовывать весь JpaRepository ради двух методов незачем,
     * поэтому мок с картой внутри.
     */
    private static ProcedureStateRepository inMemoryStates() {
        Map<String, ProcedureStateEntity> rows = new HashMap<>();
        ProcedureStateRepository repository = mock(ProcedureStateRepository.class);
        when(repository.save(any(ProcedureStateEntity.class))).thenAnswer(invocation -> {
            ProcedureStateEntity entity = invocation.getArgument(0);
            rows.put(entity.getProjectId() + "|" + entity.getRecipeId(), entity);
            return entity;
        });
        when(repository.findByProjectIdAndRecipeId(any(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(rows.get(invocation.getArgument(0) + "|" + invocation.getArgument(1))));
        when(repository.findByProjectIdIn(any())).thenAnswer(invocation -> {
            java.util.Collection<?> ids = invocation.getArgument(0);
            return rows.values().stream().filter(row -> ids.contains(row.getProjectId())).toList();
        });
        return repository;
    }
}
