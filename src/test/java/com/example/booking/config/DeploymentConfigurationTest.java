package com.example.booking.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.booking.seed.DevDataSeeder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the deployment configuration against the mistakes that are easy to make and easy to miss: a development
 * default leaking into shared configuration, a secret committed as a default, and the application inside
 * Docker being pointed at a host-mapped port instead of the service name.
 */
class DeploymentConfigurationTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    private static Map<String, Object> yaml(String resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        EnumerablePropertySource<?> source = (EnumerablePropertySource<?>) sources.get(0);
        return Arrays.stream(source.getPropertyNames())
                .collect(Collectors.toMap(name -> name, source::getProperty));
    }

    private static String file(String name) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void secretsAndConnectionSettingsInSharedConfigurationAreRequiredEnvironmentVariablesWithNoDefault() throws IOException {
        Map<String, Object> main = yaml("application.yml");

        assertThat(main).containsEntry("app.jwt.secret", "${JWT_SECRET}")
                .containsEntry("spring.datasource.url", "${DB_URL}")
                .containsEntry("spring.datasource.username", "${DB_USERNAME}")
                .containsEntry("spring.datasource.password", "${DB_PASSWORD}")
                .containsEntry("spring.data.redis.host", "${REDIS_HOST}");
    }

    @Test
    void sharedConfigurationContainsNoLocalhostAssumptionsAndNoDemoAccounts() throws IOException {
        Map<String, Object> main = yaml("application.yml");

        assertThat(main.values().stream().map(String::valueOf)).noneMatch(value -> value.contains("localhost"));
        assertThat(main.keySet()).noneMatch(key -> key.startsWith("app.seed"));
    }

    @Test
    void theDevelopmentProfileDoesNotProvideASecretOrAPasswordDefault() throws IOException {
        Map<String, Object> dev = yaml("application-dev.yml");

        assertThat(dev).doesNotContainKeys("app.jwt.secret", "spring.datasource.password", "spring.data.redis.password");
        assertThat(dev.keySet()).anyMatch(key -> key.startsWith("app.seed"));
    }

    @Test
    void demoDataIsOnlyEverCreatedUnderTheDevProfile() {
        Profile profile = DevDataSeeder.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev");
    }

    @Test
    void theEnvironmentTemplateContainsPlaceholdersOnly() throws IOException {
        List<String> secretLines = file(".env.example").lines()
                .filter(line -> line.startsWith("POSTGRES_PASSWORD=") || line.startsWith("JWT_SECRET="))
                .toList();

        assertThat(secretLines).hasSize(2).allSatisfy(line -> assertThat(line).contains("replace-with"));
    }

    @Test
    void theApplicationContainerTalksToServicesByNameAndNeverThroughHostMappedPorts() throws IOException {
        String compose = file("docker-compose.yml");
        String app = compose.substring(compose.indexOf("\n  app:"));

        assertThat(app).contains("DB_URL: jdbc:postgresql://db:5432/")
                .contains("REDIS_HOST: redis")
                .contains("REDIS_PORT: 6379");
        assertThat(app).doesNotContain("localhost", "127.0.0.1:15432", "16379");
        assertThat(app).doesNotContain("DB_URL: jdbc:postgresql://db:${DB_PORT");
    }

    @Test
    void theApplicationWaitsForHealthyDatabaseAndRedis() throws IOException {
        String compose = file("docker-compose.yml");
        String app = compose.substring(compose.indexOf("\n  app:"));

        assertThat(app).contains("condition: service_healthy");
        assertThat(app.split("condition: service_healthy", -1).length - 1).isEqualTo(2);
    }

    @Test
    void theImageRunsAsANonRootUserOnJava21AndHasAHealthCheck() throws IOException {
        String dockerfile = file("Dockerfile");

        assertThat(dockerfile).contains("eclipse-temurin:21-jre").contains("USER app").contains("HEALTHCHECK");
        assertThat(dockerfile.indexOf("USER app")).isLessThan(dockerfile.indexOf("ENTRYPOINT"));
    }
}
