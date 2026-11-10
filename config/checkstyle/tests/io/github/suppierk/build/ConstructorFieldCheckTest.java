package io.github.suppierk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ConstructorFieldCheckTest {
  @TempDir Path directory;

  @Test
  void reportsTheFieldTargetLocationInsteadOfTheInitializer() throws Exception {
    var diagnostics = check("class Example {\n  int count;\n  Example() { count = 1; }\n}");
    assertEquals(1, diagnostics.size());
    var error = diagnostics.getFirst();
    assertEquals(Diagnostic.Kind.ERROR, error.getKind());
    assertEquals(3, error.getLineNumber());
    assertEquals(15, error.getColumnNumber());
    assertTrue(error.getMessage(Locale.ROOT).contains("explicit this receiver: count"));
    assertEquals("Example.java", Path.of(error.getSource().toUri()).getFileName().toString());
  }

  @ParameterizedTest
  @MethodSource("unqualifiedFields")
  void rejectsEverySourceWrittenConstructorFieldWrite(String source) throws Exception {
    var diagnostics = check(source);
    assertEquals(1, diagnostics.size());
    assertEquals(Diagnostic.Kind.ERROR, diagnostics.getFirst().getKind());
    assertTrue(diagnostics.getFirst().getMessage(Locale.ROOT).contains("explicit this receiver"));
  }

  @ParameterizedTest
  @MethodSource("unrelatedWrites")
  void preservesParametersLocalsStaticFieldsAndExistingReceivers(String source) throws Exception {
    assertEquals(List.of(), check(source));
  }

  @Test
  void resolvesInheritedFieldsFromACompiledDependency() throws Exception {
    var dependency = directory.resolve("Base.java");
    var classes = directory.resolve("classes");
    Files.createDirectories(classes);
    Files.writeString(
        dependency,
        "package dependency; public class Base { protected int inherited; protected static int shared; }");
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-proc:none",
                "-d",
                classes.toString(),
                dependency.toString()));
    var source =
        write(
            """
        class Example extends dependency.Base {
          Example() {
            inherited = 1;
            shared = 2;
          }
        }
        """);
    var diagnostics = ConstructorFieldCheck.check(List.of(source), classes.toString());
    assertEquals(1, diagnostics.size());
    assertEquals(3, diagnostics.getFirst().getLineNumber());
    assertTrue(diagnostics.getFirst().getMessage(Locale.ROOT).contains("receiver: inherited"));
  }

  @Test
  void reportsEveryTargetOfAChainedAssignment() throws Exception {
    var diagnostics =
        check("class Example { int first, second; Example() { first = second = 1; } }");
    assertEquals(2, diagnostics.size());
    assertTrue(diagnostics.get(0).getMessage(Locale.ROOT).contains("receiver: first"));
    assertTrue(diagnostics.get(1).getMessage(Locale.ROOT).contains("receiver: second"));
  }

  @Test
  void returnsAttributionErrorsInsteadOfSilentlyAcceptingUnresolvedCode() throws Exception {
    var diagnostics = check("class Example { int count; Example() { count = unknown; } }");
    assertTrue(diagnostics.stream().anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR));
    assertTrue(
        diagnostics.stream().anyMatch(item -> item.getMessage(Locale.ROOT).contains("unknown")));
    assertFalse(
        diagnostics.stream()
            .anyMatch(item -> item.getMessage(Locale.ROOT).contains("explicit this receiver")));
  }

  @Test
  void rejectsSyntaxErrorsEvenWithoutAnAssignmentTarget() throws Exception {
    assertTrue(
        check("class Example { Example( }").stream()
            .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR));
  }

  @Test
  void commandLineWritesAReportAndFailsForViolations() throws Exception {
    var source = write("class Example { int count; Example() { count = 1; } }");
    var sources = directory.resolve("source files.txt");
    var classpath = directory.resolve("classpath.txt");
    var report = directory.resolve("reports/result.txt");
    Files.writeString(sources, source.toString(), StandardCharsets.UTF_8);
    Files.writeString(classpath, "", StandardCharsets.UTF_8);
    var arguments = new String[] {sources.toString(), classpath.toString(), report.toString()};
    assertThrows(IllegalStateException.class, () -> ConstructorFieldCheck.main(arguments));
    assertTrue(Files.readString(report).contains("receiver: count"));
    Files.writeString(source, "class Example { int count; Example() { this.count = 1; } }");
    ConstructorFieldCheck.main(arguments);
    assertTrue(Files.readString(report).contains("passed for 1 source files"));
  }

  @Test
  void acceptsAnEmptySourceSet() throws Exception {
    assertEquals(List.of(), ConstructorFieldCheck.check(List.of(), ""));
  }

  @Test
  void attributionDoesNotGenerateClassFiles() throws Exception {
    assertEquals(
        List.of(),
        check(
            "class Example { int count; Example(int count) { count += 1; this.count = count; } }"));
    assertFalse(Files.exists(directory.resolve("Example.class")));
  }

  private static Stream<String> unqualifiedFields() {
    return Stream.of(
        "class Example { Object value; Example() { value = new Object(); } }",
        "class Example { Object value; Example() { value = null; } }",
        "class Example { int count; Example(int source) { count = source; } }",
        "class Example { boolean ready; Example() { ready = false; } }",
        "class Example { int count; Example() { count += 1; } }",
        "class Example { int count; Example() { count++; } }",
        "class Example { int count; Example() { --count; } }",
        "class Example { int count; Example() { (count) = 1; } }",
        "class Example { int count; Example() { count =\n 1; } }",
        "class Example { int count; Example() { Runnable update = () -> count++; } }",
        "class Example { int count; Example() { { int count = 0; count++; } count = 1; } }",
        "enum Example { VALUE; int count; Example() { count = 1; } }",
        "class Example { int count; class Inner { Inner() { count = 1; } } }",
        "class Example { void run() { new Object() { int count; void create() { class Local { Local() { count = 1; } } new Local(); } }; } }",
        "class Example { int μέτρο; Example() { μέτρο = 1; } }",
        "class Example { void run() { class Local { int count; Local() { count = 1; } } } }",
        "record Example(int count) { Example { count = Math.max(count, 0); class Local { int value; Local() { value = 1; } } new Local(); } }",
        "@SuppressWarnings(\"all\") class Example { int count; Example() { count = 1; } }");
  }

  private static Stream<String> unrelatedWrites() {
    return Stream.of(
        "class Example { int count; Example(int count) { count += 1; this.count = count; } }",
        "class Example { int count; Example() { int count; count = 1; } }",
        "class Example { int count; Example() { for (int count = 0; count < 1; count++) {} } }",
        "class Example { int count; Example() { for (int count : new int[]{1}) { count = 2; } } }",
        "class Example { RuntimeException failure; Example() { try { throw new RuntimeException(); } catch (RuntimeException failure) { failure = new RuntimeException(); } } }",
        "class Example { String value; Example(Object input) { if (input instanceof String value) { value = value.strip(); } } }",
        "class Example { String value; Example() { java.util.function.Function<String, String> trim = value -> { value = value.strip(); return value; }; } }",
        "class Example { int count; Example() { Runnable update = () -> { int count; count = 1; }; } }",
        "class Example { static int count; Example() { count = 1; } }",
        "class Example { int count; Example(Example other) { other.count = 1; this.count = 2; } }",
        "class Example { int[] values; Example() { this.values = new int[1]; values[0] = 1; } }",
        "class Base { int count; } class Example extends Base { Example() { super.count = 1; } }",
        "class Example { int count; class Inner { Inner() { Example.this.count = 1; } } }",
        "class Example { int count; Example() { new Runnable() { public void run() { count = 1; } }; } }",
        "class Example { int count; Example() { new Object() { int local = (count = 1); }; } }",
        "class Example { int count; void Example() { count = 1; } }",
        "record Example(int count) { Example { count = Math.max(count, 0); } }",
        "record Example(int count) { Example(int count) { this.count = count; } }",
        "record Example(int count) {}",
        "class Example { String text; Example() { this.text = \"count = new Value()\"; /* count = 1; */ } }");
  }

  private List<Diagnostic<? extends JavaFileObject>> check(String source) throws Exception {
    return ConstructorFieldCheck.check(List.of(write(source)), "");
  }

  private Path write(String source) throws Exception {
    var file = directory.resolve("Example.java");
    Files.writeString(file, source, StandardCharsets.UTF_8);
    return file;
  }
}
