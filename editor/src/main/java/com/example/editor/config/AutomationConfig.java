package com.example.editor.config;

import com.example.scriptcore.ScriptSyntaxChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AutomationConfig {

    /** Один контекст GraalVM на процесс; закрывается вместе с контекстом Spring. */
    @Bean(destroyMethod = "close")
    public ScriptSyntaxChecker scriptSyntaxChecker() {
        return new ScriptSyntaxChecker();
    }
}
