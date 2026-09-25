package io.github.suppierk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.DefaultConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatementSpacingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void separatesNestedStatementsAndPreservesAttachedClauses() throws Exception {
    String input =
        """
        class Example {
          void run(boolean a, boolean b) {
            if (a) {
              if (b) {
                work();
              }
              work();
            } else if (b) {
              work();
            } else {
              work();
            }
            // Keep this comment attached to the next statement.
            work();
            try (var resource = open()) {
              work();
            } catch (Exception failure) {
              work();
            } finally {
              work();
            }
          }
          void first() {
            try {
              work();
            } finally {
              work();
            }
          }
        }
        """;
    String formatted = StatementSpacingCheck.format(input);
    assertEquals(3, violations(input), "missing separators between statements must be rejected");
    assertEquals(0, violations(formatted), "formatted source must pass");
    assertEquals(
        formatted, StatementSpacingCheck.format(formatted), "formatting must be idempotent");
    assertTrue(
        formatted.contains("} else if")
            && formatted.contains("} catch")
            && formatted.contains("} finally"),
        "attached clauses must remain attached");
    assertTrue(
        formatted.contains("}\n\n    // Keep"), "blank line belongs before the following comment");
  }

  @Test
  void preservesTextBlocks() throws Exception {
    String literal =
        "class Literal {\n  String text = \"\"\"\ntry {\nif (true) {\n}\nnext\n\"\"\";\n}\n";
    assertEquals(
        literal, StatementSpacingCheck.format(literal), "text blocks must remain untouched");
    assertEquals(0, violations(literal), "text blocks are not statements");
  }

  @Test
  void doesNotSeparateLastIfFromClosingBrace() throws Exception {
    String last = "class Last {\n void run(boolean a) {\n  if (a) {\n   work();\n  }\n }\n}\n";
    assertEquals(
        last, StatementSpacingCheck.format(last), "no separator before enclosing closing brace");
  }

  @Test
  void doesNotInsertBlankLinesBeforeSynchronizedClosingBraces() throws Exception {
    String source =
        """
        class Example {
          void run(Object lock, boolean enabled) {
            synchronized (lock) {
              if (enabled) {
                work();
              }
            }
            synchronized (lock) {
              try {
                work();
              } finally {
                synchronized (lock) {
                  work();
                }
              }
            }
          }
        }
        """;
    assertEquals(0, violations(source));
    assertEquals(source, StatementSpacingCheck.format(source));
  }

  @Test
  void permitsTryAtStartOfAnyBlock() throws Exception {
    String firstTry =
        """
        class FirstTry {
          void run(boolean enabled) {
            // A comment does not count as a preceding statement.
            try {
              if (enabled) {
                try {
                  work();
                } finally {
                  work();
                }
              } else {
                try {
                  work();
                } finally {
                  work();
                }
              }
            } catch (Exception failure) {
              try {
                work();
              } finally {
                work();
              }
            } finally {
              try {
                work();
              } finally {
                work();
              }
            }
          }
          Runnable task = () -> {
            try {
              work();
            } finally {
              work();
            }
          };
        }
        """;
    assertEquals(0, violations(firstTry), "try may start any block without a separator");
    assertEquals(
        firstTry,
        StatementSpacingCheck.format(firstTry),
        "formatter must not insert a separator before the first statement");
  }

  @Test
  void separatesCompleteTryAndFollowingComments() throws Exception {
    String followedTry =
        """
        class FollowedTry {
          void run(boolean failed) {
            try {
              work();
            } catch (Exception failure) {
              work();
            } finally {
              work();
            }
            if (failed) {
              work();
            }

            try (var resource = open()) {
              work();
            }

            // Keep this comment attached to the next statement.
            work();

            try {
              work();
            } catch (Exception failure) {
              work();
            }
            try {
              work();
            } finally {
              work();
            }
          }
        }
        """;
    String separatedTry = StatementSpacingCheck.format(followedTry);
    assertEquals(
        3, violations(followedTry), "missing separators after complete try statements rejected");
    assertEquals(0, violations(separatedTry), "formatted try statements must pass");
    assertTrue(
        separatedTry.contains("}\n\n    if (failed)")
            && separatedTry.contains(
                "}\n\n    // Keep this comment attached to the next statement.\n    work();")
            && separatedTry.contains("}\n\n    try {")
            && !separatedTry.contains("\n\n\n"),
        "separate following statements and comments, without duplicating adjacent try separators");
    assertEquals(
        separatedTry, StatementSpacingCheck.format(separatedTry), "try spacing must be idempotent");
    String missingCommentSeparator = separatedTry.replace("}\n\n    // Keep", "}\n    // Keep");
    assertEquals(
        1,
        violations(missingCommentSeparator),
        "missing blank line before the comment must be rejected");
    assertEquals(
        separatedTry,
        StatementSpacingCheck.format(missingCommentSeparator),
        "formatter must insert a blank only before the comment, keeping its statement attached");
  }

  private int violations(String source) throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "statement-spacing-", ".java");
    var checker = new Checker();

    try {
      Files.writeString(file, source);
      var configuration = new DefaultConfiguration("Checker");
      var walker = new DefaultConfiguration("TreeWalker");
      walker.addChild(new DefaultConfiguration(StatementSpacingCheck.class.getName()));
      configuration.addChild(walker);
      checker.setModuleClassLoader(StatementSpacingTest.class.getClassLoader());
      checker.configure(configuration);
      return checker.process(List.of(file.toFile()));
    } finally {
      checker.destroy();
      Files.deleteIfExists(file);
    }
  }
}
