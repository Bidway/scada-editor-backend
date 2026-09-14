package com.example.scriptcore;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;

import java.util.Optional;

/**
 * Разбор скрипта задачи без исполнения. {@link Context#parse} строит AST и не выполняет ни одной
 * инструкции — скрипт, пишущий в ПЛК, при проверке ничего не запишет.
 * <p>
 * Один контекст на весь процесс: проверка редкая (сохранение набора), а создание контекста
 * GraalVM стоит сотни миллисекунд. Контекст не потокобезопасен — отсюда {@code synchronized}.
 * Песочница та же, что у исполнения: без доступа к Java.
 */
public final class ScriptSyntaxChecker implements AutoCloseable {

    private final Context context = Context.newBuilder("js")
            .allowAllAccess(false)
            .allowHostAccess(HostAccess.NONE)
            .option("engine.WarnInterpreterOnly", "false")
            .build();

    /** Пусто — синтаксис верный. Любая ошибка, кроме синтаксической, пробрасывается. */
    public synchronized Optional<SyntaxError> check(String taskScript) {
        try {
            context.parse(Source.create("js", TaskScriptSource.wrap(taskScript)));
            return Optional.empty();
        } catch (PolyglotException e) {
            if (!e.isSyntaxError()) {
                throw e;
            }
            SourceSection location = e.getSourceLocation();
            Integer line = location == null
                    ? null
                    : Math.max(1, location.getStartLine() - TaskScriptSource.PREFIX_LINES);
            return Optional.of(new SyntaxError(line, e.getMessage()));
        }
    }

    @Override
    public synchronized void close() {
        context.close();
    }

    /** @param line строка в тексте пользователя, с единицы; {@code null}, если движок место не сообщил */
    public record SyntaxError(Integer line, String message) {
    }
}
