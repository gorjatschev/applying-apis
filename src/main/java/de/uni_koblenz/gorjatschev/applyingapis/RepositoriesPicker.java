package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * This class contains the logic to collect repositories from GitHub and filter
 * them based on their dependencies and other factors.
 */
public class RepositoriesPicker {

    private static final Logger log = Utils.getLogger();
    private static final String USERNAME_AND_TOKEN = "username:token";
    private static final String AUTHORIZATION = "Basic "
            + Base64.getEncoder().encodeToString(USERNAME_AND_TOKEN.getBytes());
    private static final Set<String> allRepositories = new HashSet<>();

    /**
     * Collects all filtered repositories that are filtered using the
     * {@code starsLimit}, the {@code contributorsLimit} and the
     * {@code commitsLimit}. Writes them in the CSV file
     * {@value Utils#O_COLLECTED_REPOSITORIES_FILE}.
     * 
     * @param starsLimit
     * @param contributorsLimit
     * @param commitsLimit
     * @throws IOException
     * @throws InterruptedException
     */
    public static void collectRepositories(int starsLimit, int contributorsLimit, int commitsLimit)
            throws IOException, InterruptedException {
        Set<String> filteredRepositories = new HashSet<>();
        filteredRepositories.addAll(filterRepositories(starsLimit, contributorsLimit, commitsLimit));
        List<Map<String, String>> filteredRepositoriesList = new ArrayList<>();
        filteredRepositories.forEach(
                repository -> filteredRepositoriesList.add(Utils.createMap(Utils.REPOSITORY_NAME, repository)));
        Utils.writeCSVFile(Utils.O_COLLECTED_REPOSITORIES_FILE, filteredRepositoriesList);
        log.info("Found " + allRepositories.size() + " Java GitHub repositories with at least 100 stars.");
        log.info("Filtered " + filteredRepositories.size()
                + " Java GitHub repositories with at least 100 stars, one POM file, " + contributorsLimit
                + " contributors and " + commitsLimit + " commits.");
    }

    /**
     * Collects the dependencies of the filtered repositories using the
     * {@code RepositoryManager}. Filters out repositories that are not parsable, do
     * not have a src/main/java directory or have less than two resolvable
     * dependencies. Writes them in the CSV file
     * {@value Utils#O_REPOSITORIES_WITH_DEPENDENCIES_FILE}.
     * 
     * @throws IOException
     */
    public static void getDependenciesOfCollectedRepositories() throws IOException {
        List<Map<String, String>> repositoriesWithDependenciesList = new ArrayList<>();
        Utils.readCSVFile(Utils.O_COLLECTED_REPOSITORIES_FILE).forEach(repository -> {
            String repositoryName = repository.get(Utils.REPOSITORY_NAME);
            RepositoryManager repositoryManager;
            try {
                repositoryManager = new RepositoryManager(repositoryName);
                if (repositoryManager.isParsable()) {
                    String dependenciesString = createDependenciesString(repositoryManager.resolveDependencies());
                    repositoriesWithDependenciesList.add(Utils.createMap(Utils.REPOSITORY_NAME, repositoryName,
                            Utils.DEPENDENCIES, dependenciesString));
                }
                repositoryManager.deleteRepository();
            } catch (GitAPIException | IOException | InvalidPathException e) {
                log.warn("--Could not collect repository \"" + repositoryName + "\" because of "
                        + e.getClass().getName() + ".");
            }
        });
        Utils.writeCSVFile(Utils.O_REPOSITORIES_WITH_DEPENDENCIES_FILE, repositoriesWithDependenciesList);
        Utils.deleteFileRecursively(new File(Utils.T_GIT_FILES_DIR));

        List<Map<String, String>> repositoriesWithEnoughDependencies = new ArrayList<>();
        Utils.readCSVFile(Utils.O_REPOSITORIES_WITH_DEPENDENCIES_FILE).forEach(repository -> {
            String filteredDependencies = filterDependencies(repository);
            if (filteredDependencies != null) {
                repositoriesWithEnoughDependencies.add(Utils.createMap(Utils.REPOSITORY_NAME,
                        repository.get(Utils.REPOSITORY_NAME), Utils.DEPENDENCIES, filteredDependencies));
            }
        });
        Utils.writeCSVFile(Utils.O_REPOSITORIES_WITH_DEPENDENCIES_FILE, repositoriesWithEnoughDependencies);
        log.info("Filtered " + repositoriesWithEnoughDependencies.size()
                + " parsable GitHub repositories with \"src/main/java\" that contain at least 2 valid dependencies.");
    }

