package de.uni_koblenz.gorjatschev.applyingapis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Logger;

/**
 * This class contains the logic to allocate dependencies to API usages in a
 * repository, i.e., assign APIs.
 */
public class DependenciesAllocator {

    private static final Logger log = Utils.getLogger();
    private final String repositoryName;
    List<Map<String, String>> dependenciesClasses = new ArrayList<>();
    private final List<Map<String, String>> srcClasses;

    /**
     * Constructor for a {@code DependenciesAllocator}. Gets the JARs from the
     * dependencies of the given repository and adds all their contained classes to
     * {@code dependenciesClasses}. Adds all {@code parsedJavaFiles} to
     * {@code srcClasses}.
     * 
     * @param repositoryName
     * @param parsedJavaFiles
     * @throws IOException
     */
    public DependenciesAllocator(String repositoryName, List<Map<String, String>> parsedJavaFiles) throws IOException {
        log.info("Creating dependencies allocator...");
        this.repositoryName = repositoryName;
        List<Map<String, String>> dependencies = Utils.readCSVFile(Utils.getDependenciesFile(this.repositoryName));
        dependencies.stream().filter(dependency -> !dependency.get(Utils.PATH_TO_JAR).equals(""))
                .forEach(dependency -> this.dependenciesClasses.addAll(getAllClassesInJar(dependency)));
        this.srcClasses = getAllClassesInSrc(parsedJavaFiles);
    }

    /**
     * Assigns the APIs to the API usages contained in the CSV file
     * {@value Utils#getDataFile(repositoryName)}. Writes them in the same CSV file.
     * 
     * @throws IOException
     */
    public void assignAPIs() throws IOException {
        log.info("Assigning APIs...");
        List<Map<String, String>> data = Utils.readCSVFile(Utils.getDataFile(this.repositoryName));
        List<Map<String, String>> newData = new ArrayList<>();
        data.stream().forEach(tuple -> newData.add(Utils.extendMap(tuple, assignAPI(tuple))));
        Utils.writeCSVFile(Utils.getDataFile(this.repositoryName), newData);
        log.info("Assigning of APIs finished.");
    }

