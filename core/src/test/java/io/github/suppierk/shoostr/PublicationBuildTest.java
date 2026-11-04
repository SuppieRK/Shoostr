package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(300)
class PublicationBuildTest {
  @TempDir Path directory;

  @Test
  void retainsOldBinaryDependenciesAndRejectsWorkspaceOnlyApisUntilDeliberatelyUpgraded()
      throws Exception {
    Files.writeString(directory.resolve("settings.gradle"), "include 'http', 'core', 'mcp'\n");
    Files.copy(
        Path.of("../config/gradle/publication.gradle"), directory.resolve("publication.gradle"));
    Files.writeString(
        directory.resolve("build.gradle"),
        """
        ext.publicationLibraries = ['http', 'core', 'mcp']
        allprojects { group = 'io.github.suppierk' }
        apply from: 'publication.gradle'
        subprojects {
            apply plugin: 'java-library'
            apply plugin: 'maven-publish'
            java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
            publishing {
                publications { mavenJava(MavenPublication) {
                    from components.java
                    artifactId = "shoostr-${project.name}"
                } }
                repositories { maven {
                    name = 'releaseVerification'
                    url = rootProject.layout.buildDirectory.dir('release-verification-repository')
                } }
            }
        }
        """);
    writeModule("http", "version = '2026.11.3.0'\n");
    writeModule(
        "core",
        """
        version = '2026.11.3.0'
        dependencies { api shoostrDependency('http', '2026.11.3.0') }
        """);
    writeModule(
        "mcp",
        """
        version = '2026.11.4.1'
        dependencies { api shoostrDependency('core', '2026.11.3.0') }
        tasks.register('probe', JavaExec) {
            classpath = sourceSets.main.runtimeClasspath
            mainClass = 'io.github.suppierk.fixture.Consumer'
        }
        """);
    writeSource(
        "core",
        "Api",
        "public final class Api { public static String legacy() { return \"OLD_BINARY\"; } }");
    run(
        ":http:publishMavenJavaPublicationToReleaseVerificationRepository",
        ":core:publishMavenJavaPublicationToReleaseVerificationRepository");

    var coreBuild = directory.resolve("core/build.gradle");
    Files.writeString(
        coreBuild,
        Files.readString(coreBuild).replace("version = '2026.11.3.0'", "version = '2026.11.4.0'"));
    writeSource(
        "core",
        "Api",
        """
        public final class Api {
          public static String legacy() { return "NEW_WORKSPACE"; }
          public static void newApi() {}
        }
        """);
    writeSource(
        "mcp", "Consumer", "public final class Consumer { void invoke() { Api.newApi(); } }");
    run(":mcp:compileJava");
    var rejected =
        runner("-PpublicationBinaries=true", "-PpublicationModules=mcp", ":mcp:compileJava")
            .buildAndFail()
            .getOutput();
    assertTrue(rejected.contains("cannot find symbol"), rejected);

    writeSource(
        "mcp",
        "Consumer",
        """
        public final class Consumer {
          public static void main(String[] args) { System.out.println(Api.legacy()); }
        }
        """);
    var retained = run("-PpublicationBinaries=true", "-PpublicationModules=mcp", ":mcp:probe");
    assertTrue(retained.contains("OLD_BINARY"), retained);
    assertFalse(retained.contains("NEW_WORKSPACE"), retained);

    var mcpBuild = directory.resolve("mcp/build.gradle");
    Files.writeString(
        mcpBuild, Files.readString(mcpBuild).replace("'2026.11.3.0'", "'2026.11.4.0'"));
    run(
        "-PpublicationBinaries=true",
        "-PpublicationModules=core,mcp",
        ":core:publishMavenJavaPublicationToReleaseVerificationRepository",
        ":mcp:publishMavenJavaPublicationToReleaseVerificationRepository");
    var upgraded = run("-PpublicationBinaries=true", "-PpublicationModules=mcp", ":mcp:probe");
    assertTrue(upgraded.contains("NEW_WORKSPACE"), upgraded);
    var publication = directory.resolve("build/release-verification-repository/io/github/suppierk");
    var pom =
        Files.readString(
            publication.resolve("shoostr-mcp/2026.11.4.1/shoostr-mcp-2026.11.4.1.pom"));
    var metadata =
        Files.readString(
            publication.resolve("shoostr-mcp/2026.11.4.1/shoostr-mcp-2026.11.4.1.module"));
    assertTrue(pom.contains("<version>2026.11.4.0</version>"), pom);
    assertTrue(metadata.contains("\"requires\": \"2026.11.4.0\""), metadata);
  }

  private void writeModule(String name, String build) throws Exception {
    Files.createDirectories(directory.resolve(name));
    Files.writeString(directory.resolve(name + "/build.gradle"), build);
  }

  private void writeSource(String module, String name, String body) throws Exception {
    var source =
        directory.resolve(module + "/src/main/java/io/github/suppierk/fixture/" + name + ".java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package io.github.suppierk.fixture;\n" + body + "\n");
  }

  private String run(String... arguments) {
    return runner(arguments).build().getOutput();
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create()
        .withProjectDir(directory.toFile())
        .withArguments(arguments)
        .withDebug(true);
  }
}
