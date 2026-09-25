package io.github.suppierk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.DefaultConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ConstantComparisonTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @MethodSource("unsafeComparisons")
  void rejectsVariableFirstComparison(String expression) throws Exception {
    assertEquals(1, violations(expression), expression);
  }

  @ParameterizedTest
  @MethodSource("safeComparisons")
  void permitsConstantFirstAndUnrelatedComparisons(String expression) throws Exception {
    assertEquals(0, violations(expression), expression);
  }

  private static Stream<String> unsafeComparisons() {
    return Stream.of(
        "name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH.value())",
        "name.equals(HttpHeaders.CONTENT_LENGTH.value())",
        "state.equals(State.OPEN)",
        "state.equals(OPEN)",
        "name.equalsIgnoreCase(CONTENT_LENGTH.value())",
        "name.equals(State.OPEN.name())",
        "name.equals(State.OPEN.toString())",
        "name.equalsIgnoreCase(io.github.suppierk.shoostr.http.HttpHeaders.CONTENT_LENGTH.value())",
        "name.equalsIgnoreCase((HttpHeaders.CONTENT_LENGTH.value()))",
        "(name).equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH.value())",
        "name\n.equalsIgnoreCase(\nHttpHeaders.CONTENT_LENGTH.value())",
        "name.equals(\"literal\")");
  }

  private static Stream<String> safeComparisons() {
    return Stream.of(
        "HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)",
        "HttpHeaders.CONTENT_LENGTH.value().equalsIgnoreCase(name)",
        "State.OPEN.equals(state)",
        "OPEN.equals(state)",
        "state == State.OPEN",
        "State.OPEN.name().equals(name)",
        "\"literal\".equals(name)",
        "\"literal\".equals(HttpHeaders.CONTENT_LENGTH.value())",
        "HttpHeaders.CONTENT_LENGTH.value().equals(\"Content-Length\")",
        "left.equals(right)",
        "name.equals(buildValue(State.OPEN))",
        "name.equals(other.value())",
        "name.equalsIgnoreCase(HEADER_BY_NAME.get(name))",
        "java.util.Objects.equals(name, State.OPEN.name())",
        "State.OPEN.equals(State.CLOSED)");
  }

  private int violations(String expression) throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "constant-comparison-", ".java");
    var checker = new Checker();

    try {
      Files.writeString(
          file,
          "import static example.State.OPEN;\n"
              + "class Example { boolean check() { return "
              + expression
              + "; } }");
      var configuration = new DefaultConfiguration("Checker");
      var walker = new DefaultConfiguration("TreeWalker");
      walker.addChild(new DefaultConfiguration(ConstantComparisonCheck.class.getName()));
      configuration.addChild(walker);
      checker.setModuleClassLoader(ConstantComparisonTest.class.getClassLoader());
      checker.configure(configuration);
      return checker.process(List.of(file.toFile()));
    } finally {
      checker.destroy();
      Files.deleteIfExists(file);
    }
  }
}
