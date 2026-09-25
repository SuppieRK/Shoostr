package io.github.suppierk.build;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import com.puppycrawl.tools.checkstyle.utils.JavadocUtil;

/** Rejects actual Javadoc comments while leaving Java strings and text blocks untouched. */
public final class NoJavadocCheck extends AbstractCheck {
  /**
   * Subscribes to block comments, including Javadoc comments.
   *
   * @return block-comment token
   */
  @Override
  public int[] getDefaultTokens() {
    return new int[] {TokenTypes.BLOCK_COMMENT_BEGIN};
  }

  /**
   * Limits configured subscriptions to block comments.
   *
   * @return block-comment token
   */
  @Override
  public int[] getAcceptableTokens() {
    return getDefaultTokens();
  }

  /**
   * Keeps the block-comment subscription mandatory.
   *
   * @return block-comment token
   */
  @Override
  public int[] getRequiredTokens() {
    return getDefaultTokens();
  }

  /**
   * Requires comment nodes in the syntax tree.
   *
   * @return true so Checkstyle includes comments
   */
  @Override
  public boolean isCommentNodesRequired() {
    return true;
  }

  /**
   * Reports Javadoc comments found in test sources.
   *
   * @param token block comment
   */
  @Override
  public void visitToken(DetailAST token) {
    var content = JavadocUtil.getBlockCommentContent(token);
    if (content.isEmpty() || JavadocUtil.isJavadocComment(content)) {
      log(token.getLineNo(), token.getColumnNo(), "test.javadoc");
    }
  }
}
