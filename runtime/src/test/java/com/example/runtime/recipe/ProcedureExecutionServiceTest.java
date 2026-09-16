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
        RuntimeSession session = new RuntimeSession(SESSION_ID,
                new com.example.runtime.project.ProjectRuntime(1L, index, null));
        sessionStore.put(session);

        service = new ProcedureExecutionService(editorClient, commandProducer, tagValueRouter,
                scriptEngineService, sessionStore);
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

        ProcedureStatusDto status = service.start(SESSION_ID, RECIPE_ID);

        verify(commandProducer).send("LINE1.V101.OPEN", true);
        assertThat(status.stepIndex()).isEqualTo(1);
        assertThat(status.stepName()).isEqualTo("Ждать подтверждения");
        assertThat(status.completed()).isFalse();
    }

    @Test
    void confirm_advancesPastConfirmStep_andCompletesOnLastStep() {
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(twoStepRecipe());
        service.start(SESSION_ID, RECIPE_ID);

        ProcedureStatusDto status = service.confirm(SESSION_ID, RECIPE_ID);

        assertThat(status.completed()).isTrue();
    }

    @Test
    void jump_reappliesStepActionAndRearmsCondition() {
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(twoStepRecipe());
        service.start(SESSION_ID, RECIPE_ID);

        ProcedureStatusDto status = service.jump(SESSION_ID, RECIPE_ID, 0);

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

        service.jump(SESSION_ID, RECIPE_ID, 2);

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
    void resumeGuess_scansFromEnd_skippingStepsWithoutObservableCondition() {
        EditorRecipeStepDto delayStep = new EditorRecipeStepDto();
        delayStep.setName("Пауза");
        delayStep.setAction(List.of());
        delayStep.setCondition_script("return elapsedMs >= 2000;");

        EditorRecipeStepDto tagStep = new EditorRecipeStepDto();
        tagStep.setName("Набор объёма");
        tagStep.setAction(List.of());
        tagStep.setCondition_script("return readProjectTag('LINE1.LEVEL') >= 300;");

        EditorRecipeStepDto confirmStep = new EditorRecipeStepDto();
        confirmStep.setName("Подтверждение");
        confirmStep.setAction(List.of());
        confirmStep.setCondition_script("return confirmed;");

        EditorRecipeDto recipe = new EditorRecipeDto();
        recipe.setId(RECIPE_ID);
        recipe.setName("Тест");
        recipe.setTags(List.of());
        recipe.setSteps(List.of(delayStep, tagStep, confirmStep));
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(recipe);
        when(tagValueRouter.lastValue("LINE1.LEVEL")).thenReturn("300");

        int guess = service.resumeGuess(SESSION_ID, RECIPE_ID);

        assertThat(guess).isEqualTo(2);
    }

    @Test
    void abort_removesExecution_soStatusThrowsAfterward() {
        when(editorClient.getRecipe(RECIPE_ID)).thenReturn(twoStepRecipe());
        service.start(SESSION_ID, RECIPE_ID);

        service.abort(SESSION_ID, RECIPE_ID);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.status(SESSION_ID, RECIPE_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
