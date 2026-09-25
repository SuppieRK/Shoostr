package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NullAwayFixtureTest {
  @Test
  void rejectsUnsafeNullInANullMarkedPackage(@TempDir Path project) throws IOException {
    Files.writeString(
        project.resolve("settings.gradle"), "rootProject.name = 'nullaway-fixture'\n");
    var root = Path.of("..").toAbsolutePath().normalize();
    var nullAwayScript = root.resolve("config/gradle/nullaway.gradle");
    var coreClasses = root.resolve("core/build/classes/java/main");
    var httpClasses = root.resolve("http/build/classes/java/main");
    Files.writeString(
        project.resolve("build.gradle"),
        """
        plugins {
          id 'java'
          id 'net.ltgt.errorprone' version '5.1.0' apply false
          id 'net.ltgt.nullaway' version '3.2.0' apply false
        }
        repositories { mavenCentral() }
        dependencies { implementation files('%s', '%s') }
        apply from: '%s'
        """
            .formatted(
                groovyPath(coreClasses), groovyPath(httpClasses), groovyPath(nullAwayScript)));
    var sourceDirectory = project.resolve("src/main/java/io/github/suppierk/fixture");
    Files.createDirectories(sourceDirectory);
    Files.writeString(
        sourceDirectory.resolve("package-info.java"),
        "@org.jspecify.annotations.NullMarked\npackage io.github.suppierk.fixture;\n");
    Files.writeString(
        sourceDirectory.resolve("UnsafeNull.java"),
        """
        package io.github.suppierk.fixture;

        import io.github.suppierk.shoostr.http.HttpHeaders;
        import io.github.suppierk.shoostr.http.HttpMethods;
        import io.github.suppierk.shoostr.Request;

        final class UnsafeNull {
          static void reject(Request request) {
            request.header("X-Request").trim();
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
        result.getOutput().contains("[NullAway] dereferenced expression 'request.header"),
        result.getOutput());
    assertFalse(result.getOutput().contains("[NullAway] passing @Nullable"), result.getOutput());
  }

  private static String groovyPath(Path path) {
    return path.toString().replace('\\', '/').replace("'", "\\'");
  }
}
