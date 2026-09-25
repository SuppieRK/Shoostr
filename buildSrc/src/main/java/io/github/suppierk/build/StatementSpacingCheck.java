package io.github.suppierk.build;

import com.puppycrawl.tools.checkstyle.JavaParser;
import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.FileText;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;

/** Enforces statement spacing using the same syntax-aware boundaries as the formatter. */
public final class StatementSpacingCheck extends AbstractCheck {
  /**
   * Subscribes to try and if statements when the rule uses its default configuration.
   *
   * @return default syntax token types
   */
  @Override
  public int[] getDefaultTokens() {
    return new int[] {TokenTypes.LITERAL_TRY, TokenTypes.LITERAL_IF};
  }

  /**
   * Limits configured subscriptions to try and if statements.
   *
   * @return supported syntax token types
   */
  @Override
  public int[] getAcceptableTokens() {
    return getDefaultTokens();
  }

  /**
   * Keeps try and if statements checked even when explicit token configuration is supplied.
   *
   * @return mandatory syntax token types
   */
  @Override
  public int[] getRequiredTokens() {
    return getDefaultTokens();
  }

  /**
   * Reports missing separators at complete statement boundaries, excluding attached clauses and
   * closing braces.
   *
   * @param token syntax node supplied by Checkstyle
   */
  @Override
  public void visitToken(DetailAST token) {
    int before = beforeBoundary(token);
    if (before >= 0 && missingLine(token, before, getLines(), true)) {
      log(token, "statement.spacing", "before try");
    }

    int after = afterBoundary(token);
    if (after >= 0 && missingLine(token, after, getLines(), false)) {
      log(
          token,
          "statement.spacing",
          token.getType() == TokenTypes.LITERAL_TRY ? "after try/catch/finally" : "after if/else");
    }
  }

  /**
   * Adds required blank lines after standard Java formatting, without touching strings or comments.
   *
   * @param source formatted Java source
   * @return source with statement separators
   * @throws CheckstyleException if the Java source cannot be parsed
   */
  public static String format(String source) throws CheckstyleException {
    String[] lines = source.split("\\R", -1);
    var tree =
        JavaParser.parseFileText(
            new FileText(new File("Source.java"), Arrays.asList(lines)),
            JavaParser.Options.WITHOUT_COMMENTS);
    var insertions = new TreeSet<Integer>();
    collect(tree, lines, insertions);
    var result = new ArrayList<>(Arrays.asList(lines));
    for (int line : insertions.descendingSet()) {
      result.add(line, "");
    }
    return String.join("\n", result);
  }

  /**
   * Walks sibling and child nodes, collecting unique insertion positions in original source
   * coordinates. The caller inserts in reverse order so earlier positions remain valid.
   *
   * @param token first sibling to inspect, or null
   * @param lines formatted source lines
   * @param insertions zero-based positions requiring a blank line
   */
  private static void collect(DetailAST token, String[] lines, TreeSet<Integer> insertions) {
    for (var current = token; current != null; current = current.getNextSibling()) {
      int before = beforeBoundary(current);
      if (before >= 0 && missingLine(current, before, lines, true)) {
        insertions.add(before);
      }

      int after = afterBoundary(current);
      if (after >= 0 && missingLine(current, after, lines, false)) {
        insertions.add(after);
      }

      collect(current.getFirstChild(), lines, insertions);
    }
  }

  /**
   * Locates a separator before a try statement unless it is the first statement in its block.
   *
   * @param token candidate statement
   * @return zero-based insertion position, or -1 if no separator is required
   */
  private static int beforeBoundary(DetailAST token) {
    if (token.getType() == TokenTypes.LITERAL_TRY && token.getPreviousSibling() != null) {
      return token.getLineNo() - 1;
    }

    return -1;
  }

  /**
   * Locates the end of a complete if/else or try/catch/finally followed by another statement.
   * Attached else clauses and the enclosing closing brace never require a separator.
   *
   * @param token candidate statement
   * @return zero-based insertion position after the final clause, or -1 if none is required
   */
  private static int afterBoundary(DetailAST token) {
    if ((token.getType() == TokenTypes.LITERAL_TRY
            || token.getType() == TokenTypes.LITERAL_IF
                && token.getParent().getType() != TokenTypes.LITERAL_ELSE)
        && token.getNextSibling() != null
        && token.getNextSibling().getType() != TokenTypes.RCURLY) {
      var end = token;
      while (end.getLastChild() != null) {
        end = end.getLastChild();
      }
      return end.getLineNo();
    }

    return -1;
  }

  /**
   * Checks a syntax boundary against source lines, including statements sharing one physical line.
   * An after-boundary separator precedes any following comment, keeping that comment with its
   * statement.
   *
   * @param token statement whose boundary is checked
   * @param boundary zero-based insertion position
   * @param lines formatted source lines
   * @param before true for a before-try boundary; false for an after-statement boundary
   * @return true if the required separator is absent
   */
  private static boolean missingLine(
      DetailAST token, int boundary, String[] lines, boolean before) {
    if (before) {
      return boundary == 0
          || !lines[boundary - 1].isBlank()
          || !lines[boundary].substring(0, token.getColumnNo()).isBlank();
    }

    return token.getNextSibling().getLineNo() <= boundary
        || boundary < lines.length && !lines[boundary].isBlank();
  }
}
