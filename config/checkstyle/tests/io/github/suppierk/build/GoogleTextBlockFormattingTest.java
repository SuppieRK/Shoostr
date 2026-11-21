package io.github.suppierk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GoogleTextBlockFormattingTest {
  @Test
  void alignsReturnedTextBlockAndKeepsItsRelativeIndentation() throws Exception {
    var source = returnedTextBlock();

    var formatted = GoogleTextBlockFormatting.format(source);

    assertEquals(alignedTextBlock(), formatted);
    assertEquals(formatted, GoogleTextBlockFormatting.format(formatted));
  }

  @Test
  void keepsInlineClosingDelimiterFromAddingLineBreakToValue() throws Exception {
    var source =
        String.join("\n", "class Example {", "  String text = \"\"\"", "    value\"\"\";", "}");

    var formatted = GoogleTextBlockFormatting.format(source);

    assertEquals(inlineClosingTextBlock(), formatted);
  }

  private static String returnedTextBlock() {
    return String.join(
        "\n",
        "class Example {",
        "  String text() {",
        "    return \"\"\"",
        "    value",
        "      indented",
        "    \"\"\";",
        "  }",
        "}");
  }

  private static String alignedTextBlock() {
    return String.join(
        "\n",
        "class Example {",
        "  String text() {",
        "    return",
        "        \"\"\"",
        "        value",
        "          indented",
        "        \"\"\";",
        "  }",
        "}");
  }

  private static String inlineClosingTextBlock() {
    return String.join(
        "\n",
        "class Example {",
        "  String text =",
        "      \"\"\"",
        "      value\\",
        "      \"\"\";",
        "}");
  }
}
