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
        RuntimeSession session = new RuntimeSession(SESSION_ID, 1L, index);
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
