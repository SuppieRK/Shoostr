package io.github.suppierk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestSourcePolicyTest {
  private static final Pattern NON_CODE =
      Pattern.compile(
          "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/");
  private static final Pattern LEGACY_JUNIT_IMPORT =
      Pattern.compile(
          "(?m)^\\s*import\\s+(?:static\\s+)?(?:junit\\.framework\\.|org\\.junit\\.(?!(?:jupiter|platform)\\.))[A-Za-z0-9_.*]+\\s*;");
  private static final Pattern TEST_MAIN =
      Pattern.compile("^\\s*(?:(?:public|protected|private)\\s+)?static\\s+void\\s+main\\s*\\(");
  private static final Pattern DIRECT_ASSERTION_ERROR =
      Pattern.compile("\\bnew\\s+AssertionError\\s*\\(");
  private static final Pattern TRANSIENT_FILE =
      Pattern.compile("\\b(?:Files\\.createTemp(?:File|Directory)|File\\.createTempFile)\\s*\\(");
  private static final Pattern FILE_WRITE =
      Pattern.compile(
          "\\bFiles\\.(?:write|writeString|newOutputStream|newBufferedWriter|createFile|createDirectory|createDirectories|delete|deleteIfExists)\\s*\\(");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "import junit.framework.TestCase;",
        "import org.junit.Test;",
        "import org.junit.runner.RunWith;",
        "import org.junit.rules.TemporaryFolder;",
        "import static org.junit.Assert.assertTrue;"
      })
  void rejectsLegacyJunitImports(String source) {
    assertEquals(1, violations(source));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "import org.junit.jupiter.api.Test;",
        "import org.junit.platform.engine.TestEngine;",
        "import static org.junit.jupiter.api.Assertions.assertTrue;"
      })
  void permitsJupiterAndPlatformImports(String source) {
    assertEquals(0, violations(source));
  }

  @Test
  void rejectsMainBasedTestRunners() {
    assertEquals(1, violations("public static void main(String[] args) {}"));
  }

  @Test
  void permitsDocumentedSubprocessMainFixture() {
    assertEquals(
        0,
        violations(
            "// test-source-policy: subprocess-main\npublic static void main(String[] args) {}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "throw new " + "AssertionError(\"failed\");",
        "var failure = new " + "AssertionError(\"failed\");"
      })
  void rejectsDirectAssertionErrorConstruction(String source) {
    assertEquals(1, violations(source));
  }

  @Test
  void permitsPolicyKeywordsInStringAndCommentText() {
    assertEquals(
        0,
        violations(
            "String value = \"new AssertionError()\"; // new AssertionError()\n"
                + "/* new AssertionError() */"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Files.createTempFile(\"unmanaged\", \".txt\");",
        "Files.createTempDirectory(\"unmanaged\");",
        "Files.createTempFile(\n    \"unmanaged\", \".txt\");",
        "File.createTempFile(\"unmanaged\", \".txt\");"
      })
  void rejectsTransientFilesWithoutAManagedParent(String source) {
    assertEquals(1, violations(source));
  }

  @Test
  void permitsTransientFilesUnderAManagedParent() {
    assertEquals(0, violations("Files.createTempFile(temporaryDirectory, \"managed\", \".txt\");"));
    assertEquals(
        0, violations("File.createTempFile(\"managed\", \".txt\", temporaryDirectory.toFile());"));
  }

  @Test
  void rejectsFileTempCreationWithANullParent() {
    assertEquals(1, violations("File.createTempFile(\"unmanaged\", \".txt\", null);"));
  }

  @Test
  void permitsTempFileTextInCommentsAndStrings() {
    assertEquals(
        0,
        violations(
            "// Files.createTempFile(\"unmanaged\", \".txt\");\n"
                + "String value = \"Files.createTempDirectory(\\\"unmanaged\\\")\";"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Files.writeString(Path.of(\"src/test/resources/result.txt\"), \"result\");",
        "Files.write(Path.of(\"build/result.txt\"), new byte[0]);",
        "Files.newOutputStream(Path.of(\"/tmp/result.txt\"));"
      })
  void rejectsWritesToFixedRepositoryOrSharedPaths(String source) {
    assertEquals(1, violations(source));
  }

  @Test
  void repositoryTestSourcesFollowJupiterAndFixturePolicies() throws IOException {
    var failures = new ArrayList<String>();

    try (var projects = Files.list(Path.of("."))) {
      for (var project : projects.filter(Files::isDirectory).toList()) {
        var testRoot = project.resolve("src/test/java");
        if (Files.isDirectory(testRoot)) {
          scan(testRoot, failures);
        }
      }
    }

    scan(Path.of("config/checkstyle/tests"), failures);
    scan(Path.of("examples/consumer-smoke/src/test/java"), failures);
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  private static void scan(Path root, List<String> failures) throws IOException {
    try (var sources = Files.walk(root)) {
      for (var source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        int count = violations(Files.readString(source));
        if (count != 0) {
          failures.add(source + ": " + count + " test-source policy violations");
        }
      }
    }
  }

  private static int violations(String source) {
    int count = 0;
    var code = maskNonCode(source);
    var matches = LEGACY_JUNIT_IMPORT.matcher(code);
    while (matches.find()) {
      count++;
    }

    var lines = code.lines().toList();
    var originalLines = source.lines().toList();
    for (int index = 0; index < lines.size(); index++) {
      if (TEST_MAIN.matcher(lines.get(index)).find()
          && (index == 0
              || !originalLines
                  .get(index - 1)
                  .trim()
                  .startsWith("// test-source-policy: subprocess-main"))) {
        count++;
      }
    }

    matches = DIRECT_ASSERTION_ERROR.matcher(code);
    while (matches.find()) {
      count++;
    }

    matches = TRANSIENT_FILE.matcher(code);
    while (matches.find()) {
      int argument = firstArgument(source, matches.end());
      if (argument < source.length()
          && source.charAt(argument) == '"'
          && (!matches.group().startsWith("File.")
              || !hasExplicitTempParent(code, matches.end()))) {
        count++;
      }
    }

    matches = FILE_WRITE.matcher(code);
    while (matches.find()) {
      int argument = firstArgument(source, matches.end());
      if (source.startsWith("Path.of(\"", argument)
          || source.startsWith("Paths.get(\"", argument)) {
        count++;
      }
    }

    return count;
  }

  private static int firstArgument(String source, int start) {
    int argument = start;
    while (argument < source.length() && Character.isWhitespace(source.charAt(argument))) {
      argument++;
    }

    return argument;
  }

  private static boolean hasExplicitTempParent(String code, int start) {
    int depth = 0;
    int commas = 0;
    int parentStart = -1;
    for (int index = start; index < code.length(); index++) {
      char value = code.charAt(index);
      if (value == '(') {
        depth++;
      } else if (value == ')') {
        if (depth == 0) {
          if (parentStart < 0) {
            return false;
          }

          var parent = code.substring(parentStart, index).trim();
          return !parent.isEmpty() && !"null".equals(parent);
        }

        depth--;
      } else if (value == ',' && depth == 0) {
        commas++;
        if (commas == 2) {
          parentStart = index + 1;
        }
      }
    }

    return false;
  }

  private static String maskNonCode(String source) {
    char[] masked = source.toCharArray();
    var matches = NON_CODE.matcher(source);
    while (matches.find()) {
      for (int index = matches.start(); index < matches.end(); index++) {
        if (masked[index] != '\n' && masked[index] != '\r') {
          masked[index] = ' ';
        }
      }
    }

    return new String(masked);
  }
}
