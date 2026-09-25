package io.github.suppierk.build;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.util.regex.Pattern;

/** Requires constant-first equality calls using the project's uppercase constant convention. */
public final class ConstantComparisonCheck extends AbstractCheck {
  private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

  /**
   * Subscribes to method calls when the rule uses its default configuration.
   *
   * @return default syntax token types
   */
  @Override
  public int[] getDefaultTokens() {
    return new int[] {TokenTypes.METHOD_CALL};
  }

  /**
   * Limits configured subscriptions to method calls.
   *
   * @return supported syntax token types
   */
  @Override
  public int[] getAcceptableTokens() {
    return getDefaultTokens();
  }

  /**
   * Keeps method calls checked even when explicit token configuration is supplied.
   *
   * @return mandatory syntax token types
   */
  @Override
  public int[] getRequiredTokens() {
    return getDefaultTokens();
  }

  /**
   * Reports equality calls whose argument is a constant but whose receiver is not recognized as
   * one.
   *
   * @param token syntax node supplied by Checkstyle
   */
  @Override
  public void visitToken(DetailAST token) {
    var method = token.getFirstChild();
    if (method.getType() != TokenTypes.DOT) {
      return;
    }

    String name = method.getLastChild().getText();
    if (!"equals".equals(name) && !"equalsIgnoreCase".equals(name)) {
      return;
    }

    var arguments = token.findFirstToken(TokenTypes.ELIST);
    var argument = arguments.getFirstChild();
    if (argument != null
        && argument.getNextSibling() == null
        && isConstant(argument)
        && !isConstant(method.getFirstChild())) {
      log(token, "constant.comparison");
    }
  }

  /**
   * Recognizes literals, uppercase constant names, and supported zero-argument accessors on
   * constants. This is a syntax convention check; it does not resolve Java types or prove arbitrary
   * expressions non-null.
   *
   * @param expression candidate expression or expression wrapper
   * @return true if the expression follows the supported constant forms
   */
  private static boolean isConstant(DetailAST expression) {
    if (expression.getType() == TokenTypes.EXPR) {
      var child = expression.getFirstChild();
      while (child != null && child.getType() == TokenTypes.LPAREN) {
        child = child.getNextSibling();
      }
      return child != null && isConstant(child);
    }

    if (expression.getType() == TokenTypes.STRING_LITERAL) {
      return true;
    }

    if (expression.getType() == TokenTypes.IDENT) {
      return CONSTANT_NAME.matcher(expression.getText()).matches();
    }

    if (expression.getType() == TokenTypes.DOT) {
      return isConstant(expression.getLastChild());
    }

    if (expression.getType() == TokenTypes.METHOD_CALL) {
      var method = expression.getFirstChild();
      if (method.getType() != TokenTypes.DOT
          || expression.findFirstToken(TokenTypes.ELIST).getFirstChild() != null) {
        return false;
      }

      String name = method.getLastChild().getText();
      return ("value".equals(name) || "name".equals(name) || "toString".equals(name))
          && isConstant(method.getFirstChild());
    }

    return false;
  }
}
