/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.maven;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.maven.support.JsonSchema;
import io.fabric8.maven.support.JsonSchemas;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.utils.Files;
import io.fabric8.utils.GitHelpers;
import io.fabric8.utils.Objects;
import io.fabric8.utils.Strings;
import io.fabric8.utils.Systems;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static io.fabric8.utils.PropertiesHelper.findPropertiesWithPrefix;
import static io.fabric8.utils.PropertiesHelper.toMap;

/**
 * Abstract base class for Fabric8 based Mojos
 */
public abstract class AbstractFabric8Mojo extends AbstractNamespacedMojo {

    private static final String DEFAULT_CONFIG_FILE_NAME = "kubernetes.json";
    public static String[] ICON_EXTENSIONS = new String[]{".svg", ".png", ".gif", ".jpg", ".jpeg"};

    /**
     * Name of the created app zip file
     */
    @Parameter(property = "fabric8.zip.file", defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}-app.zip")
    protected File zipFile;

    /**
     * The folder used for defining project specific files
     */
    @Parameter(property = "fabric8.source.dir", defaultValue = "${basedir}/src/main/fabric8")
    protected File appConfigDir;

    /**
     * Provides the resource name of the icon to use; found using the current classpath (including the ones shipped inside the maven plugin).
     * <p/>
     * You can refer to a common set of icons by setting this option to a value of:
     * <ul>
     *     <li>activemq</li>
     *     <li>camel</li>
     *     <li>java</li>
     *     <li>jetty</li>
     *     <li>karaf</li>
     *     <li>mule</li>
     *     <li>spring-boot</li>
     *     <li>tomcat</li>
     *     <li>tomee</li>
     *     <li>weld</li>
     *     <li>wildfly</li>
     * </ul>
     */
    @Parameter(property = "fabric8.iconRef")
    protected String iconRef;

    /**
     * The generated kubernetes JSON file
     */
    @Parameter(property = "fabric8.json.target", defaultValue = "${basedir}/target/classes/kubernetes.json")
    private File kubernetesJson;

    /**
     * The source kubernetes JSON file
     */
    @Parameter(property = "fabric8.json.source", defaultValue = "${basedir}/src/main/fabric8/kubernetes.json")
    protected File kubernetesSourceJson;

    /**
     * Whether we should combine kubernetes JSON dependencies on the classpath into the generated JSON
     */
    @Parameter(property = "fabric8.combineDependencies", defaultValue = "false")
    protected boolean combineDependencies;


    /**
     * Should we exclude OpenShift templates and any extensions like OAuthConfigs in the generated or combined JSON?
     */
    @Parameter(property = "fabric8.pureKubernetes", defaultValue = "false")
    protected boolean pureKubernetes;

    /**
     * The number of replicas of this container if we are auto generating the kubernetes JSON file (creating
     * a <a href="http://fabric8.io/v2/replicationControllers.html">Replication Controller</a> if this value
     * is greater than 0 or a <a href="http://fabric8.io/v2/pods.html">pod</a> if not).
     */
    @Parameter(property = "fabric8.replicas", defaultValue = "1")
    private Integer replicas;

    /**
     * Whether or not we should ignoreProject this maven project from goals like fabric8:deploy
     */
    @Parameter(property = "fabric8.ignoreProject", defaultValue = "false")
    private boolean ignoreProject;

    /**
     * The properties file used to specify environment variables which allows ${FOO_BAR} expressions to be used
     * without any Maven property expansion
     */
    @Parameter(property = "fabric8.envProperties", defaultValue = "${basedir}/src/main/fabric8/env.properties")
    protected File envPropertiesFile;


    /**
     * Specifies a file which maps environment variables or system properties to annotations which are then recorded on the
     * ReplicationController of the generated or applied JSON
     */
    @Parameter(property = "fabric8.environmentVariableToAnnotationsFile", defaultValue = "${basedir}/src/main/fabric8/environemntToAnnotations.properties")
    protected File environmentVariableToAnnotationsFile;


    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /**
     * Files to be excluded
     */
    @Parameter(property = "fabric8.excludedFiles", defaultValue = "io.fabric8.agent.properties")
    private String[] filesToBeExcluded;

