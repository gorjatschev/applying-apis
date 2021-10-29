package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import org.apache.logging.log4j.Logger;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * This class contains the logic to collect and manage the dependencies from a
 * given repository.
 */
public class DependenciesManager {

    private static final Logger log = Utils.getLogger();
    private static final String MAVEN_ARTIFACT_URL = "https://mvnrepository.com/artifact/";
    private final String repositoryName;
    private final List<Model> models;
    private final Map<Model, List<Dependency>> dependencies = new LinkedHashMap<>();

    /**
     * Constructor for a {@code DependenciesManager}. Collects all dependency lists
     * from each model in the {@code models} list and puts them in the
     * {@code dependencies} map.
     * 
     * @param repositoryName
     * @param models
     */
    public DependenciesManager(String repositoryName, List<Model> models) {
        log.info("Creating dependencies manager...");
        this.repositoryName = repositoryName;
        this.models = models;
        this.models.forEach(model -> {
            this.dependencies.put(model, model.getDependencies());
            if (model.getDependencyManagement() != null) {
                this.dependencies.get(model).addAll(model.getDependencyManagement().getDependencies());
            }
        });
    }

    /**
     * Resolves the identifiers groupId, artifactId, and version of all dependencies
     * in all models.
     * 
     * @return A set of maps of dependency identifiers
     */
    public Set<Map<String, String>> resolveIdentifiers() {
        log.info("--Resolving identifiers (groupId, artifactId, version) of dependencies...");
        Set<Map<String, String>> dependenciesTuples = new HashSet<>();
        this.dependencies.forEach((model, modelDependencies) -> modelDependencies.forEach(dependency -> {
            if (dependency.getType().equals("jar")) {
                dependenciesTuples.add(Utils.createMap(Utils.GROUP_ID, getGroupId(dependency, model), Utils.ARTIFACT_ID,
                        getArtifactId(dependency, model), Utils.VERSION, getVersion(dependency, model)));
            }
        }));
        Set<Map<String, String>> cleanedDependenciesTuples = new HashSet<>(dependenciesTuples);
        dependenciesTuples.forEach(tuple -> {
            if (tuple.get(Utils.VERSION).equals("null")) {
                if (dependenciesTuples.stream()
                        .anyMatch(map -> map.get(Utils.GROUP_ID).equals(tuple.get(Utils.GROUP_ID))
                                && map.get(Utils.ARTIFACT_ID).equals(tuple.get(Utils.ARTIFACT_ID)))) {
                    cleanedDependenciesTuples.remove(tuple);
                }
            }
        });
        return cleanedDependenciesTuples;
    }

    /**
     * Gets the MCR categories and MCR tags of each dependency identifiers map in
     * the set {@code dependenciesIdentifiers} and writes them in the CSV file
     * {@value Utils#getDependenciesFile(String)} with {@code repositoryName} as
     * {@code String}.
     * 
     * @param dependenciesIdentifiers
     * @throws IOException
     */
    public void collectAPICategories(Set<Map<String, String>> dependenciesIdentifiers) throws IOException {
        log.info("--Collecting API categories (MCR categories and MCR tags)...");
        Set<Map<String, String>> dependenciesTuples = new HashSet<>();
        dependenciesIdentifiers.forEach(dependency -> dependenciesTuples.add(getMCRCategoriesAndMCRTags(dependency)));
        Utils.writeCSVFile(Utils.getDependenciesFile(this.repositoryName), new ArrayList<>(dependenciesTuples));
    }