    /**
     * Gets the JARs from the dependencies and returns all their contained classes
     * with additional information.
     * 
     * @param dependency
     * @return A list of maps of the contained classes
     */
    private static List<Map<String, String>> getAllClassesInJar(Map<String, String> dependency) {
        List<Map<String, String>> jarClasses = new ArrayList<>();
        String api = dependency.get(Utils.GROUP_ID) + ":" + dependency.get(Utils.ARTIFACT_ID);
        try (JarFile jarFile = new JarFile(dependency.get(Utils.PATH_TO_JAR))) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                if (!jarEntry.isDirectory() && jarEntry.getName().endsWith(".class")) {
                    String className = jarEntry.getName().substring(0, jarEntry.getName().length() - ".class".length())
                            .replace('/', '.').replace('$', '.');
                    jarClasses.add(Utils.createMap("groupId:artifactId", api, Utils.CLASS_NAME, className,
                            Utils.MCR_CATEGORIES, dependency.get(Utils.MCR_CATEGORIES), Utils.MCR_TAGS,
                            dependency.get(Utils.MCR_TAGS)));
                }
            }
        } catch (IOException e) {
            log.warn("--Could not read \"" + dependency.get(Utils.PATH_TO_JAR) + "\" because of "
                    + e.getClass().getName() + ".");
        }
        return jarClasses;
    }

    /**
     * Returns all classes with their source directory roots contained in
     * {@code parsedJavaFiles}.
     * 
     * @param parsedJavaFiles
     * @return A list of maps of the contained classes
     */
    private static List<Map<String, String>> getAllClassesInSrc(List<Map<String, String>> parsedJavaFiles) {
        List<Map<String, String>> srcClasses = new ArrayList<>();
        parsedJavaFiles.forEach(tuple -> {
            String srcRoot = tuple.get(Utils.SRC_ROOT);
            String filePath = tuple.get(Utils.FILE_PATH);
            String className = filePath.substring(0, filePath.length() - ".java".length()).replace(srcRoot + "/", "")
                    .replace('/', '.');
            srcClasses.add(Utils.createMap(Utils.SRC_ROOT, srcRoot, Utils.CLASS_NAME, className));
        });
        return srcClasses;
    }

    /**
     * Tries to remove an inner class from the full class path. The last element of
     * a package path is considered to be an inner class if the next-to-last element
     * starts with an uppercase character.
     * 
     * @param className
     * @return The shortened full class path without the inner class
     */
    private static String tryRemovingInnerClass(String className) {
        int indexOfLastDot = className.lastIndexOf(".");
        try {
            if (indexOfLastDot != -1) {
                String classNameShortened = className.substring(0, indexOfLastDot);
                int indexOfSecondLastDot = classNameShortened.lastIndexOf(".");
                if (indexOfSecondLastDot != -1) {
                    if (Character.isUpperCase(classNameShortened.charAt(indexOfSecondLastDot + 1))) {
                        return classNameShortened;
                    }
                } else {
                    if (Character.isUpperCase(classNameShortened.charAt(0))) {
                        return classNameShortened;
                    }
                }
            }
        } catch (StringIndexOutOfBoundsException e) {
        }
        return null;
    }

    /**
     * Assigns an API to the API usage contained in the {@code tuple}. Assigns
     * "false (JRE)", if the full class path starts with "java." or "javax.".
     * Assigns "false (null)", if the class usage was not resolved ("null"). Assigns
     * "false (src)", if the full class path is a class contained in the current
     * source directory. Assigns "true" and the corresponding API, if the full class
     * path is a class that is part of a dependency.
     * 
     * @param tuple
     * @return The assigned API and additional information
     */
    private String[] assignAPI(Map<String, String> tuple) {
        String usedClassOfElement = tuple.get(Utils.USED_CLASS_OF_ELEMENT);
        String[] output = new String[] { "isAPIClass", "", Utils.API, "", Utils.MCR_CATEGORIES, "", Utils.MCR_TAGS,
                "" };
        if (usedClassOfElement.startsWith("java.") || usedClassOfElement.startsWith("javax.")) {
            output[1] = "false (JRE)";
        } else if (usedClassOfElement.startsWith("null")) {
            output[1] = "false (null)";
        } else {
            String filePath = tuple.get(Utils.FILE_PATH);
            List<Map<String, String>> srcClassesInSameSrc = this.srcClasses.stream()
                    .filter(srcClass -> filePath.startsWith(srcClass.get(Utils.SRC_ROOT))
                            || (filePath.contains(Utils.SRC_TEST_DIR) && filePath.startsWith(
                                    srcClass.get(Utils.SRC_ROOT).replace(Utils.SRC_MAIN_DIR, Utils.SRC_TEST_DIR))))
                    .collect(Collectors.toList());
            boolean isInSameSrc = srcClassesInSameSrc.stream()
                    .anyMatch(srcClass -> usedClassOfElement.startsWith(srcClass.get(Utils.CLASS_NAME)));
            if (isInSameSrc) {
                output[1] = "false (src)";
            } else {
                Optional<Map<String, String>> apiMatch = this.dependenciesClasses.stream().filter(
                        dependenciesClass -> dependenciesClass.get(Utils.CLASS_NAME).contains(usedClassOfElement))
                        .findFirst();
                if (apiMatch.isPresent()) {
                    output[1] = "true";
                    output[3] = apiMatch.get().get("groupId:artifactId");
                    output[5] = apiMatch.get().get(Utils.MCR_CATEGORIES);
                    output[7] = apiMatch.get().get(Utils.MCR_TAGS);
                } else {
                    output[1] = "null";
                    String usedClassOfElementShortened = tryRemovingInnerClass(usedClassOfElement);
                    if (usedClassOfElementShortened != null) {
                        Optional<Map<String, String>> apiMatchShortened = this.dependenciesClasses.stream()
                                .filter(dependenciesClass -> dependenciesClass.get(Utils.CLASS_NAME)
                                        .contains(usedClassOfElementShortened))
                                .findFirst();
                        if (apiMatchShortened.isPresent()) {
                            output[1] = "true";
                            output[3] = apiMatchShortened.get().get("groupId:artifactId");
                            output[5] = apiMatchShortened.get().get(Utils.MCR_CATEGORIES);
                            output[7] = apiMatchShortened.get().get(Utils.MCR_TAGS);
                        }
                    }
                }
            }
        }
        return output;
    }

}
