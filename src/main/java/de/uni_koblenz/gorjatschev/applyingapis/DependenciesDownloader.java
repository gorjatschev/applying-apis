package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Logger;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.version.Version;

/**
 * This class contains the logic to download the dependencies from a given
 * repository.
 */
public class DependenciesDownloader {

    private static final Logger log = Utils.getLogger();
    private final String repositoryName;

    /**
     * Constructor for a {@code DependenciesDownloader}.
     * 
     * @param repositoryName
     */
    public DependenciesDownloader(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    /**
     * Downloads the dependencies from the CSV file
     * {@value Utils#getDependenciesFile(repositoryName)}. Writes download
     * information in the same CSV file.
     * 
     * @throws IOException
     */
    public void download() throws IOException {
        log.info("--Downloading dependencies (JAR files)...");
        Set<Map<String, String>> newDependencies = new HashSet<>();
        Set<Map<String, String>> dependencies = new HashSet<>(
                Utils.readCSVFile(Utils.getDependenciesFile(this.repositoryName)));
        dependencies.forEach(dependency -> {
            String groupId = dependency.get(Utils.GROUP_ID);
            String artifactId = dependency.get(Utils.ARTIFACT_ID);
            String version = dependency.get(Utils.VERSION);
            Map<String, String> download = downloadDependency(groupId, artifactId, version);
            String url = download.get("path").equals("") ? "" : Utils.MAVEN_DOWNLOAD_URL + download.get("path");
            String pathToJAR = download.get("path").equals("") ? ""
                    : Utils.T_DEPENDENCIES_DIR + this.repositoryName + "/" + download.get("path");
            newDependencies.add(
                    Utils.extendMap(dependency, Utils.DOWNLOADED, download.get(Utils.DOWNLOADED), Utils.DL_VERS_IF_DIFF,
                            download.get(Utils.DL_VERS_IF_DIFF), Utils.JAR_URL, url, Utils.PATH_TO_JAR, pathToJAR));
        });
        Utils.writeCSVFile(Utils.getDependenciesFile(this.repositoryName), new ArrayList<>(newDependencies));
        log.info("--Finished downloading dependencies.");
    }

    /**
     * Downloads the dependency with the given {@code groupId}, {@code artifactId},
     * and {@code version}. Removes a possible "-SNAPSHOT" from the {@code version}
     * and tries to download the given {@code version}. If the {@code version} to
     * download is still "null", finds the newest {@code version} of this dependency
     * and tries to download it. Returns the download information for the given
     * dependency.
     * 
     * @param groupId
     * @param artifactId
     * @param version
     * @return The download information for the given dependency
     */
    private Map<String, String> downloadDependency(String groupId, String artifactId, String version) {
        String versionToDownload = version;
        if (groupId.equals("null") || artifactId.equals("null")) {
            log.warn("----Skipping the download of \"" + groupId + ":" + artifactId + ":" + version + "\".");
            return Utils.createMap(Utils.DOWNLOADED, "false", Utils.DL_VERS_IF_DIFF, "", "path", "");
        }
        boolean success = false;
        log.info("----Trying to download \"" + groupId + ":" + artifactId + ":" + version + "\"...");
        if (!versionToDownload.equals("null")) {
            if (versionToDownload.endsWith("-SNAPSHOT")) {
                versionToDownload = versionToDownload.substring(0, versionToDownload.length() - 9);
            }
            if (!versionToDownload.equals("null")) {
                String path = generatePath(groupId, artifactId, versionToDownload);
                success = tryDownload(path);
                versionToDownload = success ? versionToDownload : "null";
            }
        }
        if (versionToDownload.equals("null")) {
            try {
                log.warn("------Cannot download \"" + groupId + ":" + artifactId + ":" + version
                        + "\". Trying the newest version instead.");
                versionToDownload = findNewestVersion(groupId, artifactId);
            } catch (VersionRangeResolutionException e) {
                versionToDownload = "null";
            }
            if (!versionToDownload.equals("null")) {
                String path = generatePath(groupId, artifactId, versionToDownload);
                success = tryDownload(path);
                versionToDownload = success ? versionToDownload : "null";
            }
            if (versionToDownload.equals("null")) {
                log.warn(
                        "------Skipping download of \"" + groupId + ":" + artifactId + ":" + versionToDownload + "\".");
            }
        }
        String downloaded = String.valueOf(success);
        String dlVersIfDiff = versionToDownload.equals(version) ? "" : versionToDownload;
        String path = success ? generatePath(groupId, artifactId, versionToDownload) : "";
        return Utils.createMap(Utils.DOWNLOADED, downloaded, Utils.DL_VERS_IF_DIFF, dlVersIfDiff, "path", path);
    }

    /**
     * Tries to download the dependency converted in the string {@code path}.
     * 
     * @param path
     * @return True if the download was successful, false otherwise
     */
    private boolean tryDownload(String path) {
        boolean success = false;
        String url = Utils.MAVEN_DOWNLOAD_URL + path;
        try {
            File file = new File(Utils.T_DEPENDENCIES_DIR + this.repositoryName + "/" + path);
            if (!file.exists()) {
                log.info("------Downloading \"" + url + "\"...");
                FileUtils.copyURLToFile(new URL(url), file);
            } else {
                log.info("------Already downloaded \"" + url + "\".");
            }
            success = true;
        } catch (IOException e) {
            log.warn("------Could not download from \"" + url + "\" because of " + e.getClass().getName() + ".");
        }
        return success;
    }

    /**
     * Tries to find the newest version of the dependency defined by {@code groupId}
     * and {@code artifactId}.
     * 
     * @param groupId
     * @param artifactId
     * @return The newest version if found, "null" otherwise
     * @throws VersionRangeResolutionException
     */
    private String findNewestVersion(String groupId, String artifactId) throws VersionRangeResolutionException {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        RepositorySystem repositorySystem = locator.getService(RepositorySystem.class);
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepository = new LocalRepository(
                Utils.T_DEPENDENCIES_REPOS_DIR + this.repositoryName + "/");
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepository));
        RemoteRepository central = new RemoteRepository.Builder("central", "default",
                "https://repo.maven.apache.org/maven2/").build();

        VersionRangeRequest rangeRequest = new VersionRangeRequest();
        rangeRequest.setArtifact(new DefaultArtifact(groupId + ":" + artifactId + ":[0,)"));
        rangeRequest.addRepository(central);
        VersionRangeResult versionRangeResult = repositorySystem.resolveVersionRange(session, rangeRequest);
        Version newestVersion = versionRangeResult.getHighestVersion();
        if (newestVersion == null) {
            return "null";
        }
        return newestVersion.toString();
    }

    /**
     * Generates a URL download path for the dependency defined by {@code groupId},
     * {@code artifactId}, and {@code version}.
     * 
     * @param groupId
     * @param artifactId
     * @param version
     * @return The generated string
     */
    private static String generatePath(String groupId, String artifactId, String version) {
        return groupId.replace(".", "/") + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }
}
