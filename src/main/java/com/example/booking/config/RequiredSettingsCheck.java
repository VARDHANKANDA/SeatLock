package com.example.booking.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Stops startup with a readable message when a required environment variable is not provided. */
@Component
class RequiredSettingsCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Map<String, String> REQUIRED_SETTINGS = new LinkedHashMap<>();

    static {
        REQUIRED_SETTINGS.put("spring.datasource.url", "DB_URL");
        REQUIRED_SETTINGS.put("spring.datasource.username", "DB_USERNAME");
        REQUIRED_SETTINGS.put("spring.datasource.password", "DB_PASSWORD");
        REQUIRED_SETTINGS.put("spring.data.redis.host", "REDIS_HOST");
        REQUIRED_SETTINGS.put("app.jwt.secret", "JWT_SECRET");
    }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> missing = new ArrayList<>();
        REQUIRED_SETTINGS.forEach((property, variable) -> {
            if (!isResolvable(property)) {
                missing.add(variable);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required configuration. Set the environment variables: " + String.join(", ", missing));
        }
    }

    private boolean isResolvable(String property) {
        try {
            String value = environment.getProperty(property);
            return value != null && !value.isBlank();
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return false;
        }
    }
}
