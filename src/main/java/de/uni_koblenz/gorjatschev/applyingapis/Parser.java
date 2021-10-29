package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;

import org.apache.logging.log4j.Logger;

/**
 * Ths class contains the logic to parse Java directories and files.
 */
public class Parser {

    private static final Logger log = Utils.getLogger();
    private final String repositoryName;
    private final List<String> jars;
    private final ParserCollectionStrategy parserCollectionStrategy = new ParserCollectionStrategy();
    private final ProjectRoot projectRoot;
    private final List<Map<String, String>> parsedJavaFiles = new ArrayList<>();

    /**
     * Constructor for a {@code Parser}. Configures the parser for Java 8. Sets the
     * given repository as project root.
     * 
     * @param repositoryName
     * @param jars
     */
    public Parser(String repositoryName, List<String> jars) {
        log.info("Creating parser...");
        this.repositoryName = repositoryName;
        this.jars = jars;
        this.parserCollectionStrategy.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
        this.projectRoot = this.parserCollectionStrategy.collect(Path.of(Utils.T_JAVA_FILES_DIR + this.repositoryName));
    }

    /**
     * Returns the parsed Java files.
     * 
     * @return The list of the parsed Java files as maps
     */
    public List<Map<String, String>> getParsedJavaFiles() {
        return this.parsedJavaFiles;
    }

    /**
     * Parses the repository and resolves the contained symbols. Saves the results
     * in the CSV file {@value Utils#getDataFile(repositoryName)}.
     * 
     * @throws IOException
     */
    public void parse() throws IOException {
        log.info("Starting JavaParser and JavaSymbolSolver...");
        File file = new File(Utils.getDataFile(this.repositoryName));
        if (file.exists()) {
            log.info("Parsing and symbol solving already performed.");
            return;
        }
        final List<Map<String, String>> usedClasses = new ArrayList<>();
        this.projectRoot.getSourceRoots().forEach(srcRoot -> usedClasses.addAll(parseSourceRoot(srcRoot)));
        Utils.writeCSVFile(Utils.getDataFile(this.repositoryName), usedClasses);
        JavaParserFacade.clearInstances();
        log.info("Parsing and symbol solving finished.");
    }

    /**
     * Parses the source root and resolves the contained symbols.
     * 
     * @param srcRoot
     * @return A list of the resolved symbols as maps
     */
    private List<Map<String, String>> parseSourceRoot(SourceRoot srcRoot) {
        String root = Utils.getUnixPath(srcRoot.getRoot());
        log.info("--Parsing the src \"" + root + "\".");
        List<Map<String, String>> usedClassesInSrc = new ArrayList<>();
        CombinedTypeSolver combinedTypeSolver = createInitialCombinedTypeSolver();
        // Add the Java files from the same source directory for type solving
        combinedTypeSolver.add(new JavaParserTypeSolver(root));
        // Add the Java files from the corresponding main source directory for type
        // solving if its a test source directory
        if (root.endsWith(Utils.SRC_TEST_DIR)) {
            String mainRoot = root.replace(Utils.SRC_TEST_DIR, Utils.SRC_MAIN_DIR);
            if (projectRoot.getSourceRoots().stream()
                    .anyMatch(src -> Utils.getUnixPath(src.getRoot()).equals(mainRoot))) {
                log.info("----Adding the corresponding src/main/java directory as a type solver...");
                combinedTypeSolver.add(new JavaParserTypeSolver(mainRoot));
            }
        }
        this.parserCollectionStrategy.getParserConfiguration().setAttributeComments(false)
                .setSymbolResolver(new JavaSymbolSolver(combinedTypeSolver));
        try {
            srcRoot.parse("", (localPath, absolutePath, result) -> {
                result.ifSuccessful(cu -> usedClassesInSrc.addAll(resolveSymbols(cu, absolutePath, srcRoot)));
                return SourceRoot.Callback.Result.DONT_SAVE;
            });
        } catch (Exception e) {
            log.warn("--Could not parse the src \"" + root + "\" because of " + e + ".");
        }
        JavaParserFacade.clearInstances();
        return usedClassesInSrc;
    }

    /**
     * Resolves the contained symbols in the given {@code CompilationUnit}.
     * 
     * @param cu
     * @param absolutePath
     * @param srcRoot
     * @return A list of the resolved symbols as maps
     */
    private List<Map<String, String>> resolveSymbols(CompilationUnit cu, Path absolutePath, SourceRoot srcRoot) {
        List<Map<String, String>> usedClassesInCU = new ArrayList<>();
        String subpath = Utils.getUnixPath(absolutePath.subpath(4, absolutePath.getNameCount()));
        this.parsedJavaFiles.add(Utils.createMap(Utils.SRC_ROOT,
                Utils.getUnixPath(srcRoot.getRoot().subpath(4, srcRoot.getRoot().getNameCount())), Utils.FILE_PATH,
                subpath));
        log.info("----Parsing the file \"" + Utils.getUnixPath(absolutePath) + "\".");
        Optional<PackageDeclaration> packageDecl = cu.getPackageDeclaration();
        String packageName = packageDecl.isPresent() ? packageDecl.get().getNameAsString() : "null";
        cu.getChildNodes().stream().filter(childOfCU -> childOfCU instanceof ClassOrInterfaceDeclaration)
                .map(classOrInterface -> (ClassOrInterfaceDeclaration) classOrInterface)
                .forEach(classOrInterface -> usedClassesInCU.addAll(
                        Utils.extendMaps(ClassOrInterfaceSymbolResolver.resolveClassOrInterface(classOrInterface),
                                Utils.FILE_PATH, subpath, Utils.PACKAGE_NAME, packageName, Utils.CLASS_NAME,
                                classOrInterface.getNameAsString())));
        return usedClassesInCU;
    }

    /**
     * Creates a {@code CombinedTypeSolver} that contains a
     * {@code ReflectionTypeSolver} and a {@code JarTypeSolver} for each JAR, i.e.,
     * dependency of the repository.
     * 
     * @return The created {@code CombinedTypeSolver}
     */
    private CombinedTypeSolver createInitialCombinedTypeSolver() {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        jars.forEach(jar -> {
            try {
                combinedTypeSolver.add(new JarTypeSolver(jar));
            } catch (IOException e) {
                log.warn("--Could not add \"" + jar + "\" as a JarTypeSolver. Skipping this JAR.");
            }
        });
        return combinedTypeSolver;
    }

}
