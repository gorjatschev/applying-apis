package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * This java project collects Java repositories from GitHub, selects a part of
 * them based on their declared dependencies and parses them.
 * 
 * This class contains the main method of this project with all its customizable
 * parameters (except for the GitHub PAT which can be found in
 * {@code RepositoriesPicker}).
 */
public class Application {
        private static final Logger log = Utils.getLogger();
        private static final boolean COLLECT_REPOSITORIES = true;
        private static final boolean GET_DEPENDENCIES_OF_COLLECTED_REPOSITORIES = true;
        private static final boolean GET_MCR_TAGS_OF_COLLECTED_REPOSITORIES = true;
        private static final boolean SELECT_REPOSITORIES = true;
        private static final boolean PARSE_REPOSITORIES = true;
        private static final boolean COLLECT_API_CATEGORIES = true;
        private static final boolean DOWNLOAD_JARS = true;
        private static final int STARS_LIMIT = 100; // at least 100 stars
        private static final int CONTRIBUTOR_LIMIT = 2; // at least 2 contributors
        private static final int COMMITS_LIMIT = 100; // at least 100 commits
        private static final int DEPENDENCIES_LIMIT = 30; // at most 30 dependencies
        private static final int FILES_LIMIT = 1000; // at most 1000 files
        private static final List<String> DEPENDENCIES = List.of("org.apache.lucene:lucene-analyzers-common",
                        "org.apache.lucene:lucene-core"); // can be left empty

        /**
         * Runs everything that is needed according to the chosen parameters in the
         * {@code Application} class.
         * 
         * @param args
         * @throws IllegalStateException
         * @throws IOException
         * @throws InterruptedException
         */
        public static void main(String[] args) throws IllegalStateException, IOException, InterruptedException {
                long startTime = System.currentTimeMillis();
                // Collect all Java GitHub repositories that use Maven and meet the limit
                // conditions
                if (COLLECT_REPOSITORIES) {
                        RepositoriesPicker.collectRepositories(STARS_LIMIT, CONTRIBUTOR_LIMIT, COMMITS_LIMIT);
                }
                // Collect the dependencies of the collected repositories such that they
                // can be used for a separate analysis of all dependencies
                if (GET_DEPENDENCIES_OF_COLLECTED_REPOSITORIES) {
                        RepositoriesPicker.getDependenciesOfCollectedRepositories();
                }
                // Collect the MCR tags of all used dependencies in the collected repositories
                // and assign them to the repositories
                if (GET_MCR_TAGS_OF_COLLECTED_REPOSITORIES) {
                        RepositoriesPicker.getMcrTagsOfDependenciesOfCollectedRepositories();
                }
                // Select repositories out of the collected repositories based on their
                // declared dependencies and the dependencies limit and files limit
                if (SELECT_REPOSITORIES) {
                        RepositoriesPicker.selectRepositories(DEPENDENCIES, DEPENDENCIES_LIMIT, FILES_LIMIT);
                }
                // Parse the selected repositories
                if (PARSE_REPOSITORIES) {
                        Utils.readCSVFile(Utils.getSelectedRepositoriesFile(DEPENDENCIES))
                                        .forEach(repository -> parse(repository));
                }
                log.info("Successful.");
                long stopTime = System.currentTimeMillis();
                Duration d = Duration.ofMillis(stopTime - startTime);
                log.info("Execution took " + d.toHours() + " hour(s), " + d.toMinutesPart() + " minute(s) and "
                                + d.toSecondsPart() + " second(s).");
        }

        /**
         * Parses the given repository if the CSV file
         * {@value Utils#getDataFile(repositoryName)} does not exist yet. A
         * {@code RepositoryManager} is created that downloads the Java files and
         * collects the dependencies from the POM files of the repository. A
         * {@code Parser} then parses the downloaded Java files. The
         * {@code DependenciesAllocator} assigns the APIs to the found API usages in the
         * Java files of the repository.
         * 
         * @param repository
         */
        public static void parse(Map<String, String> repository) {
                String repositoryName = repository.get(Utils.REPOSITORY_NAME);
                File file = new File(Utils.getDataFile(repositoryName));
                boolean parsed = file.exists();
                RepositoryManager repositoryManager;
                if (!parsed) {
                        try {
                                repositoryManager = new RepositoryManager(repositoryName);
                                repositoryManager.downloadJavaFiles();
                                List<String> jars = repositoryManager.collectDependencies(COLLECT_API_CATEGORIES,
                                                DOWNLOAD_JARS);
                                Parser parser = new Parser(repositoryName, jars);
                                parser.parse();
                                DependenciesAllocator dependenciesAllocator = new DependenciesAllocator(repositoryName,
                                                parser.getParsedJavaFiles());
                                dependenciesAllocator.assignAPIs();
                        } catch (GitAPIException | IOException | NullPointerException e) {
                                log.info("Skipping repository \"" + repositoryName + "\" because of "
                                                + e.getClass().getName());
                        }
                } else {
                        log.info("Skipping repository \"" + repositoryName
                                        + "\" since the file with the parsing results already exists.");
                }
        }

}
