package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Logger;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * This class contains the logic to download and manage a given GitHub
 * repository.
 */
public class RepositoryManager {

    private static final Logger log = Utils.getLogger();
    private final String repositoryName;
    private final Repository repository;
    private final RevCommit headRevision;

    /**
     * Constructor for a {@code RepositoryManager}. Clones the repository
     * {@code repositoryName} from GitHub using JGit.
     * 
     * @param repositoryName
     * @throws GitAPIException
     * @throws IOException
     */
    public RepositoryManager(String repositoryName) throws GitAPIException, IOException {
        log.info("Creating repository manager for " + repositoryName + "...");
        this.repositoryName = repositoryName;
        File gitDirectory = new File(Utils.T_GIT_FILES_DIR + this.repositoryName);
        if (!gitDirectory.exists()) {
            Git.cloneRepository().setURI("https://github.com/" + this.repositoryName + ".git").setBare(true)
                    .setDirectory(gitDirectory).call().close();
        }
        this.repository = new FileRepositoryBuilder().setGitDir(gitDirectory).setMustExist(true).build();
        ObjectId head = this.repository.resolve(Constants.HEAD);
        RevWalk revWalk = new RevWalk(this.repository);
        this.headRevision = revWalk.parseCommit(head);
        revWalk.close();
    }

