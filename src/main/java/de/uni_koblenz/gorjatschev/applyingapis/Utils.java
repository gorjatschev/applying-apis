package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class contains reusable logic regarding maps, CSV files, and CSV file
 * names and reusable strings like CSV file names, directories, and column
 * names.
 */
public class Utils {

    public static final Logger logger = LogManager.getLogger();

    // Created and used CSV files in their creation order
    // Contains the names of the collected repositories
    public static final String O_COLLECTED_REPOSITORIES_FILE = "output/repositories_collected.csv";
    // Contains the names and valid dependencies of the filtered collected
    // repositories
    public static final String O_REPOSITORIES_WITH_DEPENDENCIES_FILE = "output/repositories_with_dependencies.csv";
    // Contains groupId and artifactId of all declared valid dependencies
    public static final String O_DEPENDENCIES_FILE = "output/dependencies.csv";
    // Contains groupId, artifactId, and mcrTags of the declared valid dependencies
    public static final String O_DEPENDENCIES_WITH_TAGS_FILE = "output/dependencies_with_mcrTags.csv";
    // Contains the names and mcrTags of the filtered collected repositories
    public static final String O_REPOSITORIES_WITH_TAGS_FILE = "output/repositories_with_mcrTags.csv";

    // Directory for the downloaded repositories
    public static final String T_GIT_FILES_DIR = "temp/git/";
    // Directory for the downloaded dependencies
    public static final String T_DEPENDENCIES_DIR = "temp/dependencies/";
    // Directory for the downloaded dependency repositories (to find newest version)
    public static final String T_DEPENDENCIES_REPOS_DIR = "temp/dependencies_repos/";
    // Directory for the downloaded Java files
    public static final String T_JAVA_FILES_DIR = "temp/java_files/";

    public static final String SRC_MAIN_DIR = "src/main/java";
    public static final String SRC_TEST_DIR = "src/test/java";
    public static final String GITHUB_API_URL = "https://api.github.com/";
    public static final String MAVEN_DOWNLOAD_URL = "https://repo1.maven.org/maven2/";

    // Strings, mostly column names in CSV files
    public static final String GROUP_ID = "groupId";
    public static final String ARTIFACT_ID = "artifactId";
    public static final String VERSION = "version";
    public static final String MCR_CATEGORIES = "mcrCategories";
    public static final String MCR_TAGS = "mcrTags";
    public static final String DOWNLOADED = "downloaded";
    public static final String DL_VERS_IF_DIFF = "dlVersIfDiff";
    public static final String JAR_URL = "jarURL";
    public static final String PATH_TO_JAR = "pathToJAR";
    public static final String FILE_PATH = "filePath";
    public static final String PACKAGE_NAME = "packageName";
    public static final String CLASS_NAME = "className";
    public static final String METHOD_NAME = "methodName";
    public static final String LINE = "line";
    public static final String COLUMN = "column";
    public static final String JP_TYPE_OF_ELEMENT = "javaParserTypeOfElement";
    public static final String USED_CLASS_OF_ELEMENT = "usedClassOfElement";
    public static final String API = "api";
    public static final String COUNT = "count";
    public static final String COUNT_ALL = "countAll";
    public static final String PROPORTION = "proportion";
    public static final String SRC_ROOT = "srcRoot";
    public static final String REPOSITORY_NAME = "repositoryName";
    public static final String DEPENDENCIES = "dependencies";

    /**
     * Gets the logger of this application.
     * 
     * @return The logger
     */
    public static Logger getLogger() {
        return logger;
    }

