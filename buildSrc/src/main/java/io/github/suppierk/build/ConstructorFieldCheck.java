package io.github.suppierk.build;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

/** Checks constructor field writes through compiler-resolved Java bindings. */
public final class ConstructorFieldCheck {
  /** Prevents construction of this build tool. */
  private ConstructorFieldCheck() {}

  /**
   * Checks one source set using task-specific file lists, without generating class files.
   *
   * @param arguments source-list file, classpath-list file and diagnostic report path
   * @throws IOException if an input or report cannot be read or written
   * @throws IllegalArgumentException if the three required paths are absent
   * @throws IllegalStateException if compilation or qualification checks fail
   */
  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 3) {
      throw new IllegalArgumentException("Expected source list, classpath list and report paths");
    }

    var sources =
        Files.readAllLines(Path.of(arguments[0]), StandardCharsets.UTF_8).stream()
            .map(Path::of)
            .toList();
    var classpath =
        String.join(
            File.pathSeparator, Files.readAllLines(Path.of(arguments[1]), StandardCharsets.UTF_8));
    var diagnostics = check(sources, classpath);
    var errors =
        diagnostics.stream().filter(item -> item.getKind() == Diagnostic.Kind.ERROR).toList();
    var report = Path.of(arguments[2]).toAbsolutePath();
    Files.createDirectories(Objects.requireNonNull(report.getParent()));
    var text =
        errors.isEmpty()
            ? "Constructor field qualification passed for " + sources.size() + " source files\n"
            : String.join("\n", errors.stream().map(Object::toString).toList()) + "\n";
    Files.writeString(report, text, StandardCharsets.UTF_8);
    if (!errors.isEmpty()) {
      System.err.print(text);
      throw new IllegalStateException("Constructor field verification failed; see " + report);
    }
  }

  /**
   * Resolves source-written constructor assignments and reports unqualified instance fields.
   * Parsing precedes attribution so generated constructor and record writes cannot become targets.
   * Reported parsing and attribution errors fail verification.
   *
   * @param sources authored Java files in one compilation source set
   * @param classpath that source set's compilation classpath
   * @return compiler and qualification diagnostics
   * @throws IOException if source files cannot be read
   * @throws IllegalStateException if the launcher is not a JDK or a target cannot be resolved
   */
  public static List<Diagnostic<? extends JavaFileObject>> check(
      List<Path> sources, String classpath) throws IOException {
    if (sources.isEmpty()) {
      return List.of();
    }

    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("Constructor field verification requires a JDK");
    }

    var diagnostics = new DiagnosticCollector<JavaFileObject>();

    try (var files =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  diagnostics,
                  List.of(
                      "--release",
                      "21",
                      "-proc:none",
                      "-implicit:none",
                      "-Xmaxerrs",
                      "2147483647",
                      "-classpath",
                      classpath),
                  null,
                  files.getJavaFileObjectsFromPaths(sources));
      var targets = new ArrayList<TreePath>();
      for (var unit : task.parse()) {
        new ConstructorWrites(targets).scan(unit, false);
      }
      if (!targets.isEmpty() && !hasErrors(diagnostics)) {
        task.analyze();
        if (!hasErrors(diagnostics)) {
          var trees = Trees.instance(task);
          for (var target : targets) {
            var element = trees.getElement(target);
            if (element == null) {
              throw new IllegalStateException(
                  "Cannot resolve constructor assignment in "
                      + target.getCompilationUnit().getSourceFile().getName());
            }

            if (element.getKind() == ElementKind.FIELD
                && !element.getModifiers().contains(Modifier.STATIC)) {
              trees.printMessage(
                  Diagnostic.Kind.ERROR,
                  "Constructor field assignment requires an explicit this receiver: "
                      + element.getSimpleName(),
                  target.getLeaf(),
                  target.getCompilationUnit());
            }
          }
        }
      }
    }

    return List.copyOf(diagnostics.getDiagnostics());
  }

  /**
   * Reports whether syntax or attribution has already failed.
   *
   * @param diagnostics collected compiler output
   * @return whether an error was reported
   */
  private static boolean hasErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
    return diagnostics.getDiagnostics().stream()
        .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR);
  }

  /** Collects bare write targets with the lexical constructor scope intact. */
  private static final class ConstructorWrites extends TreePathScanner<@Nullable Void, Boolean> {
    private final List<TreePath> targets;

    /**
     * Creates a collector for one source tree.
     *
     * @param targets shared source-written targets
     */
    private ConstructorWrites(List<TreePath> targets) {
      this.targets = targets;
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Void visitClass(ClassTree node, Boolean inConstructor) {
      return super.visitClass(node, false);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Void visitMethod(MethodTree node, Boolean inConstructor) {
      return scan(node.getBody(), node.getReturnType() == null);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Void visitAssignment(AssignmentTree node, Boolean inConstructor) {
      collect(node.getVariable(), inConstructor);
      return super.visitAssignment(node, inConstructor);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Void visitCompoundAssignment(
        CompoundAssignmentTree node, Boolean inConstructor) {
      collect(node.getVariable(), inConstructor);
      return super.visitCompoundAssignment(node, inConstructor);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Void visitUnary(UnaryTree node, Boolean inConstructor) {
      switch (node.getKind()) {
        case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT ->
            collect(node.getExpression(), inConstructor);
        default -> {
          // Reads are outside this assignment rule.
        }
      }
      return super.visitUnary(node, inConstructor);
    }

    /**
     * Records only bare identifiers written within an explicit constructor.
     *
     * @param target assignment or update target
     * @param inConstructor whether the current lexical method is a constructor
     */
    private void collect(ExpressionTree target, Boolean inConstructor) {
      while (target instanceof ParenthesizedTree parentheses) {
        target = parentheses.getExpression();
      }
      if (Boolean.TRUE.equals(inConstructor) && target instanceof IdentifierTree) {
        targets.add(Objects.requireNonNull(TreePath.getPath(getCurrentPath(), target)));
      }
    }
  }
}
