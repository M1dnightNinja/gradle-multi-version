package org.wallentines.gradle.mv;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.ConfigurationVariantDetails;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import javax.inject.Inject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class MultiVersionExtension {

    private final Project project;
    private final SourceSetContainer sourceSets;
    private final JavaToolchainService toolchainService;

    private final HashMap<SourceSet, Map<Integer, SourceDirectorySet>> directorySets = new HashMap<>();

    private int defaultVersion = 0;
    private boolean useSourceDirectorySets = false;
    private boolean skipApiGuardianDependency = false;


    /**
     * Sets the flag to use source directory sets as opposed to source sets. See the README for more details
     */
    public void useSourceDirectorySets() {
        this.useSourceDirectorySets = true;
    }


    /**
     * Sets the flag to skip the @API Guardian dependency for tests run on source directory sets. See the README for more details
     */
    public void skipApiGuardianDependency() {
        this.skipApiGuardianDependency = true;
    }


    @Inject
    public MultiVersionExtension(Project project, JavaToolchainService toolchainService) {

        this.project = project;
        this.sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        this.toolchainService = toolchainService;

    }

    /**
     * Creates a version override which will be applied to the default compile and jar tasks
     * @param version The version to target
     */
    public void defaultVersion(int version) {

        if(defaultVersion != 0) {
            throw new IllegalStateException("Cannot set default version more than once!");
        }

        defaultVersion = version;
        setupVersion(version, true);
    }

    /**
     * Adds additional source (directory) sets which will be compiled for the given versions
     * @param versions The JVM versions to target
     */
    public void additionalVersions(int... versions) {
        for(int i : versions) {
            setupVersion(i, false);
        }
    }

    /**
     * Gets the compile task for the given version within the main source set
     * @param version The version to lookup
     * @return A reference to the compile task for that version
     * @throws org.gradle.api.UnknownDomainObjectException If there is no compile task for the given version
     */
    public JavaCompile getCompileTask(int version) {

        return getCompileTask(version, sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME));
    }

    /**
     * Gets the compile task for the given within the given source set
     * @param version The version to lookup
     * @param set The source set to look into.
     * @return A reference to the compile task for that version
     * @throws org.gradle.api.UnknownDomainObjectException If there is no compile task for the given version
     */
    public JavaCompile getCompileTask(int version, SourceSet set) {

        return (JavaCompile) project.getTasks().getByName(
                defaultVersion == version ?
                        set.getCompileTaskName("java") :
                        getCompileTaskName(version, set)
        );
    }

    /**
     * Gets the jar task for the given version within the main source set
     * @param version The version to lookup
     * @return A reference to the jar task for that version
     * @throws org.gradle.api.UnknownDomainObjectException If there is no jar task for the given version
     */
    public Jar getJarTask(int version) {

        return getJarTask(version, sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME));
    }

    /**
     * Gets the jar task for the given within the given source set
     * @param version The version to lookup
     * @param set The source set to look into.
     * @return A reference to the jar task for that version
     * @throws org.gradle.api.UnknownDomainObjectException If there is no jar task for the given version
     */
    public Jar getJarTask(int version, SourceSet set) {

        return (Jar) project.getTasks().getByName(
                defaultVersion == version ?
                        set.getJarTaskName() :
                        getJarTaskName(version, set)
        );
    }


    /**
     * Gets the source directory set for the given version override on the main source set
     * @param version The version override to lookup
     * @return A source directory set, or null if there is no version override for the given version
     */
    public SourceSet getSourceSet(int version) {

        return sourceSets.getByName(getSourceSetName(version));
    }

    /**
     * Gets the source directory set for the given version override on the main source set
     * @param version The version override to lookup
     * @return A source directory set, or null if there is no version override for the given version
     */
    public SourceDirectorySet getSourceDirectorySet(int version) {
        return getSourceDirectorySet(version, sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME));
    }


    /**
     * Gets the source directory set for the given version override on the given source set
     * @param version The version override to lookup
     * @param set The source set to look into
     * @return A source directory set, or null if there is no version override for the given version
     */
    public SourceDirectorySet getSourceDirectorySet(int version, SourceSet set) {

        if(!directorySets.containsKey(set)) return null;
        return directorySets.get(set).getOrDefault(version, null);
    }


    private void setupVersion(int version, boolean defaultVersion) {

        String name = getSourceSetName(version);
        SourceSet mainJava = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet mainTest = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);

        if(mainJava == null) {
            throw new IllegalStateException("Main source set must exist!");
        }

        if(useSourceDirectorySets) {
            setupSourceDirectorySet(version, name, mainJava, mainTest, defaultVersion);
        } else {
            setupSourceSet(version, name, mainJava, mainTest, defaultVersion);
        }

    }


    private void setupSourceDirectorySet(int version, String name, SourceSet sourceSet, SourceSet testSet, boolean defaultVersion) {

        JavaLanguageVersion javaVersion = JavaLanguageVersion.of(version);
        Provider<JavaLauncher> targetLauncher = toolchainService.launcherFor(spec -> spec.getLanguageVersion().convention(javaVersion));
        ConfigurationContainer configurations = project.getConfigurations();
        TaskContainer tasks = project.getTasks();

        // Source Directory Set
        SourceDirectorySet java = addSourceDirectorySet(name, version, sourceSet, defaultVersion);
        cacheSourceDirectorySet(sourceSet, version, java);

        Jar jarTask;

        if(defaultVersion) {

            jarTask = (Jar) tasks.getByName(sourceSet.getJarTaskName());

        } else {
            // Jar
            jarTask = tasks.register(getJarTaskName(version, sourceSet), Jar.class, task -> {
                task.setGroup("build");
                task.dependsOn(tasks.getByName(getClassesTaskName(version, sourceSet)));
                task.dependsOn(tasks.getByName(sourceSet.getProcessResourcesTaskName()));
                task.getArchiveClassifier().set(name);
                task.from(java.getDestinationDirectory().get().getAsFile(), sourceSet.getOutput().getResourcesDir());
            }).get();
            tasks.getByName("assemble").dependsOn(jarTask);

            Configuration implementation = configurations.getByName(configurationNameOf(sourceSet.getImplementationConfigurationName(), version));

            // Variant Artifacts
            Configuration apiElements = configurations.create(configurationNameOf(sourceSet.getApiElementsConfigurationName(), version), conf -> setupElementsConfig(conf, Usage.JAVA_API, implementation, jarTask, version));
            Configuration runtimeElements = configurations.create(configurationNameOf(sourceSet.getRuntimeElementsConfigurationName(), version), conf -> setupElementsConfig(conf, Usage.JAVA_RUNTIME, implementation, jarTask, version));

            AdhocComponentWithVariants javaComponent = (AdhocComponentWithVariants) project.getComponents().getByName("java");
            javaComponent.addVariantsFromConfiguration(apiElements, ConfigurationVariantDetails::mapToOptional);
            javaComponent.addVariantsFromConfiguration(runtimeElements, ConfigurationVariantDetails::mapToOptional);

        }

        // Application
        project.getPluginManager().withPlugin("application", plugin -> {

            JavaApplication application = project.getExtensions().getByType(JavaApplication.class);
            FileCollection runtimeClasspath = configurations.getByName(configurationNameOf(sourceSet.getRuntimeClasspathConfigurationName(), version)).plus(project.files(java.getDestinationDirectory()));
            JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);

            if(defaultVersion) {
                tasks.named("run", JavaExec.class, task -> {
                    task.getJavaLauncher().convention(targetLauncher);
                    task.setClasspath(getRuntimeClasspath(task, jarTask, runtimeClasspath));
                });
            } else {
                tasks.register(name + "Run", JavaExec.class, task -> {
                    task.setGroup(ApplicationPlugin.APPLICATION_GROUP);
                    task.setClasspath(getRuntimeClasspath(task, jarTask, runtimeClasspath));
                    task.getJavaLauncher().convention(targetLauncher);
                    task.getMainClass().convention(application.getMainClass());
                    task.getMainModule().convention(application.getMainModule());
                    task.getModularity().getInferModulePath().convention(javaExtension.getModularity().getInferModulePath());
                });
            }
        });


        // Tests
        SourceDirectorySet test = addSourceDirectorySet(name, version, testSet, defaultVersion);
        cacheSourceDirectorySet(testSet, version, test);

        Configuration testImplementation = configurations.getByName(configurationNameOf(testSet.getImplementationConfigurationName(), version));
        Configuration testCompileOnly = configurations.getByName(configurationNameOf(testSet.getCompileOnlyConfigurationName(), version));

        DependencyHandler dependencies = project.getDependencies();

        testImplementation.extendsFrom(configurations.getByName(configurationNameOf(sourceSet.getImplementationConfigurationName(), version)));
        testCompileOnly.extendsFrom(configurations.getByName(configurationNameOf(sourceSet.getCompileOnlyConfigurationName(), version)));

        if(!skipApiGuardianDependency) {
            // Add @API Guardian to prevent warnings during tests. (https://github.com/apiguardian-team/apiguardian)
            testImplementation.getDependencies().add(dependencies.create("org.apiguardian:apiguardian-api:1.1.2"));
        }

        if(!defaultVersion) {

            Configuration testCompileClasspath = configurations.getByName(configurationNameOf(testSet.getCompileClasspathConfigurationName(), version));
            Configuration testRuntimeClasspath = configurations.getByName(configurationNameOf(testSet.getRuntimeClasspathConfigurationName(), version));

            JavaCompile testCompile = getCompileTask(version, testSet);
            testCompile.setClasspath(project.getObjects().fileCollection().from(testCompileClasspath, project.files(java.getDestinationDirectory())));
            testCompile.dependsOn(getCompileTask(version, sourceSet));

            TaskProvider<Test> testTask = tasks.register(name + "Test", Test.class, task -> {
                task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                task.getJavaLauncher().convention(targetLauncher);
                task.dependsOn(tasks.getByName(getClassesTaskName(version, testSet)));

                task.setClasspath(project.getObjects().fileCollection().from(testCompile.getDestinationDirectory().getAsFile().get(), java.getDestinationDirectory().getAsFile().get(), testRuntimeClasspath));
            });

            tasks.named("check", task -> task.dependsOn(testTask));

        }
    }

    private SourceDirectorySet addSourceDirectorySet(String name, int version, SourceSet parent, boolean defaultVersion) {

        JavaLanguageVersion javaVersion = JavaLanguageVersion.of(version);
        TaskContainer tasks = project.getTasks();

        // Source Directory Set
        SourceDirectorySet java = project.getObjects().sourceDirectorySet(name, "Java " + version + " " + parent.getName() + " sources");
        java.srcDir("src/" + parent.getName() + "/" + name);


        // Configurations
        Configuration implementation = registerConfiguration(parent.getImplementationConfigurationName(), version, true);
        Configuration runtimeOnly = registerConfiguration(parent.getRuntimeOnlyConfigurationName(), version, true);
        Configuration compileOnly = registerConfiguration(parent.getCompileOnlyConfigurationName(), version, true);

        Configuration compileClasspath = registerConfiguration(parent.getCompileClasspathConfigurationName(), version, false);

        compileClasspath.extendsFrom(compileOnly, implementation);

        Configuration annotationProcessor = registerConfiguration(parent.getAnnotationProcessorConfigurationName(), version, true);
        Configuration runtimeClasspath = registerConfiguration(parent.getRuntimeClasspathConfigurationName(), version, false);

        runtimeClasspath.extendsFrom(runtimeOnly, implementation);

        Configuration api = maybeRegisterConfiguration(parent.getApiConfigurationName(), version);
        Configuration compileOnlyApi = maybeRegisterConfiguration(parent.getCompileOnlyApiConfigurationName(), version);

        if(api != null) {
            implementation.extendsFrom(api);
        }
        if(compileOnlyApi != null) {
            compileOnly.extendsFrom(api);
        }

        // Compile Task
        Provider<JavaCompiler> targetCompiler = toolchainService.compilerFor(spec -> spec.getLanguageVersion().convention(javaVersion));

        if(defaultVersion) {

            tasks.named(parent.getCompileJavaTaskName(), JavaCompile.class, task -> {
                task.getJavaCompiler().convention(targetCompiler);

                FileTree source = task.getSource();
                task.setSource(java.getSourceDirectories().plus(filterSources(source, parent.getJava(), java).getAsFileTree()));
            });
            java.getDestinationDirectory().convention(parent.getJava().getDestinationDirectory());

        } else {

            // Output
            final String sourceSetChildPath = "classes/" + name + "/" + parent.getName();
            java.getDestinationDirectory().convention(project.getLayout().getBuildDirectory().dir(sourceSetChildPath));

            TaskProvider<JavaCompile> compileTask = tasks.register(getCompileTaskName(version, parent), JavaCompile.class, (task) -> {

                task.setSource(java.getSourceDirectories().plus(filterSources(parent.getJava().getSourceDirectories(), parent.getJava(), java)));

                task.getJavaCompiler().convention(targetCompiler);
                task.setClasspath(compileClasspath);

                String generatedHeadersDir = "generated/sources/headers/" + name + "/" + parent.getName();
                task.getOptions().getHeaderOutputDirectory().convention(project.getLayout().getBuildDirectory().dir(generatedHeadersDir));
                task.getOptions().setAnnotationProcessorPath(annotationProcessor);

                JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
                task.getModularity().getInferModulePath().convention(javaPluginExtension.getModularity().getInferModulePath());

                task.getDestinationDirectory().convention(java.getDestinationDirectory());

            });
            java.compiledBy(compileTask, AbstractCompile::getDestinationDirectory);

            // Register
            parent.getAllJava().source(java);

            // Classes
            tasks.register(getClassesTaskName(version, parent), task -> task.dependsOn(compileTask));
        }

        return java;
    }


    private void setupSourceSet(int version, String name, SourceSet sourceSet, SourceSet testSet, boolean defaultVersion) {

        JavaLanguageVersion javaVersion = JavaLanguageVersion.of(version);
        DependencyHandler dependencies = project.getDependencies();
        ConfigurationContainer configurations = project.getConfigurations();
        TaskContainer tasks = project.getTasks();

        // Source Set
        SourceSet java = sourceSets.create(name, set -> set.setCompileClasspath(set.getCompileClasspath().plus(sourceSet.getCompileClasspath())));
        cacheSourceDirectorySet(sourceSet, version, java.getJava());

        Configuration javaImpl = configurations.getByName(java.getImplementationConfigurationName(), conf ->
                conf.getAttributes().attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, version));

        // Dependencies
        Configuration javaComp = configurations.getByName(java.getCompileOnlyConfigurationName());
        Configuration javaRuntime = configurations.getByName(java.getRuntimeOnlyConfigurationName());

        Configuration mainImpl = configurations.getByName(sourceSet.getImplementationConfigurationName());
        Configuration mainComp = configurations.getByName(sourceSet.getCompileOnlyConfigurationName());
        Configuration mainRuntime = configurations.getByName(sourceSet.getRuntimeOnlyConfigurationName());

        javaImpl.extendsFrom(mainImpl);
        javaComp.extendsFrom(mainComp);
        javaRuntime.extendsFrom(mainRuntime);

        // API dependencies
        Configuration javaApi = configurations.findByName(java.getApiConfigurationName());
        if(javaApi != null) {
            Configuration javaApiComp = configurations.getByName(java.getCompileOnlyApiConfigurationName());
            Configuration mainApi = configurations.getByName(sourceSet.getApiConfigurationName());
            Configuration mainApiComp = configurations.getByName(sourceSet.getCompileOnlyApiConfigurationName());
            javaApi.extendsFrom(mainApi);
            javaApiComp.extendsFrom(mainApiComp);
        }

        // Compilation
        Provider<JavaCompiler> targetCompiler = toolchainService.compilerFor(spec -> spec.getLanguageVersion().convention(javaVersion));
        Provider<JavaLauncher> targetLauncher = toolchainService.launcherFor(spec -> spec.getLanguageVersion().convention(javaVersion));

        Jar jarTask;


        TaskProvider<JavaCompile> compileTask = tasks.named(java.getCompileJavaTaskName(), JavaCompile.class, task -> {
            task.getJavaCompiler().convention(targetCompiler);

            FileTree source = task.getSource();
            task.setSource(source.plus(filterSources(sourceSet.getJava().getSourceDirectories().getAsFileTree(), sourceSet.getJava(), java.getJava())).getAsFileTree());
        });

        if(defaultVersion) {

            tasks.named(sourceSet.getJarTaskName(), Jar.class, task -> {
                task.dependsOn(compileTask);
                task.from(java.getJava().getDestinationDirectory(), java.getOutput().getResourcesDir(), sourceSet.getOutput().getResourcesDir());

                task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            });

            jarTask = (Jar) tasks.getByName(sourceSet.getJarTaskName());

        } else {

            // Jars
            jarTask = tasks.create(java.getJarTaskName(), Jar.class, task -> {

                task.setGroup("build");
                task.dependsOn(sourceSet.getProcessResourcesTaskName());
                task.dependsOn(compileTask);
                task.from(java.getJava().getDestinationDirectory(), java.getOutput().getResourcesDir(), sourceSet.getOutput().getResourcesDir());
                task.getArchiveClassifier().set(name);

            });

            tasks.getByName("assemble").dependsOn(jarTask);


            // Variant Artifacts
            Configuration apiElements = configurations.create(java.getApiElementsConfigurationName(), conf -> setupElementsConfig(conf, Usage.JAVA_API, javaImpl, jarTask, version));
            Configuration runtimeElements = configurations.create(java.getRuntimeElementsConfigurationName(), conf -> setupElementsConfig(conf, Usage.JAVA_RUNTIME, javaImpl, jarTask, version));

            AdhocComponentWithVariants javaComponent = (AdhocComponentWithVariants) project.getComponents().getByName("java");
            javaComponent.addVariantsFromConfiguration(apiElements, ConfigurationVariantDetails::mapToOptional);
            javaComponent.addVariantsFromConfiguration(runtimeElements, ConfigurationVariantDetails::mapToOptional);

        }

        // Application
        project.getPluginManager().withPlugin("application", plugin -> {
            JavaApplication application = project.getExtensions().getByType(JavaApplication.class);
            JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);

            if(defaultVersion) {
                tasks.named("run", JavaExec.class, task -> {
                    task.setClasspath(getRuntimeClasspath(task, jarTask, java.getRuntimeClasspath()).plus(java.getOutput()));
                    task.getJavaLauncher().convention(targetLauncher);
                });
            } else {
                tasks.register(name + "Run", JavaExec.class, task -> {
                    task.setGroup(ApplicationPlugin.APPLICATION_GROUP);
                    task.setClasspath(getRuntimeClasspath(task, jarTask, java.getRuntimeClasspath()).plus(java.getOutput()));
                    task.getJavaLauncher().convention(targetLauncher);
                    task.getMainClass().convention(application.getMainClass());
                    task.getMainModule().convention(application.getMainModule());
                    task.getModularity().getInferModulePath().convention(javaExtension.getModularity().getInferModulePath());
                });
            }
        });

        // Tests
        if(testSet != null) {

            SourceSet test = sourceSets.create(name + "Test");
            cacheSourceDirectorySet(testSet, version, test.getJava());

            JavaCompile testCompileTask = tasks.named(test.getCompileJavaTaskName(), JavaCompile.class, task -> {
                task.getJavaCompiler().convention(targetCompiler);

                FileTree source = task.getSource();
                task.setSource(source.plus(filterSources(testSet.getJava().getSourceDirectories().getAsFileTree(), testSet.getJava(), test.getJava()).getAsFileTree()));
            }).get();


            Configuration testImpl = configurations.getByName(test.getImplementationConfigurationName());
            Configuration testComp = configurations.getByName(test.getCompileOnlyConfigurationName());
            Configuration testRuntimeClasspath = configurations.getByName(test.getRuntimeClasspathConfigurationName());

            Configuration mainTestImpl = configurations.getByName(testSet.getImplementationConfigurationName());
            Configuration mainTestComp = configurations.getByName(testSet.getCompileOnlyConfigurationName());

            testImpl.extendsFrom(mainTestImpl);
            testComp.extendsFrom(mainTestComp);

            testImpl.getDependencies().add(dependencies.create(java.getOutput().getClassesDirs()));
            testImpl.getDependencies().add(dependencies.create(sourceSet.getOutput().getClassesDirs()));

            TaskProvider<Test> testTask = tasks.register(name + "Test", Test.class, task -> {
                task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                task.getJavaLauncher().convention(targetLauncher);
                task.setClasspath(project.getObjects().fileCollection().from(testCompileTask.getDestinationDirectory(), java.getOutput(), testRuntimeClasspath));
            });

            tasks.named("check", task -> task.dependsOn(testTask));
        }
    }


    private void cacheSourceDirectorySet(SourceSet set, int version, SourceDirectorySet value) {

        directorySets.compute(set, (k,v) -> {
            if(v == null) v = new TreeMap<>();
            if(v.containsKey(version)) {
                throw new IllegalStateException("Attempt to overwrite source directory set for version " + version + " on source set " + set.getName());
            }
            v.put(version, value);
            return v;
        });

    }


    private FileCollection getRuntimeClasspath(JavaExec task, Jar jarTask, FileCollection runtimeClasspath) {
        return project.files().from(
                (task.getMainModule().isPresent() ?
                        jarTask.getOutputs().getFiles().plus(runtimeClasspath) :
                        runtimeClasspath)
        );
    }


    private void setupElementsConfig(Configuration element, String usage, Configuration implementation, Jar jar, int version) {

        ConfigurationContainer configurations = project.getConfigurations();
        TaskContainer tasks = project.getTasks();

        element.setCanBeResolved(false);
        element.setCanBeConsumed(true);

        element.attributes(attr -> {
            attr.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, usage));
            attr.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY));
            attr.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
            attr.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, version);
        });

        element.extendsFrom(configurations.getByName(implementation.getName()));
        element.outgoing(pub -> pub.artifact(tasks.named(jar.getName())));
    }


    private static String getCompileTaskName(int version, SourceSet base) {
        return base.getCompileTaskName("java") + version + "Java";
    }


    private static String getSourceSetName(int version) {
        return "java" + version;
    }


    private static String getJarTaskName(int version, SourceSet base) {
        return "java" + version + capitalize(base.getJarTaskName());
    }


    private static String getClassesTaskName(int version, SourceSet base) {
        return "java" + version + capitalize(base.getClassesTaskName() );
    }


    private static String configurationNameOf(String configuration, int version) {
        return "java" + version + capitalize(configuration);
    }


    private static String capitalize(String other) {
        if(other == null || other.isEmpty()) return other;
        return Character.toTitleCase(other.charAt(0)) + other.substring(1);
    }


    private Configuration registerConfiguration(String name, int version, boolean extend) {

        Configuration base = project.getConfigurations().findByName(name);
        if (base == null) {
            throw new IllegalStateException("Unable to find base configuration " + name + "!");
        }
        Configuration out = project.getConfigurations().create(configurationNameOf(name, version));
        setupConfiguration(out, base, extend, version);
        return out;
    }


    private Configuration maybeRegisterConfiguration(String name, int version) {

        Configuration base = project.getConfigurations().findByName(name);

        if(base != null) {
            Configuration out = project.getConfigurations().create(configurationNameOf(name, version));
            setupConfiguration(out, base, true, version);
            return out;
        }

        return null;
    }


    private void setupConfiguration(Configuration config, Configuration base, boolean extend, int version) {

        if(extend) {
            config.extendsFrom(base);
        }
        config.getAttributes().attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, version);
    }


    private FileCollection filterSources(FileCollection sources, SourceDirectorySet sourceDirectories, SourceDirectorySet overrides) {

        return sources.filter(file -> {

            for (File overrideDir : overrides.getSourceDirectories()) {
                for (File sourceDir : sourceDirectories.getSourceDirectories()) {

                    String overridePath = overrideDir.getAbsolutePath();
                    String sourcePath = sourceDir.getAbsolutePath();

                    if(!file.getAbsolutePath().startsWith(sourcePath)) continue;

                    String suffix = file.getAbsolutePath().substring(sourcePath.length());
                    File existing = new File(overridePath + suffix);

                    if (overrides.contains(existing)) {
                        return false;
                    }
                }
            }

            return true;
        });
    }

}