    /**
     * Creates a map from the strings in {@code args}. {@code args} must correspond
     * to key1, value1, key2, value2, ...
     * 
     * @param args
     * @return The created map
     */
    public static Map<String, String> createMap(String... args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            map.put(args[i], args[i + 1]);
        }
        return map;
    }

    /**
     * Extends {@code map} with the strings in {@code args} by adding {@code args}
     * as keyX, valueX, keyX+1, valueX+1, ...
     * 
     * @param map
     * @param args
     * @return The extended map
     */
    public static Map<String, String> extendMap(Map<String, String> map, String... args) {
        Map<String, String> extendedMap = new LinkedHashMap<>();
        map.entrySet().forEach(entry -> extendedMap.put(entry.getKey(), entry.getValue()));
        for (int i = 0; i < args.length - 1; i += 2) {
            extendedMap.put(args[i], args[i + 1]);
        }
        return extendedMap;
    }

    /**
     * Extends the maps in the list {@code maps} with the strings in {@code args} by
     * adding {@code args} as key1, value1, key2, value2, ... to each map in the
     * list, i.e., the strings in {@code args} are added at the beginning of the
     * maps.
     * 
     * @param maps
     * @param args
     * @return The extended maps
     */
    public static List<Map<String, String>> extendMaps(List<Map<String, String>> maps, String... args) {
        List<Map<String, String>> extendedMaps = new ArrayList<>();
        maps.forEach(map -> {
            Map<String, String> extendedMap = new LinkedHashMap<>();
            for (int i = 0; i < args.length - 1; i += 2) {
                extendedMap.put(args[i], args[i + 1]);
            }
            map.entrySet().forEach(entry -> extendedMap.put(entry.getKey(), entry.getValue()));
            extendedMaps.add(extendedMap);
        });
        return extendedMaps;
    }

    /**
     * Reads the CSV file {@code fileName} and writes it in a list of maps of
     * strings. The keys correspond to the column names of the CSV file.
     * 
     * @param fileName
     * @return The CSV file as a list of maps
     * @throws IOException
     */
    public static List<Map<String, String>> readCSVFile(String fileName) throws IOException {
        List<Map<String, String>> list = new ArrayList<>();
        Reader reader = new FileReader(fileName);
        Iterable<CSVRecord> records = CSVFormat.RFC4180.withFirstRecordAsHeader().parse(reader);
        for (CSVRecord r : records) {
            list.add(r.toMap());
        }
        reader.close();
        return list;
    }

    /**
     * Writes the list of maps {@code input} in the CSV file {@code fileName}. The
     * keys correspond to the column names of the CSV file.
     * 
     * @param fileName
     * @param input
     * @throws IOException
     */
    public static void writeCSVFile(String fileName, List<Map<String, String>> input) throws IOException {
        Files.createDirectories(Path.of(fileName).getParent());
        try (Writer writer = new FileWriter(new File(fileName))) {
            try (CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.RFC4180)) {
                if (input.iterator().hasNext()) {
                    Set<String> header = input.iterator().next().keySet();
                    csvPrinter.printRecord(header);
                }
                for (Map<String, String> e : input) {
                    csvPrinter.printRecord(e.values());
                }
            }
        }
    }

    /**
     * Deletes the directory/file {@code file} recursively.
     * 
     * @param file
     */
    public static void deleteFileRecursively(File file) {
        if (file.isDirectory()) {
            for (File c : file.listFiles()) {
                deleteFileRecursively(c);
            }
        }
        file.delete();
    }

    /**
     * Converts the separators in the path {@code path} to the Unix separator
     * {@code /}.
     * 
     * @param path
     * @return The Unix path as a string
     */
    public static String getUnixPath(Path path) {
        return FilenameUtils.separatorsToUnix(path.toString());
    }

    /**
     * Gets the path of the dependencies file of the repository
     * {@code repositoryName} as a string:
     * {@code output/dependencies/dependencies_[repositoryName].csv}
     * 
     * @param repositoryName
     * @return The path of the dependencies file as a string
     */
    public static String getDependenciesFile(String repositoryName) {
        return "output/dependencies/dependencies_" + repositoryName.replace("/", "_") + ".csv";
    }

    /**
     * Gets the path of the data file of the repository {@code repositoryName} as a
     * string: {@code output/data/data_[repositoryName].csv}
     * 
     * @param repositoryName
     * @return The path of the data file as a string
     */
    public static String getDataFile(String repositoryName) {
        return "output/data/data_" + repositoryName.replace("/", "_") + ".csv";
    }

    /**
     * Gets the path of the selected repositories file as a string. Its name
     * consists of the dependencies from the list {@code dependencies}:
     * {@code output/repositories_selected/[dependencies].csv}
     * 
     * @param dependencies
     * @return The path of the selected repositories file as a string
     */
    public static String getSelectedRepositoriesFile(List<String> dependencies) {
        List<String> dependenciesList = new ArrayList<>();
        dependencies.forEach(dependency -> dependenciesList.add(dependency.replace(":", "_")));
        String dependenciesString = String.join("_", dependenciesList);
        return "output/repositories_selected/" + dependenciesString + ".csv";
    }

}