    protected static File copyReadMe(File src, File appBuildDir) throws IOException {
        File[] files = src.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase(Locale.ENGLISH).startsWith("readme.");
            }
        });
        if (files != null && files.length == 1) {
            File readme = files[0];
            File outFile = new File(appBuildDir, readme.getName());
            Files.copy(readme, outFile);
            return outFile;
        }

        return null;
    }

    protected static Object loadJsonFile(File file) throws MojoExecutionException {
        try {
            return KubernetesHelper.loadJson(file);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to parse JSON " + file + ". " + e, e);
        }
    }

    public MavenProject getProject() {
        return project;
    }

    protected static URLClassLoader createURLClassLoader(Collection<URL> jars) {
        return new URLClassLoader(jars.toArray(new URL[jars.size()]));
    }

    public File getKubernetesJson() {
        return kubernetesJson;
    }


    public Integer getReplicas() {
        return replicas;
    }

    public boolean isIgnoreProject() {
        return ignoreProject;
    }

    public File getZipFile() {
        return zipFile;
    }

    /**
     * Returns true if this project is a pom packaging project
     */
    protected boolean isPom(MavenProject reactorProject) {
        return "pom".equals(reactorProject.getPackaging());
    }

    protected InputStream loadPluginResource(String iconRef) throws MojoExecutionException {
        InputStream answer = Thread.currentThread().getContextClassLoader().getResourceAsStream(iconRef);
        if (answer == null) {
            answer = getTestClassLoader().getResourceAsStream(iconRef);
        }
        if (answer == null) {
            answer = this.getClass().getResourceAsStream(iconRef);
        }
        return answer;
    }

    protected URLClassLoader getCompileClassLoader() throws MojoExecutionException {
        try {
            List<String> classpathElements = getProject().getCompileClasspathElements();
            return createClassLoader(classpathElements, getProject().getBuild().getOutputDirectory());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to resolve classpath: " + e, e);
        }
    }

    protected URLClassLoader getTestClassLoader() throws MojoExecutionException {
        try {
            List<String> classpathElements = getProject().getTestClasspathElements();
            return createClassLoader(classpathElements, getProject().getBuild().getTestOutputDirectory());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to resolve classpath: " + e, e);
        }
    }

    protected URLClassLoader createClassLoader(List<String> classpathElements, String... paths) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (String path : paths) {
            URL url = pathToUrl(path);
            urls.add(url);
        }
        for (Object object : classpathElements) {
            if (object != null) {
                String path = object.toString();
                URL url = pathToUrl(path);
                urls.add(url);
            }
        }
        getLog().debug("Creating class loader from: " + urls);
        return createURLClassLoader(urls);
    }

    private URL pathToUrl(String path) throws MalformedURLException {
        File file = new File(path);
        return file.toURI().toURL();
    }

    protected boolean hasConfigDir() {
        return appConfigDir.isDirectory();
    }

    protected boolean isPomProject() {
        return isPom(getProject());
    }

    protected void addEnvironmentAnnotations(File json) throws MojoExecutionException {
        try {
            Object dto = loadJsonFile(json);
            if (dto instanceof KubernetesList) {
                KubernetesList container = (KubernetesList) dto;
                List<HasMetadata> items = container.getItems();
                addEnvironmentAnnotations(items);
                getLog().info("Added environment annotations:");
                printSummary(items);
                container.setItems(items);
                KubernetesHelper.saveJson(json, container);
            } else if (dto instanceof Template) {
                Template container = (Template) dto;
                List<HasMetadata> items = container.getObjects();
                addEnvironmentAnnotations(items);
                getLog().info("Added environment annotations:");
                printSummary(items);
                container.setObjects(items);
                getLog().info("Template is now:");
                printSummary(container.getObjects());
                KubernetesHelper.saveJson(json, container);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to updated JSON file " + json + ". " + e, e);
        }
    }

    protected void addEnvironmentAnnotations(Iterable<HasMetadata> items) throws MojoExecutionException {
        if (items != null) {
            for (HasMetadata item : items) {
                if (item instanceof KubernetesList) {
                    KubernetesList list = (KubernetesList) item;
                    addEnvironmentAnnotations(list.getItems());
                } else if (item instanceof Template) {
                    Template template = (Template) item;
                    addEnvironmentAnnotations(template.getObjects());
                } else if (item instanceof ReplicationController) {
                    addEnvironmentAnnotations(item);
                } else if (item instanceof DeploymentConfig) {
                    addEnvironmentAnnotations(item);
                }
            }
        }
    }

    protected void addEnvironmentAnnotations(HasMetadata resource) throws MojoExecutionException {
        Map<String, String> mapEnvVarToAnnotation = new HashMap<>();
        String resourceName = "environmentAnnotations.properties";
        URL url = getClass().getResource(resourceName);
        if (url == null) {
            throw new MojoExecutionException("Cannot find resource `" + resourceName + "` on the classpath!");
        }
        addPropertiesFileToMap(url, mapEnvVarToAnnotation);
        addPropertiesFileToMap(this.environmentVariableToAnnotationsFile, mapEnvVarToAnnotation);

        Map<String, String> annotations = KubernetesHelper.getOrCreateAnnotations(resource);
        Set<Map.Entry<String, String>> entries = mapEnvVarToAnnotation.entrySet();
        for (Map.Entry<String, String> entry : entries) {
            String envVar = entry.getKey();
            String annotation = entry.getValue();

            if (Strings.isNotBlank(envVar) && Strings.isNotBlank(annotation)) {
                String value = Systems.getEnvVarOrSystemProperty(envVar);
                if (Strings.isNotBlank(value)) {
                    String oldValue = annotations.get(annotation);
                    if (Strings.isNotBlank(oldValue)) {
                        getLog().warn("Not adding annotation `" + annotation + "` to " + KubernetesHelper.getKind(resource) + " " + KubernetesHelper.getName(resource) + " with value `" + value + "` as there is already an annotation value of `" + oldValue + "`");
                    } else {
                        annotations.put(annotation, value);
                    }
                }
            }
        }
    }


    /**
     * Creates an Iterable to walk the exception from the bottom up
     * (the last caused by going upwards to the root exception).
     *
     * @see java.lang.Iterable
     * @param exception  the exception
     * @return the Iterable
     */
    protected static Iterable<Throwable> createExceptionIterable(Throwable exception) {
        List<Throwable> throwables = new ArrayList<Throwable>();

        Throwable current = exception;
        // spool to the bottom of the caused by tree
        while (current != null) {
            throwables.add(current);
            current = current.getCause();
        }
        Collections.reverse(throwables);

        return throwables;
    }

    protected Repository getGitRepository(File basedir, String envVarName) {
        try {
            File gitFolder = GitHelpers.findGitFolder(basedir);
            if (gitFolder == null) {
                getLog().warn("Could not find .git folder based on the current basedir of " + basedir);
                return null;
            }
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            Repository repository = builder
                    .readEnvironment()
                    .setGitDir(gitFolder)
                    .build();
            if (repository == null) {
                getLog().warn("Cannot create default value for $" + envVarName + " as no .git/config file could be found");
            }
            return repository;
        } catch (Exception e) {
            getLog().warn("Failed to initialise Git Repository: " + e, e);
            return null;
        }
    }

    protected boolean shouldGenerateForThisProject() {
        return !isPomProject() || hasConfigDir();
    }

    /**
     * Returns all the environment variable properties defined in the pom.xml which are prefixed with "fabric8.env."
     */
    public Map<String, String> getEnvironmentVariableProperties() throws MojoExecutionException {
        Map<String, String> rawProperties = findPropertiesWithPrefix(getProject().getProperties(), "fabric8.env.", Strings.toEnvironmentVariableFunction());
        Set<Map.Entry<String, String>> entries = rawProperties.entrySet();
        Map<String, String>  answer = new HashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String key = entry.getKey();
            String value = entry.getValue();
            value = unquoteTemplateExpression(value);
            answer.put(key, value);
        }
        addPropertiesFileToMap(this.envPropertiesFile, answer);
        return answer;
    }

    protected static void addPropertiesFileToMap(File file, Map<String, String> answer) throws MojoExecutionException {
        if (file != null && file.isFile() && file.exists()) {
            try (FileInputStream in = new FileInputStream(file)){
                Properties properties = new Properties();
                properties.load(in);
                Map<String, String> map = toMap(properties);
                answer.putAll(map);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to load properties file: " + file + ". " + e, e);
            }
        }
    }

    protected static void addPropertiesFileToMap(URL url, Map<String, String> answer) throws MojoExecutionException {
        if (url != null) {
            try (InputStream in = url.openStream()) {
                Properties properties = new Properties();
                properties.load(in);
                Map<String, String> map = toMap(properties);
                answer.putAll(map);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to load properties URL: " + url + ". " + e, e);
            }
        }
    }

    /**
     * If supported we should escape <code>${FOO}</code> for template expressions
     */
    public static String unquoteTemplateExpression(String value) {
        // turns out escaping of ${foo} expressions isn't supported yet in maven inside <properties> elements
/*
        int idx = 0;
        while (true) {
            idx = value.indexOf("\\${", idx);
            if (idx >= 0) {
                value = value.substring(0, idx) + value.substring(++idx);
            } else {
                break;
            }
        }
*/
        return value;
    }

    public JsonSchema getEnvironmentVariableJsonSchema() throws IOException, MojoExecutionException {
        JsonSchema schema = JsonSchemas.loadEnvironmentSchemas(getCompileClassLoader(), getProject().getBuild().getOutputDirectory());
        if (schema == null) {
            getLog().info("No environment schemas found for file: " + JsonSchemas.ENVIRONMENT_SCHEMA_FILE);
            schema = new JsonSchema();
        }
        Map<String, String> envs = getEnvironmentVariableProperties();
        JsonSchemas.addEnvironmentVariables(schema, envs);
        return schema;
    }

    protected File copyIconToFolder(File appBuildDir) throws MojoExecutionException, IOException {
        if (Strings.isNotBlank(iconRef)) {
            File[] icons = appBuildDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    if (name == null) {
                        return false;
                    }
                    String lower = name.toLowerCase();
                    if (lower.startsWith("icon.")) {
                        for (String ext : ICON_EXTENSIONS) {
                            if (lower.endsWith(ext)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
            });
            if (icons == null || icons.length == 0) {
                // lets copy the iconRef
                InputStream in = loadPluginResource(iconRef);
                if (in == null) {
                    // maybe it dont have extension so try to find it
                    for (String ext : ICON_EXTENSIONS) {
                        String name = iconRef + ext;
                        in = loadPluginResource(name);
                        if (in != null) {
                            iconRef = name;
                            break;
                        }
                    }
                }
                if (in != null) {
                    String fileName = "icon." + Files.getFileExtension(iconRef);
                    File outFile = new File(appBuildDir, fileName);
                    Files.copy(in, new FileOutputStream(outFile));
                    getLog().info("Generated icon file " + outFile + " from icon reference: " + iconRef);
                    return outFile;
                }
            }
        }
        return null;
    }

    /**
     * Copies any local configuration files into the app directory
     */
    protected void copyAppConfigFiles(File appBuildDir, File appConfigDir) throws IOException {
        File[] files = appConfigDir.listFiles();
        if (files != null) {
            appBuildDir.mkdirs();
            for (File file : files) {
                if (!toBeExclude(file.getName())) {
                    File outFile = new File(appBuildDir, file.getName());
                    if (file.isDirectory()) {
                        copyAppConfigFiles(outFile, file);
                    } else {
                        Files.copy(file, outFile);
                    }
                }
            }
        }
    }

    protected boolean toBeExclude(String fileName) {
        List excludedFilesList = Arrays.asList(filesToBeExcluded);
        Boolean result = excludedFilesList.contains(fileName);
        return result;
    }

    protected void copyReadMe(File appBuildDir) throws IOException {
        MavenProject project = getProject();
        copyReadMe(project.getFile().getParentFile(), appBuildDir);
    }

    protected void copySummaryText(File appBuildDir) throws IOException {
        MavenProject project = getProject();
        String description = project.getDescription();
        if (Strings.isNotBlank(description)) {
            File summaryMd = new File(appBuildDir, "Summary.md");
            summaryMd.getParentFile().mkdirs();
            if (!summaryMd.exists()) {
                byte[] bytes = description.getBytes();
                Files.copy(new ByteArrayInputStream(bytes), new FileOutputStream(summaryMd));
            }
        }
    }

    protected void printSummary(Object kubeResource) throws IOException {
        if (kubeResource instanceof Template) {
            Template template = (Template) kubeResource;
            String id = KubernetesHelper.getName(template);
            getLog().info("  Template " +  id + " " + KubernetesHelper.summaryText(template));
            printSummary(template.getObjects());
            return;
        }
        List<HasMetadata> list = KubernetesHelper.toItemList(kubeResource);
        for (Object object : list) {
            if (object != null) {
                if (object instanceof List) {
                    printSummary(object);
                } else {
                    String kind = object.getClass().getSimpleName();
                    String id = KubernetesHelper.getObjectId(object);
                    getLog().info("    " + kind + " " + id + " " + KubernetesHelper.summaryText(object));
                }
            }
        }
    }

    /**
     * Returns the root project folder
     */
    protected File getRootProjectFolder() {
        File answer = null;
        MavenProject project = getProject();
        while (project != null) {
            File basedir = project.getBasedir();
            if (basedir != null) {
                answer = basedir;
            }
            project = project.getParent();
        }
        return answer;
    }

    /**
     * Returns the root project folder
     */
    protected MavenProject getRootProject() {
        MavenProject project = getProject();
        while (project != null) {
            MavenProject parent = project.getParent();
            if (parent == null) {
                break;
            }
            project = parent;
        }
        return project;
    }

    public String getDockerImage() {
        MavenProject project = getProject();
        return project.getProperties().getProperty("docker.image");
    }

    Set<File> getDependencies() throws IOException {
        Set<File> dependnencies = new LinkedHashSet<>();
        MavenProject project = getProject();

        Path dir = Paths.get(project.getBuild().getOutputDirectory(), "deps");
        if (!dir.toFile().exists() && !dir.toFile().mkdirs()) {
            throw new IOException("Cannot create temp directory at:" + dir.toAbsolutePath());
        }

        for (Artifact candidate : project.getDependencyArtifacts()) {
            File f = candidate.getFile();
            if (f == null) {
                continue;
            } else if (f.getName().endsWith("jar") && hasKubernetesJson(f)) {
                getLog().info("Found file:" + f.getAbsolutePath());
                try (FileInputStream fis = new FileInputStream(f); JarInputStream jis = new JarInputStream(fis)) {
                    Zips.unzip(new FileInputStream(f), dir.toFile());
                    File jsonPath = dir.resolve(DEFAULT_CONFIG_FILE_NAME).toFile();
                    if (jsonPath.exists()) {
                        dependnencies.add(jsonPath);
                    }
                }
            } else if (isKubernetesJsonArtifact(candidate.getClassifier(), candidate.getType())) {
                dependnencies.add(f);
            }
        }
        return dependnencies;
    }


    static boolean isKubernetesJsonArtifact(String classifier, String type) {
        return Objects.equal("json", type) && Objects.equal("kubernetes", classifier);
    }

    static boolean hasKubernetesJson(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f); JarInputStream jis = new JarInputStream(fis)) {
            for (JarEntry entry = jis.getNextJarEntry(); entry != null; entry = jis.getNextJarEntry()) {
                if (entry.getName().equals(DEFAULT_CONFIG_FILE_NAME)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Before applying the given template lets allow template parameters to be overridden via the maven
     * properties - or optionally - via the command line if in interactive mode.
     */
    protected void overrideTemplateParameters(Template template) {
        List<io.fabric8.openshift.api.model.Parameter> parameters = template.getParameters();
        MavenProject project = getProject();
        if (parameters != null && project != null) {
            Properties properties = project.getProperties();
            properties.putAll(project.getProperties());
            properties.putAll(System.getProperties());
            boolean missingProperty = false;
            for (io.fabric8.openshift.api.model.Parameter parameter : parameters) {
                String parameterName = parameter.getName();
                String name = "fabric8.apply." + parameterName;
                String propertyValue = properties.getProperty(name);
                if (propertyValue != null) {
                    getLog().info("Overriding template parameter " + name + " with value: " + propertyValue);
                    parameter.setValue(propertyValue);
                } else {
                    missingProperty = true;
                    getLog().info("No property defined for template parameter: " + name);
                }
            }
            if (missingProperty) {
                getLog().debug("Current properties " + new TreeSet<>(properties.keySet()));
            }
        }
    }
}