    /**
     * Collects all dependencies of the repositories with dependencies in a set and
     * writes them in the CSV file {@value Utils#O_DEPENDENCIES_FILE}. Collects the
     * MCR tags of those dependencies using the {@code DependenciesManager} and
     * writes them in the CSV file {@value Utils#O_DEPENDENCIES_WITH_TAGS_FILE}.
     * Assigns the MCR tags to the filtered repositories based on their
     * dependencies. Filters out repositories that do not have MCR tags and writes
     * them in the CSV file {@value Utils#O_REPOSITORIES_WITH_TAGS_FILE}.
     * 
     * @throws IOException
     */
    public static void getMcrTagsOfDependenciesOfCollectedRepositories() throws IOException {
        Set<Map<String, String>> dependencies = new HashSet<>();
        Utils.readCSVFile(Utils.O_REPOSITORIES_WITH_DEPENDENCIES_FILE).forEach(repository -> {
            String[] dependenciesArray = repository.get(Utils.DEPENDENCIES).replace("[", "").replace("]", "")
                    .split(",");
            for (String dependency : dependenciesArray) {
                dependencies.add(Utils.createMap(Utils.GROUP_ID, dependency.split(":")[0], Utils.ARTIFACT_ID,
                        dependency.split(":")[1]));
            }
        });
        Utils.writeCSVFile(Utils.O_DEPENDENCIES_FILE, new ArrayList<>(dependencies));
        DependenciesManager dependenciesManager = new DependenciesManager(null, new ArrayList<>());
        List<Map<String, String>> dependenciesWithMCRTags = dependenciesManager
                .getMCRTags(Utils.readCSVFile(Utils.O_DEPENDENCIES_FILE));
        Utils.writeCSVFile(Utils.O_DEPENDENCIES_WITH_TAGS_FILE, dependenciesWithMCRTags);

        List<Map<String, String>> repositoriesWithTaggedDependencies = new ArrayList<>();
        List<Map<String, String>> dependenciesWithTags = Utils.readCSVFile(Utils.O_DEPENDENCIES_WITH_TAGS_FILE);
        Utils.readCSVFile(Utils.O_REPOSITORIES_WITH_DEPENDENCIES_FILE).forEach(repository -> {
            Set<String> allTags = new HashSet<>();
            String[] dependenciesArray = repository.get(Utils.DEPENDENCIES).replace("[", "").replace("]", "")
                    .split(",");
            for (String dependency : dependenciesArray) {
                Optional<Map<String, String>> found = dependenciesWithTags.stream()
                        .filter(dep -> dep.get(Utils.GROUP_ID).equals(dependency.split(":")[0])
                                && dep.get(Utils.ARTIFACT_ID).equals(dependency.split(":")[1]))
                        .findFirst();
                if (found.isPresent()) {
                    String[] tags = found.get().get(Utils.MCR_TAGS).split(",");
                    allTags.addAll(Arrays.asList(tags));
                }
            }
            if (!allTags.isEmpty()) {
                List<String> orderedTagsList = allTags.stream().sorted().collect(Collectors.toList());
                repositoriesWithTaggedDependencies
                        .add(Utils.createMap(Utils.REPOSITORY_NAME, repository.get(Utils.REPOSITORY_NAME),
                                Utils.MCR_TAGS, orderedTagsList.toString().replace(", ", ",")));
            }
        });
        Utils.writeCSVFile(Utils.O_REPOSITORIES_WITH_TAGS_FILE, repositoriesWithTaggedDependencies);
    }

    /**
     * Selects all repositories out of the repositories with dependencies that
     * contain the dependencies in {@code dependencies}, have at most
     * {@code dependenciesLimit} dependencies and at most {@code filesLimit} Java
     * files. Writes them in the CSV file
     * {@value Utils#getSelectedRepositoriesFile(List)} with {@code dependencies} as
     * {@code List}.
     * 
     * 
     * @param dependencies
     * @param dependenciesLimit
     * @param filesLimit
     * @throws IOException
     */
    public static void selectRepositories(List<String> dependencies, int dependenciesLimit, int filesLimit)
            throws IOException {
        log.info("Selecting repositories with not more than " + dependenciesLimit + " dependencies and " + filesLimit
                + " files that contain the dependencies " + dependencies.toString() + "...");
        List<Map<String, String>> selection = new ArrayList<>();
        Utils.readCSVFile(Utils.O_REPOSITORIES_WITH_DEPENDENCIES_FILE).forEach(repository -> {
            List<String> dependenciesList = new ArrayList<>(
                    Arrays.asList(repository.get(Utils.DEPENDENCIES).replace("[", "").replace("]", "").split(",")));
            if (dependenciesList.size() <= dependenciesLimit && dependenciesList.containsAll(dependencies)) {
                int numberFiles;
                try {
                    numberFiles = request(Utils.GITHUB_API_URL + "search/code?q=extension:java+repo:"
                            + repository.get(Utils.REPOSITORY_NAME)).getAsJsonObject().get("total_count").getAsInt();
                } catch (IOException | InterruptedException e) {
                    numberFiles = 0;
                }
                if (numberFiles <= filesLimit) {
                    selection.add(Utils.createMap(Utils.REPOSITORY_NAME, repository.get(Utils.REPOSITORY_NAME)));
                }
            }
        });
        Utils.writeCSVFile(Utils.getSelectedRepositoriesFile(dependencies), selection);
        log.info("Selected " + selection.size() + " GitHub repositories.");
    }

