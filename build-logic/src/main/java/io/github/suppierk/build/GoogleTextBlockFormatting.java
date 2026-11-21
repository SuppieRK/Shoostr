package io.github.suppierk.build;

import com.puppycrawl.tools.checkstyle.JavaParser;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.FileText;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Aligns text block delimiters after google-java-format has formatted the surrounding code. */
public final class GoogleTextBlockFormatting {
  /** Prevents construction of the formatter utility. */
  private GoogleTextBlockFormatting() {}

  /**
   * Moves delimiters to aligned lines while preserving each text block's value.
   *
   * @param source Java source formatted by google-java-format
   * @return source with aligned text block delimiters
   * @throws CheckstyleException if the source cannot be parsed
   */
  public static String format(String source) throws CheckstyleException {
    if (!source.contains("\"\"\"")) {
      return source;
    }

    String[] lines = source.split("\\n", -1);
    var tree =
        JavaParser.parseFileText(
            new FileText(new File("Source.java"), Arrays.asList(lines)),
            JavaParser.Options.WITHOUT_COMMENTS);

    var openings = new ArrayList<DetailAST>();
    collectOpenings(tree, openings);
    for (var opening : openings.reversed()) {
      align(opening, lines);
    }

    return String.join("\n", lines);
  }

  /**
   * Collects opening delimiters in source order.
   *
   * @param token first node of the subtree
   * @param openings collected delimiters
   */
  private static void collectOpenings(DetailAST token, List<DetailAST> openings) {
    for (var current = token; current != null; current = current.getNextSibling()) {
      if (current.getType() == TokenTypes.TEXT_BLOCK_LITERAL_BEGIN) {
        openings.add(current);
      }

      collectOpenings(current.getFirstChild(), openings);
    }
  }

  /**
   * Replaces the incidental indentation and inserts missing delimiter line breaks.
   *
   * @param opening opening delimiter
   * @param lines source lines
   */
  private static void align(DetailAST opening, String[] lines) {
    var closing = opening.getLastChild();
    int first = opening.getLineNo() - 1;
    int last = closing.getLineNo() - 1;
    String beforeOpening = lines[first].substring(0, opening.getColumnNo());
    String beforeClosing = lines[last].substring(0, closing.getColumnNo());
    boolean openingInline = !beforeOpening.isBlank();
    boolean closingInline = !beforeClosing.isBlank();
    int target = openingInline ? indentation(beforeOpening) + 4 : beforeOpening.length();
    int incidental = incidentalIndent(lines, first, last, beforeClosing);
    String margin = " ".repeat(target);

    String openingPrefix = openingInline ? beforeOpening.stripTrailing() + "\n" : "";
    lines[first] = openingPrefix + margin + lines[first].substring(opening.getColumnNo());

    for (int i = first + 1; i < last; i++) {
      String content = lines[i];
      lines[i] = content.isBlank() ? "" : margin + content.substring(incidental);
    }

    // Moving an inline closing delimiter adds a line break. Escape it to preserve the value.
    String closingPrefix =
        closingInline ? margin + beforeClosing.substring(incidental).stripTrailing() + "\\\n" : "";
    lines[last] = closingPrefix + margin + lines[last].substring(closing.getColumnNo());
  }

  /**
   * Measures the common indentation stripped by Java from the literal's nonblank lines.
   *
   * @param lines source lines
   * @param first opening delimiter line
   * @param last closing delimiter line
   * @param beforeClosing content or indentation preceding the closing delimiter
   * @return number of incidental indentation characters
   */
  private static int incidentalIndent(String[] lines, int first, int last, String beforeClosing) {
    int minimum = indentation(beforeClosing);
    for (int i = first + 1; i < last; i++) {
      if (!lines[i].isBlank()) {
        minimum = Math.min(minimum, indentation(lines[i]));
      }
    }

    return minimum;
  }

  /**
   * Counts leading whitespace in a source line.
   *
   * @param line source line or prefix
   * @return number of leading whitespace characters
   */
  private static int indentation(String line) {
    return line.length() - line.stripLeading().length();
  }
}
