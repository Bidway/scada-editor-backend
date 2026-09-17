package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorRecipeDto;
import com.example.runtime.client.dto.EditorRecipeStepActionDto;
import com.example.runtime.client.dto.EditorRecipeStepDto;
import com.example.runtime.client.dto.EditorRecipeTagDto;
import com.example.runtime.config.RuntimeProperties;
import com.example.runtime.dto.ProcedureStatusDto;
import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.TagSubscriptionIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcedureExecutionServiceTest {

    private static final String SESSION_ID = "s1";
    private static final Long PROJECT_ID = 1L;
    private static final String RECIPE_ID = "r1";

    private EditorClient editorClient;
    private CommandProducer commandProducer;
    private TagValueRouter tagValueRouter;
    private ScriptEngineService scriptEngineService;
    private RuntimeSessionStore sessionStore;
    private ProcedureExecutionService service;

    @BeforeEach
    void setUp() {
        editorClient = mock(EditorClient.class);
        commandProducer = mock(CommandProducer.class);
        tagValueRouter = mock(TagValueRouter.class);
        when(commandProducer.send(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandOutcome.applied("ok")));

        RuntimeProperties properties = new RuntimeProperties();
        properties.getScript().setContextPoolSize(1);
        properties.getScript().setOnChangeThreads(1);
        scriptEngineService = new ScriptEngineService(properties);
        scriptEngineService.initPool();

        sessionStore = new RuntimeSessionStore();
        TagSubscriptionIndex index = mock(TagSubscriptionIndex.class);
        when(index.resolveTagPath(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(index.getInitialPropertyValues()).thenReturn(java.util.Map.of());
        when(index.getAllTagIds()).thenReturn(java.util.Set.of());
        com.example.runtime.project.ProjectRuntime project =
                new com.example.runtime.project.ProjectRuntime(PROJECT_ID, index, null);
        RuntimeSession session = new RuntimeSession(SESSION_ID, project);
        sessionStore.put(session);
        project.addObserver(session);

        com.example.runtime.project.ProjectRuntimeStore projectStore =
                mock(com.example.runtime.project.ProjectRuntimeStore.class);
        when(projectStore.get(PROJECT_ID)).thenReturn(project);

        service = new ProcedureExecutionService(editorClient, commandProducer, tagValueRouter,
                scriptEngineService, sessionStore, projectStore,
                mock(com.example.runtime.persistence.ProcedureStateRepository.class));
    }

    @AfterEach
    void tearDown() {
        scriptEngineService.shutdown();
    }

    private EditorRecipeDto twoStepRecipe() {
        EditorRecipeTagDto tag = new EditorRecipeTagDto();
        tag.setName("V101_OPEN");
        tag.setTag("LINE1.V101.OPEN");

        EditorRecipeStepActionDto action = new EditorRecipeStepActionDto();
        action.setTag("V101_OPEN");
        action.setValue(true);

        EditorRecipeStepDto step1 = new EditorRecipeStepDto();
        step1.setName("Открыть клапан");
        step1.setAction(List.of(action));
        step1.setCondition_script(null);

        EditorRecipeStepDto step2 = new EditorRecipeStepDto();
        step2.setName("Ждать подтверждения");
        step2.setAction(List.of());
        step2.setCondition_script("return confirmed;");

        EditorRecipeDto recipe = new EditorRecipeDto();
        recipe.setId(RECIPE_ID);
        recipe.setName("Тест");
        recipe.setTags(List.of(tag));
        recipe.setSteps(List.of(step1, step2));
        return recipe;
    }

    @Test
    void start_appliesFirstStepAction_andAutoAdvancesThroughTrivialCondition() {
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(twoStepRecipe());

        ProcedureStatusDto status = service.start(PROJECT_ID, RECIPE_ID, SESSION_ID, "tester");

        verify(commandProducer).send("LINE1.V101.OPEN", true);
        assertThat(status.stepIndex()).isEqualTo(1);
        assertThat(status.stepName()).isEqualTo("Ждать подтверждения");
        assertThat(status.completed()).isFalse();
    }

    @Test
    void confirm_advancesPastConfirmStep_andCompletesOnLastStep() {
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(twoStepRecipe());
        service.start(PROJECT_ID, RECIPE_ID, SESSION_ID, "tester");

        ProcedureStatusDto status = service.confirm(PROJECT_ID, RECIPE_ID, null, SESSION_ID, "tester");

        assertThat(status.completed()).isTrue();
    }

    @Test
    void jump_reappliesStepActionAndRearmsCondition() {
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(twoStepRecipe());
        service.start(PROJECT_ID, RECIPE_ID, SESSION_ID, "tester");

        ProcedureStatusDto status = service.jump(PROJECT_ID, RECIPE_ID, 0, SESSION_ID, "tester");

        verify(commandProducer, org.mockito.Mockito.times(2)).send("LINE1.V101.OPEN", true);
        assertThat(status.stepIndex()).isEqualTo(0);
        assertThat(status.completed()).isFalse();
    }

    @Test
    void jump_appliesAccumulatedStateOfPreviousSteps_notOnlyTargetStepDelta() {
        EditorRecipeTagDto v1 = new EditorRecipeTagDto();
        v1.setName("V1");
        v1.setTag("LINE1.V1.ST");
        EditorRecipeTagDto v2 = new EditorRecipeTagDto();
        v2.setName("V2");
        v2.setTag("LINE1.V2.ST");

        // Шаги пишут только изменения: 0 — V1 открыт, 1 — V2 открыт, 2 — V1 закрыт.
        EditorRecipeDto recipe = new EditorRecipeDto();
        recipe.setId(RECIPE_ID);
        recipe.setName("Дельты");
        recipe.setTags(List.of(v1, v2));
        recipe.setSteps(List.of(
                step("0", action("V1", 1), action("V2", 0)),
                step("1", action("V2", 1)),
                step("2", action("V1", 0))));
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(recipe);

        service.jump(PROJECT_ID, RECIPE_ID, 2, SESSION_ID, "tester");

        // На шаге 2 V2 должен быть открыт, хотя сам шаг 2 его не упоминает.
        verify(commandProducer).send("LINE1.V2.ST", 1);
        verify(commandProducer).send("LINE1.V1.ST", 0);
        verify(commandProducer, org.mockito.Mockito.never()).send("LINE1.V1.ST", 1);
        verify(commandProducer, org.mockito.Mockito.never()).send("LINE1.V2.ST", 0);
    }

    private static EditorRecipeStepDto step(String name, EditorRecipeStepActionDto... actions) {
        EditorRecipeStepDto step = new EditorRecipeStepDto();
        step.setName(name);
        step.setAction(List.of(actions));
        step.setCondition_script("return confirmed;");
        return step;
    }

    private static EditorRecipeStepActionDto action(String tag, Object value) {
        EditorRecipeStepActionDto action = new EditorRecipeStepActionDto();
        action.setTag(tag);
        action.setValue(value);
        return action;
    }

    @Test
    void abort_removesExecution_soStatusThrowsAfterward() {
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(twoStepRecipe());
        service.start(PROJECT_ID, RECIPE_ID, SESSION_ID, "tester");

        service.abort(PROJECT_ID, RECIPE_ID, null, "operator");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.status(PROJECT_ID, RECIPE_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
