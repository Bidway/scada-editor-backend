package com.example.runtime.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Проверка JWT-секрета на старте (#13f). Секрет в application.yml имеет fallback
 * (${JWT_SECRET:...}) — удобно для локального прогона/стенда, но в проде рабочий секрет
 * не должен быть встроенным значением из репозитория (кто знает секрет — подделает токен).
 * <p>
 * Поэтому: если используется встроенный секрет по умолчанию и активен профиль {@code prod} —
 * сервис не стартует (fail-fast), требуя задать реальный {@code JWT_SECRET}. Вне prod —
 * только предупреждение в лог, чтобы не ломать стенд.
 */
@Component
public class JwtSecretGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretGuard.class);

    /** Тот самый дефолт из application.yml — его наличие означает, что JWT_SECRET не задан. */
    private static final String INSECURE_DEFAULT =
            "f8d7e2c6a1b3f9e5d8c4a7b2e1f3c5d6a8b4e7f2c9d1a3b5e6f8c2d4e7f9a0b1";

    private final String secret;
    private final Environment environment;

    public JwtSecretGuard(@Value("${jwt.secret}") String secret, Environment environment) {
        this.secret = secret;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!INSECURE_DEFAULT.equals(secret)) {
            return;
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "JWT_SECRET не задан: встроенный секрет по умолчанию нельзя использовать с профилем 'prod'. " +
                            "Задайте реальный секрет через переменную окружения JWT_SECRET.");
        }
        log.warn("Используется встроенный JWT-секрет по умолчанию — допустимо для локального прогона/стенда, " +
                "но НЕ для прода. В проде задайте переменную окружения JWT_SECRET.");
    }
}
