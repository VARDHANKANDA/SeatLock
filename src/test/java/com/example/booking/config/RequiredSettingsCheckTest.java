package com.example.booking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class RequiredSettingsCheckTest {

    /** Required property to the environment variable that supplies it. */
    private static final Map<String, String> VARIABLES = Map.of(
            "spring.datasource.url", "DB_URL",
            "spring.datasource.username", "DB_USERNAME",
            "spring.datasource.password", "DB_PASSWORD",
            "spring.data.redis.host", "REDIS_HOST",
            "app.jwt.secret", "JWT_SECRET");

    private static Map<String, String> completeProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://db:5432/booking");
        properties.put("spring.datasource.username", "booking");
        properties.put("spring.datasource.password", "from-environment");
        properties.put("spring.data.redis.host", "redis");
        properties.put("app.jwt.secret", "from-environment-from-environment-1234");
        return properties;
    }

    private static MockEnvironment environment(Map<String, String> properties) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        return environment;
    }

    private static void check(MockEnvironment environment) {
        RequiredSettingsCheck check = new RequiredSettingsCheck();
        check.setEnvironment(environment);
        check.postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @Test
    void startsWhenEveryRequiredSettingIsPresent() {
        assertThatCode(() -> check(environment(completeProperties()))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.data.redis.host",
            "app.jwt.secret"})
    void failsClearlyNamingOnlyTheMissingEnvironmentVariable(String missingProperty) {
        Map<String, String> properties = completeProperties();
        properties.remove(missingProperty);

        assertThatThrownBy(() -> check(environment(properties)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing required configuration")
                .hasMessageContaining(VARIABLES.get(missingProperty))
                .satisfies(e -> VARIABLES.forEach((property, variable) -> {
                    if (!property.equals(missingProperty)) {
                        assertThat(e.getMessage()).doesNotContain(variable);
                    }
                }));
    }

    @Test
    void aBlankValueCountsAsMissing() {
        Map<String, String> properties = completeProperties();
        properties.put("app.jwt.secret", "   ");

        assertThatThrownBy(() -> check(environment(properties))).hasMessageContaining("JWT_SECRET");
    }

    @Test
    void anUnresolvedPlaceholderCountsAsMissingInsteadOfBeingUsedLiterally() {
        Map<String, String> properties = completeProperties();
        properties.put("spring.datasource.url", "${DB_URL}");

        assertThatThrownBy(() -> check(environment(properties))).hasMessageContaining("DB_URL");
    }

    @Test
    void everyMissingVariableIsReportedAtOnce() {
        assertThatThrownBy(() -> check(new MockEnvironment()))
                .hasMessageContaining("DB_URL").hasMessageContaining("DB_USERNAME").hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("REDIS_HOST").hasMessageContaining("JWT_SECRET");
    }
}
