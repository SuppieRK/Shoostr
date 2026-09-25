package io.github.suppierk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.DefaultConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstanceFieldInitializationTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "class Example { private final Object field = new Object(); }",
        "class Example { Object field = create(); }",
        "class Example { int count = 1; }",
        "class Example { Object value = null; }",
        "class Example { int[] values = {1, 2}; }",
        "class Example { Runnable action = () -> {}; }",
        "class Example { class Nested { Object field = new Object(); } }",
        "class Example { static class Nested { Object field = new Object(); } }",
        "class Example { void run() { class Local { Object field = new Object(); } } }",
        "class Example { void run() { var obj = new Object() { Object field = new Object(); }; } }",
        "enum Example { VALUE; private final Object field = new Object(); }",
        "enum Example { VALUE { private final Object field = new Object(); }; }",
        "class Example { Object field; { field = new Object(); } }"
      })
  void rejectsInstanceInitializationOutsideConstructors(String source) throws Exception {
    assertEquals(1, violations(source), source);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "class Example { final Object field; Example() { field = new Object(); } }",
        "class Example { final Object field; Example(Object value) { field = value; } }",
        "class Example { final Object field; Example() { this(new Object()); } Example(Object value) { field = value; } }",
        "class Example { static final Object VALUE = new Object(); }",
        "class Example { static Object value = create(); }",
        "class Example { static Object value; static { value = new Object(); } }",
        "class Example { void run() { Object value = new Object(); } }",
        "class Example { Object field; void set(Object value) { field = value; } }",
        "interface Example { Object VALUE = new Object(); }",
        "@interface Example { String VALUE = \"value\"; String value() default \"value\"; }",
        "record Example(Object value) { Example { value = new Object(); } }",
        "enum Example { VALUE; final Object field; Example() { field = new Object(); } }"
      })
  void permitsConstructorsStaticFieldsAndLocalVariables(String source) throws Exception {
    assertEquals(0, violations(source), source);
  }

  private int violations(String source) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var rules = factory.newDocumentBuilder().parse(Path.of("config/checkstyle/java.xml").toFile());
    String query =
        XPathFactory.newInstance()
            .newXPath()
            .evaluate(
                "/module/module/module[@name='MatchXpath']/property[@name='query']/@value", rules);
    assertFalse(query.isBlank(), "The configured field-initialization rule must exist");
    var file = Files.createTempFile(temporaryDirectory, "instance-fields-", ".java");
    var checker = new Checker();

    try {
      Files.writeString(file, source);
      var configuration = new DefaultConfiguration("Checker");
      var walker = new DefaultConfiguration("TreeWalker");
      var rule = new DefaultConfiguration("MatchXpath");
      rule.addProperty("query", query);
      walker.addChild(rule);
      configuration.addChild(walker);
      checker.setModuleClassLoader(InstanceFieldInitializationTest.class.getClassLoader());
      checker.configure(configuration);
      return checker.process(List.of(file.toFile()));
    } finally {
      checker.destroy();
      Files.deleteIfExists(file);
    }
  }
}
