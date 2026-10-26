package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NullAwayFixtureTest {
  @Test
  void rejectsUnsafeNullInANullMarkedPackage(@TempDir Path project) throws IOException {
    var sourceDirectory = configureProject(project);
    Files.writeString(
        sourceDirectory.resolve("UnsafeNull.java"),
        """
        package io.github.suppierk.fixture;

        import io.github.suppierk.shoostr.http.HttpHeaders;
        import io.github.suppierk.shoostr.http.HttpMethods;
        import io.github.suppierk.shoostr.Response;

        final class UnsafeNull {
          static void reject(Response response) {
            response.header("X-Response").trim();
          }

          static void acceptNullableInputs() {
            HttpHeaders.httpHeader(null);
            HttpMethods.httpMethod(null);
          }
        }
        """);

    var result =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments("--console=plain", "compileJava")
            .buildAndFail();

    assertTrue(
        result.getOutput().contains("[NullAway] dereferenced expression 'response.header"),
        result.getOutput());
    assertFalse(result.getOutput().contains("[NullAway] passing @Nullable"), result.getOutput());
  }

  @Test
  void allowsDeliberateNullInputsInTestSources(@TempDir Path project) throws IOException {
    configureProject(project);
    var tests = project.resolve("src/test/java/io/github/suppierk/fixture");
    Files.createDirectories(tests);
    Files.writeString(
        tests.resolve("NullInput.java"),
        """
        package io.github.suppierk.fixture;

        import io.github.suppierk.shoostr.Options;

        final class NullInput {
          static Options invalidInput() {
            return new Options(64, 128, 32, 4, null);
          }
        }
        """);

    var result =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments("--console=plain", "compileTestJava")
            .build();
    assertFalse(result.getOutput().contains("[NullAway]"), result.getOutput());
  }

  @Test
  void retainsOtherErrorProneChecksForTestSources(@TempDir Path project) throws IOException {
    configureProject(project);
    var tests = project.resolve("src/test/java/io/github/suppierk/fixture");
    Files.createDirectories(tests);
    Files.writeString(
        tests.resolve("InvalidEquality.java"),
        """
        package io.github.suppierk.fixture;

        final class InvalidEquality {
          static boolean invalidEquality() {
            return new Object().equals(null);
          }
        }
        """);

    var result =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments("--console=plain", "compileTestJava")
            .buildAndFail();
    assertTrue(result.getOutput().contains("[EqualsNull]"), result.getOutput());
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "test"})
  void rejectsUnnecessaryQualifiedExpressionsInEverySourceSet(
      String sourceSet, @TempDir Path project) throws IOException {
    configureProject(project);
    var sources = project.resolve("src/" + sourceSet + "/java/io/github/suppierk/fixture");
    Files.createDirectories(sources);
    Files.writeString(
        sources.resolve("QualifiedName.java"),
        """
        package io.github.suppierk.fixture;

        import java.time.Duration;
        import java.util.function.UnaryOperator;

        final class QualifiedName {
          static String value(String input) {
            return java.util.Objects.requireNonNull(input);
          }

          static Duration delay() {
            return java.time.Duration.ZERO;
          }

          static UnaryOperator<String> operation() {
            return java.util.Objects::requireNonNull;
          }
        }
        """);

    var result =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments(
                "--console=plain", "main".equals(sourceSet) ? "compileJava" : "compileTestJava")
            .buildAndFail();

    assertTrue(result.getOutput().contains("[UnnecessarilyFullyQualified]"), result.getOutput());
    assertTrue(result.getOutput().contains("QualifiedName.java:"), result.getOutput());
    assertTrue(result.getOutput().contains("return java.time.Duration.ZERO;"), result.getOutput());
    assertTrue(
        result.getOutput().contains("return java.util.Objects::requireNonNull;"),
        result.getOutput());
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "test"})
  void permitsImportsAndNecessaryNameDisambiguationInEverySourceSet(
      String sourceSet, @TempDir Path project) throws IOException {
    configureProject(project);
    var sources = project.resolve("src/" + sourceSet + "/java/io/github/suppierk/fixture");
    Files.createDirectories(sources);
    Files.writeString(
        sources.resolve("ImportedName.java"),
        """
        package io.github.suppierk.fixture;

        import java.util.Objects;

        final class ImportedName {
          static String value(String input) {
            return Objects.requireNonNull(input);
          }
        }
        """);
    Files.writeString(
        sources.resolve("Objects.java"),
        """
        package io.github.suppierk.fixture;

        final class Objects {
          static String value(String input) {
            return java.util.Objects.requireNonNull(input);
          }
        }
        """);

    var result =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments(
                "--console=plain", "main".equals(sourceSet) ? "compileJava" : "compileTestJava")
            .build();

    assertFalse(result.getOutput().contains("[UnnecessarilyFullyQualified]"), result.getOutput());
  }

  private static Path configureProject(Path project) throws IOException {
    Files.writeString(
        project.resolve("settings.gradle"), "rootProject.name = 'nullaway-fixture'\n");
    var root = Path.of("..").toAbsolutePath().normalize();
    var nullAwayScript = root.resolve("config/gradle/nullaway.gradle");
    var errorProneScript = root.resolve("config/gradle/errorprone.gradle");
    var coreClasses = root.resolve("core/build/classes/java/main");
    var httpClasses = root.resolve("http/build/classes/java/main");
    Files.writeString(
        project.resolve("build.gradle"),
        """
        plugins {
          id 'java'
          id 'net.ltgt.errorprone' version '5.1.1' apply false
          id 'net.ltgt.nullaway' version '3.3.0' apply false
        }
        repositories { mavenCentral() }
        dependencies {
          implementation files('%s', '%s')
          testCompileOnly 'org.jspecify:jspecify:1.0.1'
        }
        apply from: '%s'
        apply from: '%s'
        """
            .formatted(
                groovyPath(coreClasses),
                groovyPath(httpClasses),
                groovyPath(errorProneScript),
                groovyPath(nullAwayScript)));
    var sourceDirectory = project.resolve("src/main/java/io/github/suppierk/fixture");
    Files.createDirectories(sourceDirectory);
    Files.writeString(
        sourceDirectory.resolve("package-info.java"),
        "@NullMarked\npackage io.github.suppierk.fixture;\n\nimport org.jspecify.annotations.NullMarked;\n");
    return sourceDirectory;
  }

  private static String groovyPath(Path path) {
    return path.toString().replace('\\', '/').replace("'", "\\'");
  }
}
