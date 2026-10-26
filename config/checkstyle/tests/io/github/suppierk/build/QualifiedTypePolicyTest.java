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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QualifiedTypePolicyTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(strings = {"java.xml", "java-test.xml"})
  void configuresTheNativeQualifiedTypeCheckForEachSourceProfile(String profile) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var rules = factory.newDocumentBuilder().parse(Path.of("config/checkstyle", profile).toFile());
    var count =
        XPathFactory.newInstance()
            .newXPath()
            .evaluate("count(/module/module/module[@name='UnnecessaryFullyQualifiedType'])", rules);

    assertEquals("1", count);
  }

  @Test
  void rejectsAnUnnecessaryQualifiedVariableType() throws Exception {
    assertEquals(1, violations("class Example { java.util.List<String> values; }"));
  }

  @Test
  void permitsAnImportedStaticCall() throws Exception {
    assertEquals(
        0,
        violations(
            "import java.util.Objects; class Example { String value(String input) { return Objects.requireNonNull(input); } }"));
  }

  @Test
  void permitsQualificationWhenTheDeclaredTypeHasTheSameName() throws Exception {
    assertEquals(
        0,
        violations(
            "class Objects { String value(String input) { return java.util.Objects.requireNonNull(input); } }"));
    assertEquals(0, violations("class Request { org.eclipse.jetty.server.Request delegate; }"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"java.xml", "java-test.xml"})
  void rejectsQualifiedAnnotationNamesIncludingPackageAnnotations(String profile) throws Exception {
    assertEquals(
        1, annotationViolations(profile, "Example.java", "@java.lang.Deprecated class Example {}"));
    assertEquals(
        1,
        annotationViolations(
            profile,
            "package-info.java",
            "@org.jspecify.annotations.NullMarked\npackage io.github.suppierk.fixture;"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"java.xml", "java-test.xml"})
  void permitsSimpleImportedAnnotationsAndQualifiedText(String profile) throws Exception {
    assertEquals(0, annotationViolations(profile, "Example.java", "@Deprecated class Example {}"));
    assertEquals(
        0,
        annotationViolations(
            profile,
            "package-info.java",
            "@NullMarked\npackage io.github.suppierk.fixture;\nimport org.jspecify.annotations.NullMarked;"));
    assertEquals(
        0,
        annotationViolations(
            profile,
            "Example.java",
            "// @java.lang.Deprecated\nclass Example { String value = \"@java.lang.Deprecated\"; }"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"java.xml", "java-test.xml"})
  void permitsQualifiedEnumValuesInsideAnnotationArguments(String profile) throws Exception {
    assertEquals(
        0,
        annotationViolations(
            profile,
            "Example.java",
            "import org.junit.jupiter.api.TestInstance;\n@TestInstance(TestInstance.Lifecycle.PER_CLASS) class Example {}"));
  }

  private int annotationViolations(String profile, String name, String source) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var rules = factory.newDocumentBuilder().parse(Path.of("config/checkstyle", profile).toFile());
    var query =
        XPathFactory.newInstance()
            .newXPath()
            .evaluate(
                "/module/module/module[@name='MatchXpath'][property[@name='id' and @value='qualifiedAnnotation']]/property[@name='query']/@value",
                rules);
    assertFalse(query.isBlank(), "The configured annotation import rule must exist");
    var rule = new DefaultConfiguration("MatchXpath");
    rule.addProperty("query", query);
    return violations(source, name, rule);
  }

  private int violations(String source) throws Exception {
    return violations(
        source, "Example.java", new DefaultConfiguration("UnnecessaryFullyQualifiedType"));
  }

  private int violations(String source, String name, DefaultConfiguration rule) throws Exception {
    var file = Files.createTempDirectory(temporaryDirectory, "qualified-type-").resolve(name);
    var checker = new Checker();

    try {
      Files.writeString(file, source);
      var configuration = new DefaultConfiguration("Checker");
      var walker = new DefaultConfiguration("TreeWalker");
      walker.addChild(rule);
      configuration.addChild(walker);
      checker.setModuleClassLoader(QualifiedTypePolicyTest.class.getClassLoader());
      checker.configure(configuration);
      return checker.process(List.of(file.toFile()));
    } finally {
      checker.destroy();
      Files.deleteIfExists(file);
    }
  }
}