    /**
     * Collects Java repositories that have at least {@code starsLimit} stars and
     * filters out repositories with less than {@code contributorsLimit}
     * contributors, less than {@code commitsLimit} commits and less than one POM
     * file.
     * 
     * @param starsLimit
     * @param contributorsLimit
     * @param commitsLimit
     * @return A list of the filtered repositories (contains their names)
     * @throws IOException
     * @throws InterruptedException
     */
    private static List<String> filterRepositories(int starsLimit, int contributorsLimit, int commitsLimit)
            throws IOException, InterruptedException {
        List<String> filteredRepositories = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            String url = Utils.GITHUB_API_URL + "search/repositories?q=language:java+stars:%3E=" + starsLimit
                    + "&sort=stars&order=asc&per_page=100&page=" + page;
            JsonObject repositories = request(url).getAsJsonObject();
            JsonArray items = repositories.get("items").getAsJsonArray();
            for (JsonElement e : items) {
                JsonObject repository = e.getAsJsonObject();
                String name = repository.get("full_name").getAsString();
                if (allRepositories.contains(name)) {
                    continue;
                }
                allRepositories.add(name);
                JsonArray contributors = request(
                        Utils.GITHUB_API_URL + "repos/" + name + "/contributors?per_page=" + contributorsLimit)
                                .getAsJsonArray();
                // Filter out repositories with not enough contributors
                if (contributors.size() < contributorsLimit) {
                    continue;
                }
                JsonArray commits = request(
                        Utils.GITHUB_API_URL + "repos/" + name + "/commits?per_page=" + commitsLimit).getAsJsonArray();
                // Filter out repositories with not enough commits
                if (commits.size() < commitsLimit) {
                    continue;
                }
                JsonObject pomFiles = request(
                        Utils.GITHUB_API_URL + "search/code?q=filename:pom.xml+extension:xml+repo:" + name)
                                .getAsJsonObject();
                // Filter out repositories with no POM file
                if (pomFiles.get("total_count").getAsInt() == 0) {
                    continue;
                }
                filteredRepositories.add(name);
            }
            int itemsSize = items.size();
            // The GitHub Search API provides up to 1000 results for each search, therefore
            // the query is split into smaller queries using recursion and the stars of a
            // repository
            if (page == 10 && itemsSize == 100) {
                starsLimit = items.get(99).getAsJsonObject().get("stargazers_count").getAsInt();
                filteredRepositories.addAll(filterRepositories(starsLimit, contributorsLimit, commitsLimit));
            }
        }
        return filteredRepositories;
    }

    /**
     * Returns a string version of the ordered list of the dependencies from the
     * {@code repositoryDependenciesList}.
     * 
     * @param repositoryDependenciesList
     * @return The string version of the ordered dependencies list
     */
    private static String createDependenciesString(Set<Map<String, String>> repositoryDependenciesList) {
        Set<String> repositoryDependencies = new HashSet<>();
        repositoryDependenciesList.forEach(dependency -> repositoryDependencies
                .add(dependency.get(Utils.GROUP_ID) + ":" + dependency.get(Utils.ARTIFACT_ID)));
        List<String> orderedDependencies = repositoryDependencies.stream().sorted().collect(Collectors.toList());
        return orderedDependencies.toString().replace(", ", ",");
    }

    /**
     * Filters out dependencies that cannot be resolved: groupId or artifactId is
     * null or groupId or artifactId contains one of {@code &#123;&$@}. Afterwards
     * returns the string version of the ordered list of the dependencies if there
     * are at least two dependencies.
     * 
     * @param repository
     * @return The string version of the ordered dependencies list or null if there
     *         are less than two dependencies in the list
     */
    private static String filterDependencies(Map<String, String> repository) {
        Set<String> dependencies = new HashSet<>();
        String[] dependenciesArray = repository.get(Utils.DEPENDENCIES).replace("[", "").replace("]", "").split(",");
        for (String dependency : dependenciesArray) {
            if (dependency.isBlank() || dependency.split(":")[0].equals("null")
                    || dependency.split(":")[1].equals("null") || dependency.contains("{") || dependency.contains("&")
                    || dependency.contains("$") || dependency.contains("@")) {
                continue;
            }
            dependencies.add(dependency);
        }
        if (dependencies.size() < 2) {
            return null;
        } else {
            List<String> orderedDependencies = dependencies.stream().sorted().collect(Collectors.toList());
            return orderedDependencies.toString().replace(", ", ",");
        }
    }

    /**
     * Creates an HttpURLConnection for the URL {@code url} using the authorization
     * {@code AUTHORIZATION}, collects the response and parses it using the
     * JsonParser.
     * 
     * @param url
     * @return The JsonElement of the parsed result
     * @throws IOException
     * @throws InterruptedException
     */
    private static JsonElement request(String url) throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("Authorization", AUTHORIZATION);
        try {
            InputStream response = connection.getInputStream();
            return JsonParser.parseReader(new InputStreamReader(response));
        } catch (Exception e) {
            log.info("--" + e.getMessage());
            log.info("--Sleeping...");
            Thread.sleep(60001);
            log.info("--Resuming.");
            return request(url);
        }
    }

}