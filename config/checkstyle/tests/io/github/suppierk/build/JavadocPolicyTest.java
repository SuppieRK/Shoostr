package io.github.suppierk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.DefaultConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class JavadocPolicyTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "class Example {}",
        "public class Example {}",
        "interface Example {}",
        "enum Example { VALUE }",
        "record Example(int value) {}",
        "/** Documented. */ class Example {\n private class Nested {} }",
        "/** Documented. */ class Example { protected void run() {} }",
        "/** Documented. */ class Example { public void run() {} }",
        "/** Documented. */ class Example { void run() {} }",
        "/** Documented. */ class Example { private void run() {} }",
        "/** Documented. */ class Example { private Example() {} }",
        "/** Documented. */ record Example(int value) { Example {} }",
        "/** Documented. */ class Example { int value; public int getValue() { return value; } }",
        "/** Documented. */ class Example { int value; public void setValue(int value) { this.value = value; } }",
        "/** Documented. */ class Example { @Override public String toString() { return \"x\"; } }",
        "/** Documented. */ class Example { /** Runs. */ void run() {\n class Local {} } }",
        "/** Documented. */ class Example { /** Runs. */ void run() { /** Local. */ class Local { private void work() {} } } }",
        "/** Documented. */ class Example { Runnable task = new Runnable() { @Override public void run() {} }; }",
        "/** Documented. */ enum Example { VALUE { @Override public String toString() { return \"x\"; } } }"
      })
  void rejectsMissingDocumentation(String declaration) throws Exception {
    assertEquals(1, violations(declaration), declaration);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/** Documented. */ class Example { /** Runs. */ private void run(int count) {} }",
        "/** Documented. */ class Example { /** Reads. */ private int read() { return 1; } }",
        "/** Documented. */ class Example { /** Runs. */ private void run() throws Exception {} }",
        "/** Documented. */ class Example { /** Runs. */ private void run() { throw new IllegalStateException(); } }",
        "/** Documented. */ class Example { /** Creates. */ private Example(int count) {} }"
      })
  void validatesPrivateMethodTags(String declaration) throws Exception {
    assertEquals(1, violations(declaration), declaration);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/** Documented. */ class Example {}",
        "/** Documented. */ record Example(int value) {}",
        "/** Documented. */ enum Example { VALUE }",
        "/** Documented. */ class Example { /** Creates. */ private Example() {} }",
        "/** Documented. */ record Example(int value) { /** Copies.\n * @param value stored value\n */ Example {} }",
        "/** Documented. */ class Example { /** Reads.\n * @return description\n */ @Override public String toString() { return \"x\"; } }",
        "/** Documented. */ class Example { Runnable task = new Runnable() { /** Runs. */ @Override public void run() {} }; }",
        "/** Documented. */ class Example { /** Runs. */ void run() { /** Local. */ class Local { /** Works. */ private void work() {} } } }",
        "/** Documented. */ class Example { /** Counts.\n * @param count input\n * @return count\n * @throws IllegalArgumentException if negative\n */ private int count(int count) { if (count < 0) { throw new IllegalArgumentException(); } return count; } }"
      })
  void acceptsCompleteDocumentation(String declaration) throws Exception {
    assertEquals(0, violations(declaration), declaration);
  }

  @Test
  void rejectsJavadocsInTestSources() throws Exception {
    assertEquals(1, testViolations("/** Test-only documentation. */ class Example {}"));
  }

  @Test
  void rejectsInlineJavadocsInTestSources() throws Exception {
    assertEquals(
        1, testViolations("class Example { /** Test-only documentation. */ void run() {} }"));
  }

  @Test
  void rejectsJavadocShapedCommentsInsideTestMethods() throws Exception {
    assertEquals(1, testViolations("class Example { void run() { /** documentation */ } }"));
  }

  @Test
  void rejectsEveryConsecutiveJavadocShapedComment() throws Exception {
    assertEquals(2, testViolations("/** first */ /** second */ class Example {}"));
  }

  @Test
  void rejectsMinimalEmptyJavadocShapedComment() throws Exception {
    assertEquals(1, testViolations("/**/ class Example {}"));
  }

  @Test
  void acceptsOrdinaryBlockCommentsInTestSources() throws Exception {
    assertEquals(0, testViolations("/* ordinary comment */ class Example {}"));
  }

  @Test
  void acceptsJavadocSyntaxInsideTestSourceStrings() throws Exception {
    assertEquals(0, testViolations("class Example { String value = \"/** not a comment */\"; }"));
  }

  @Test
  void acceptsJavadocSyntaxInsideTestSourceTextBlocks() throws Exception {
    assertEquals(
        0,
        testViolations(
            """
            class Example {
              String value = \"\"\"
              /** not a comment */
              \"\"\";
            }
            """));
  }

  private int violations(String source) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var document =
        factory.newDocumentBuilder().parse(Path.of("config/checkstyle/java.xml").toFile());
    var rules =
        (NodeList)
            XPathFactory.newInstance()
                .newXPath()
                .evaluate(
                    "/module/module/module[@name='MissingJavadocType' or @name='MissingJavadocMethod' or @name='JavadocMethod']",
                    document,
                    XPathConstants.NODESET);
    assertEquals(3, rules.getLength(), "All documentation rules must remain configured");
    var checker = new Checker();
    var file = Files.createTempFile(temporaryDirectory, "javadoc-policy-", ".java");

    try {
      Files.writeString(file, source);
      var configuration = new DefaultConfiguration("Checker");
      var walker = new DefaultConfiguration("TreeWalker");
      for (int i = 0; i < rules.getLength(); i++) {
        var element = (Element) rules.item(i);
        var rule = new DefaultConfiguration(element.getAttribute("name"));
        var properties = element.getElementsByTagName("property");
        for (int j = 0; j < properties.getLength(); j++) {
          var property = (Element) properties.item(j);
          rule.addProperty(property.getAttribute("name"), property.getAttribute("value"));
        }
        walker.addChild(rule);
      }
      configuration.addChild(walker);
      checker.setModuleClassLoader(JavadocPolicyTest.class.getClassLoader());
      checker.configure(configuration);
      return checker.process(List.of(file.toFile()));
    } finally {
      checker.destroy();
      Files.deleteIfExists(file);
    }
  }

  private int testViolations(String source) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var document =
        factory.newDocumentBuilder().parse(Path.of("config/checkstyle/java-test.xml").toFile());
    var rules =
        (NodeList)
            XPathFactory.newInstance()
                .newXPath()
                .evaluate(
                    "/module/module/module[@name='io.github.suppierk.build.NoJavadocCheck']",
                    document,
                    XPathConstants.NODESET);
    assertEquals(1, rules.getLength(), "Test Javadoc rule must remain configured");
    var checker = new Checker();
    var file = Files.createTempFile(temporaryDirectory, "test-javadoc-policy-", ".java");

    try {
      Files.writeString(file, source);
      var configuration = new DefaultConfiguration("Checker");
      var walker = new DefaultConfiguration("TreeWalker");
      var element = (Element) rules.item(0);
      var rule = new DefaultConfiguration(element.getAttribute("name"));
      var properties = element.getElementsByTagName("property");
      for (int index = 0; index < properties.getLength(); index++) {
        var property = (Element) properties.item(index);
        rule.addProperty(property.getAttribute("name"), property.getAttribute("value"));
      }
      walker.addChild(rule);
      configuration.addChild(walker);
      checker.setModuleClassLoader(JavadocPolicyTest.class.getClassLoader());
      checker.configure(configuration);
      return checker.process(List.of(file.toFile()));
    } finally {
      checker.destroy();
      Files.deleteIfExists(file);
    }
  }
}
