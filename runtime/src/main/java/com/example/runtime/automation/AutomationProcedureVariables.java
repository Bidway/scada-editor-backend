package com.example.runtime.automation;

import com.example.runtime.automation.engine.AutomationEngine;
import com.example.runtime.recipe.ProcedureVariables;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Переменные автоматизации для движка процедур — см. {@link ProcedureVariables}. */
@Component
@RequiredArgsConstructor
public class AutomationProcedureVariables implements ProcedureVariables {

    private final AutomationEngine engine;

    @Override
    public Object read(long projectId, String name) {
        return engine.readVariable(projectId, name);
    }

    @Override
    public boolean write(long projectId, String name, Object value) {
        return engine.writeVariable(projectId, name, value);
    }
}