    /**
     * Gets the MCR tags of each dependency identifiers map in the list
     * {@code dependenciesIdentifiers} for the {@code RepositoriesPicker}.
     * 
     * @param dependenciesIdentifiers
     * @return The list of maps of the dependencies with their MCR tags
     */
    public List<Map<String, String>> getMCRTags(List<Map<String, String>> dependenciesIdentifiers) {
        Set<Map<String, String>> dependenciesTuples = new HashSet<>();
        dependenciesIdentifiers.forEach(dependency -> {
            Map<String, String> categoriesAndTags = getMCRCategoriesAndMCRTags(dependency);
            if (!categoriesAndTags.get(Utils.MCR_TAGS).isBlank()
                    && !categoriesAndTags.get(Utils.MCR_TAGS).equals("null")) {
                dependenciesTuples.add(Utils.createMap(Utils.GROUP_ID, categoriesAndTags.get(Utils.GROUP_ID),
                        Utils.ARTIFACT_ID, categoriesAndTags.get(Utils.ARTIFACT_ID), Utils.MCR_TAGS,
                        categoriesAndTags.get(Utils.MCR_TAGS)));
            }
        });
        return new ArrayList<>(dependenciesTuples);
    }

    /***
     * Gets the {@code groupId} of the given {@code dependency} from the
     * {@code model}. If the {@code groupId} is a variable, looks up the model and
     * parent {@code groupId} and the model's and the parent's properties.
     * 
     * @param dependency
     * @param model
     * @return The found {@code groupId} or "null" otherwise
     */
    private String getGroupId(Dependency dependency, Model model) {
        String groupId = dependency.getGroupId();
        if (groupId == null) {
            return "null";
        }
        boolean isVariable = groupId.startsWith("${") && groupId.endsWith("}");
        if (isVariable && groupId.equals("${project.groupId}")) {
            groupId = model.getGroupId();
            if (groupId == null) {
                groupId = model.getParent().getGroupId();
            }
        } else if (isVariable) {
            String variable = groupId.substring(2, groupId.length() - 1);
            groupId = model.getProperties().getProperty(variable);
            if (groupId == null && model.getParent() != null) {
                groupId = searchInParentProperties(variable, model.getParent());
            }
        }
        if (groupId == null) {
            return "null";
        }
        return groupId;
    }

    /***
     * Gets the {@code artifactId} of the given {@code dependency} from the
     * {@code model}. If the {@code artifactId} is a variable, looks up the model
     * {@code artifactId}, and the model's and the parent's properties.
     * 
     * @param dependency
     * @param model
     * @return The found {@code artifactId} or "null" otherwise
     */
    private String getArtifactId(Dependency dependency, Model model) {
        String artifactId = dependency.getArtifactId();
        if (artifactId == null) {
            return "null";
        }
        boolean isVariable = artifactId.startsWith("${") && artifactId.endsWith("}");
        if (isVariable && artifactId.equals("${project.artifactId}")) {
            artifactId = model.getArtifactId();
        } else if (isVariable) {
            String variable = artifactId.substring(2, artifactId.length() - 1);
            artifactId = model.getProperties().getProperty(variable);
            if (artifactId == null && model.getParent() != null) {
                artifactId = searchInParentProperties(variable, model.getParent());
            }
        }
        if (artifactId == null) {
            return "null";
        }
        return artifactId;
    }

    /***
     * Gets the {@code version} of the given {@code dependency} from the
     * {@code model}. If the {@code version} is a variable, looks up the model and
     * parent {@code version}, and the model's and the parent's properties.
     * Otherwise, if the {@code version} is null, looks up the parent dependencies
     * for a {@code version} of this dependency.
     * 
     * @param dependency
     * @param model
     * @return The found {@code version} or "null" otherwise
     */
    private String getVersion(Dependency dependency, Model model) {
        String version = dependency.getVersion();
        if (version != null) {
            boolean isVariable = version.startsWith("${") && version.endsWith("}");
            if (isVariable && version.equals("${project.version}")) {
                version = model.getVersion();
                if (version == null && model.getParent() != null) {
                    version = model.getParent().getVersion();
                }
            } else if (isVariable) {
                String variable = version.substring(2, version.length() - 1);
                version = model.getProperties().getProperty(variable);
                if (version == null && model.getParent() != null) {
                    version = searchInParentProperties(variable, model.getParent());
                }
            }
        } else {
            if (model.getParent() != null) {
                version = searchInParentDependencies(dependency, model);
            }
        }
        if (version == null) {
            version = "null";
        }
        return version;
    }