    /**
     * Downloads all Java files from the repository and saves them to the
     * {@value Utils#T_JAVA_FILES_DIR} directory.
     * 
     * @throws IOException
     */
    public void downloadJavaFiles() throws IOException {
        log.info("Fetching Java files...");
        TreeWalk treeWalk = new TreeWalk(this.repository);
        treeWalk.addTree(headRevision.getTree());
        treeWalk.setRecursive(true);
        while (treeWalk.next()) {
            String path = treeWalk.getPathString();
            if (!path.endsWith(".java")) {
                continue;
            }
            ObjectLoader objectLoader = this.repository.open(treeWalk.getObjectId(0));
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            objectLoader.copyTo(outputStream);
            String content = IOUtils.toString(outputStream.toByteArray(), StandardCharsets.UTF_8.name());
            Path filePath = Path.of(Utils.T_JAVA_FILES_DIR + this.repositoryName + "/" + path);
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                Files.writeString(filePath, content, StandardCharsets.UTF_8);
            }
        }
        treeWalk.close();
    }

    /**
     * Collects the dependencies of this repository. If the flag
     * {@code collectApiCategories} is set, a {@code DependencyManager} is created
     * that resolves the identifiers of the dependencies in the POM files of the
     * repository and collects the MCR categories and MCR tags of the dependencies.
     * The results are written in the {@value Utils#getDependenciesFile(String)}
     * with {@code repositoryName} as {@code String}. If the flag
     * {@code downloadJars} is set, a {@code DependenciesDownloader} is created that
     * downloads the JAR files of the dependencies. If the flag
     * {@code collectApiCategories} was never set before, the flag
     * {@code downloadJars} will throw an error since
     * {@value Utils#getDependenciesFile(repositoryName)} is missing.
     * 
     * @param collectApiCategories
     * @param downloadJars
     * @return A list with the paths of the downloaded JAR files as strings
     * @throws IOException
     */
    public List<String> collectDependencies(boolean collectApiCategories, boolean downloadJars) throws IOException {
        if (!(collectApiCategories || downloadJars)) {
            log.info(
                    "Skipping the collection of dependencies. Assuming that dependencies.csv was already created and the dependencies were already downloaded.");
            Set<Map<String, String>> dependencies = new HashSet<>(
                    Utils.readCSVFile(Utils.getDependenciesFile(this.repositoryName)));
            return dependencies.stream().filter(dependency -> !dependency.get(Utils.PATH_TO_JAR).equals(""))
                    .map(dependency -> dependency.get(Utils.PATH_TO_JAR)).collect(Collectors.toList());
        }
        log.info("Starting the collection of dependencies...");
        if (collectApiCategories) {
            List<Model> models = readPOMFiles();
            DependenciesManager dependenciesManager = new DependenciesManager(this.repositoryName, models);
            Set<Map<String, String>> dependenciesIdentifiers = dependenciesManager.resolveIdentifiers();
            dependenciesManager.collectAPICategories(dependenciesIdentifiers);
        } else {
            log.info(
                    "--Skipping the collection of API categories. Assuming that dependencies.csv was already created.");
        }
        if (downloadJars) {
            DependenciesDownloader dependenciesDownloader = new DependenciesDownloader(this.repositoryName);
            dependenciesDownloader.download();
        } else {
            log.info(
                    "--Skipping the download of dependencies. Assuming that the dependencies were already downloaded.");
        }
        Set<Map<String, String>> dependencies = new HashSet<>(
                Utils.readCSVFile(Utils.getDependenciesFile(this.repositoryName)));
        List<String> jars = dependencies.stream().filter(dependency -> !dependency.get(Utils.PATH_TO_JAR).equals(""))
                .map(dependency -> dependency.get(Utils.PATH_TO_JAR)).collect(Collectors.toList());
        log.info("Finished collecting dependencies.");
        return jars;
    }

    /**
     * Resolves the dependencies of this repository for the
     * {@code RepositoriesPicker} by creating a {@code DependencyManager} that
     * resolves the identifiers of the dependencies in the POM files of the
     * repository.
     * 
     * @return A set of maps that contains the identifiers of the dependencies
     * @throws IOException
     */
    public Set<Map<String, String>> resolveDependencies() throws IOException {
        List<Model> models = readPOMFiles();
        DependenciesManager dependenciesManager = new DependenciesManager(this.repositoryName, models);
        return dependenciesManager.resolveIdentifiers();
    }

    /**
     * Deletes the cloned repository from the {@value Utils#T_GIT_FILES_DIR}
     * directory.
     */
    public void deleteRepository() {
        this.repository.close();
        File gitDirectory = new File(Utils.T_GIT_FILES_DIR + this.repositoryName);
        if (gitDirectory.exists()) {
            try {
                Utils.deleteFileRecursively(gitDirectory);
            } catch (Exception e) {
                log.warn("----Could not delete  \"" + Utils.getUnixPath(Path.of(gitDirectory.getPath()))
                        + "\" because of " + e + ".");
            }
        }
    }

    /**
     * Checks if the repository contains a Maven {@value Utils#SRC_MAIN_DIR}
     * directory for the {@code RepositoriesPicker}. Throws an error if something
     * went wrong reading the repository.
     * 
     * @return True if the repository contains a {@code src/main/java} directory,
     *         false otherwise
     * @throws IOException
     */
    public boolean isParsable() throws IOException {
        TreeWalk treeWalk = new TreeWalk(this.repository);
        treeWalk.addTree(headRevision.getTree());
        treeWalk.setRecursive(true);
        while (treeWalk.next()) {
            if (treeWalk.getPathString().contains(Utils.SRC_MAIN_DIR)) {
                treeWalk.close();
                return true;
            }
        }
        treeWalk.close();
        return false;
    }

    /**
     * Reads all POM files of the repository. Adds the model of each file to the
     * list {@code models}.
     * 
     * @return The list of models
     * @throws IOException
     */
    private List<Model> readPOMFiles() throws IOException {
        log.info("--Reading POM files...");
        List<Model> models = new ArrayList<>();
        TreeWalk treeWalk = new TreeWalk(this.repository);
        treeWalk.addTree(headRevision.getTree());
        treeWalk.setRecursive(true);
        while (treeWalk.next()) {
            String path = treeWalk.getPathString();
            if (!path.endsWith("pom.xml")) {
                continue;
            }
            ObjectStream objectStream = this.repository.open(treeWalk.getObjectId(0)).openStream();
            MavenXpp3Reader mavenXpp3Reader = new MavenXpp3Reader();
            try {
                Model model = mavenXpp3Reader.read(objectStream);
                models.add(model);
            } catch (XmlPullParserException | ArrayIndexOutOfBoundsException e) {
                log.warn("----Could not parse \"" + path + "\" because of " + e + ".");
            }
            objectStream.close();
        }
        treeWalk.close();
        return models;
    }

}