    /**
     * Searches for the string {@code variable} in the properties of the
     * {@code parent}.
     * 
     * @param variable
     * @param parent
     * @return The value of the property with the key {@code variable}.
     */
    private String searchInParentProperties(String variable, Parent parent) {
        String parentId = parent.getId();
        Optional<Model> foundParent = this.models.stream().filter(model -> model.getId().equals(parentId)).findFirst();
        if (foundParent.isPresent()) {
            return foundParent.get().getProperties().getProperty(variable);
        }
        return null;
    }

    /**
     * Searches for the {@code dependency} in the dependencies of the parent of the
     * {@code model} and returns its {@code version}.
     * 
     * @param dependency
     * @param model
     * @return The version of the found dependency
     */
    private String searchInParentDependencies(Dependency dependency, Model model) {
        String groupId = getGroupId(dependency, model);
        String artifactId = getArtifactId(dependency, model);
        if (!(groupId.equals("null") || artifactId.equals("null"))) {
            String parentId = model.getParent().getId();
            Optional<Model> foundParent = this.models.stream().filter(m -> m.getId().equals(parentId)).findFirst();
            if (foundParent.isPresent()) {
                Model parentModel = foundParent.get();
                Optional<Dependency> parentDependency = dependencies.get(parentModel).stream()
                        .filter(d -> groupId.equals(getGroupId(d, parentModel))
                                && artifactId.equals(getArtifactId(d, parentModel)))
                        .findFirst();
                if (parentDependency.isPresent()) {
                    return getVersion(parentDependency.get(), parentModel);
                }
            }
        }
        return null;
    }

    /**
     * Gets the MCR categories and MCR tags of the {@code dependency} from the Maven
     * repository site.
     * 
     * @param dependency
     * @return The map of the {@code dependency} extended with the MCR categories
     *         and MCR tags.
     */
    private static Map<String, String> getMCRCategoriesAndMCRTags(Map<String, String> dependency) {
        Map<String, String> dependenciesTuples = new HashMap<>();
        String url = MAVEN_ARTIFACT_URL + dependency.get(Utils.GROUP_ID) + "/" + dependency.get(Utils.ARTIFACT_ID);
        try {
            log.info("----Connecting to \"" + url + "\"...");
            Document doc = request(url);
            Elements mcrCategories = doc.select("a.b.c");
            StringJoiner mcrCategoriesJoiner = new StringJoiner(",");
            mcrCategories.forEach(mcrCategory -> mcrCategoriesJoiner.add(mcrCategory.text()));
            Elements mcrTags = doc.select("a.b.tag");
            StringJoiner mcrTagsJoiner = new StringJoiner(",");
            mcrTags.forEach(mcrTag -> mcrTagsJoiner.add(mcrTag.text()));
            dependenciesTuples = Utils.extendMap(dependency, Utils.MCR_CATEGORIES, mcrCategoriesJoiner.toString(),
                    Utils.MCR_TAGS, mcrTagsJoiner.toString());
        } catch (IOException e) {
            log.warn("----Could not connect to \"" + url + "\" because of " + e + ".");
            dependenciesTuples = Utils.extendMap(dependency, Utils.MCR_CATEGORIES, "null", Utils.MCR_TAGS, "null");
        }
        return dependenciesTuples;
    }

    /**
     * Creates an HttpURLConnection for the URL {@code url}, collects the response
     * and parses it using jsoup.
     * 
     * @param url
     * @return The document corresponding to the parsed result
     * @throws IOException
     */
    private static Document request(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla");
        InputStream response = connection.getInputStream();
        return Jsoup.parse(response, null, url);
    }

}
