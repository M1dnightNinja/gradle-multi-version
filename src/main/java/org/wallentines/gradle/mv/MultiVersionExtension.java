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
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.*;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.impldep.org.apache.commons.lang.StringUtils;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import javax.inject.Inject;
import java.io.File;

public class MultiVersionExtension {

    private final Project project;
    private final SourceSetContainer sourceSets;
    private final JavaToolchainService toolchainService;

    @Inject
    public MultiVersionExtension(Project project, JavaToolchainService toolchainService) {

        this.project = project;
        this.sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        this.toolchainService = toolchainService;

    }

    public void defaultVersion(int version) {

        setupVersion(version, true);
    }

    public void additionalVersions(int... versions) {
        for(int i : versions) {
            setupVersion(i, false);
        }
    }

    private void setupVersion(int version, boolean defaultVersion) {

        JavaLanguageVersion javaVersion = JavaLanguageVersion.of(version);
        DependencyHandler dependencies = project.getDependencies();
        ConfigurationContainer configurations = project.getConfigurations();
        TaskContainer tasks = project.getTasks();

        String name = "java" + version;

        SourceSet mainJava = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet mainTest = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);

        if(mainJava == null) {
            throw new IllegalStateException("Main source set must exist!");
        }

        // Source Set
        SourceSet java = sourceSets.create(name, set -> set.setCompileClasspath(set.getCompileClasspath().plus(mainJava.getCompileClasspath())));

        Configuration javaImpl = configurations.getByName(java.getImplementationConfigurationName(), conf ->
                conf.getAttributes().attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, version));

        // Dependencies
        FileCollection mainClasses = project.getObjects().fileCollection().from(sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getOutput().getClassesDirs());
        javaImpl.getDependencies().add(dependencies.create(mainClasses));

        Configuration javaComp = configurations.getByName(java.getCompileOnlyConfigurationName());
        Configuration javaRuntime = configurations.getByName(java.getRuntimeOnlyConfigurationName());

        Configuration mainImpl = configurations.getByName(mainJava.getImplementationConfigurationName());
        Configuration mainComp = configurations.getByName(mainJava.getCompileOnlyConfigurationName());
        Configuration mainRuntime = configurations.getByName(mainJava.getRuntimeOnlyConfigurationName());

        javaImpl.extendsFrom(mainImpl);
        javaComp.extendsFrom(mainComp);
        javaRuntime.extendsFrom(mainRuntime);

        // API dependencies
        Configuration javaApi = configurations.findByName(java.getApiConfigurationName());
        if(javaApi != null) {
            Configuration javaApiComp = configurations.getByName(java.getCompileOnlyApiConfigurationName());
            Configuration mainApi = configurations.getByName(mainJava.getApiConfigurationName());
            Configuration mainApiComp = configurations.getByName(mainJava.getCompileOnlyApiConfigurationName());
            javaApi.extendsFrom(mainApi);
            javaApiComp.extendsFrom(mainApiComp);
        }

        // Compilation
        Provider<JavaCompiler> targetCompiler = toolchainService.compilerFor(spec -> spec.getLanguageVersion().convention(javaVersion));
        Provider<JavaLauncher> targetLauncher = toolchainService.launcherFor(spec -> spec.getLanguageVersion().convention(javaVersion));

        if(defaultVersion) {

            tasks.named(mainJava.getCompileJavaTaskName(), JavaCompile.class, task -> {
                task.getJavaCompiler().convention(targetCompiler);

                FileTree source = task.getSource();
                task.setSource(source.plus(filterSources(source, mainJava.getJava(), java.getJava()).getAsFileTree()));
            });

            tasks.named(mainJava.getJarTaskName(), Jar.class, task -> {
                task.setGroup("build");
                task.from(java.getOutput());
            });

        } else {

            tasks.named(java.getCompileJavaTaskName(), JavaCompile.class, task -> {
                task.getJavaCompiler().convention(targetCompiler);

                FileTree source = task.getSource();
                task.setSource(source.plus(filterSources(source, mainJava.getJava(), java.getJava()).getAsFileTree()));
            });

            // Jars
            Jar javaJarTask = tasks.create(java.getJarTaskName(), Jar.class, task -> {

                task.setGroup("build");
                task.from(java.getOutput());
                task.getArchiveClassifier().set(name);

            });

            tasks.getByName("assemble").dependsOn(javaJarTask);


            // Variant Artifacts
            Configuration apiElements = configurations.create(java.getApiElementsConfigurationName(), conf -> { // API Elements
                conf.setCanBeResolved(false);
                conf.setCanBeConsumed(true);

                conf.attributes(attr -> {
                    attr.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_API));
                    attr.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY));
                    attr.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
                    attr.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, version);
                });

                conf.extendsFrom(configurations.getByName(java.getImplementationConfigurationName()));
                conf.outgoing(pub -> pub.artifact(tasks.named(java.getJarTaskName())));
            });

            Configuration runtimeElements = configurations.create(java.getRuntimeElementsConfigurationName(), conf -> { // Runtime Elements
                conf.setCanBeResolved(false);
                conf.setCanBeConsumed(true);

                conf.attributes(attr -> {
                    attr.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
                    attr.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY));
                    attr.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
                    attr.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, version);
                });

                conf.extendsFrom(configurations.getByName(java.getImplementationConfigurationName()));
                conf.outgoing(pub -> pub.artifact(tasks.named(java.getJarTaskName())));
            });

            AdhocComponentWithVariants javaComponent = (AdhocComponentWithVariants) project.getComponents().getByName("java");
            javaComponent.addVariantsFromConfiguration(apiElements, ConfigurationVariantDetails::mapToOptional);
            javaComponent.addVariantsFromConfiguration(runtimeElements, ConfigurationVariantDetails::mapToOptional);

        }


        // Tests
        if(mainTest != null) {

            SourceSet test = sourceSets.create(name + "Test");

            tasks.named(test.getCompileJavaTaskName(), JavaCompile.class, task -> {
                task.getJavaCompiler().convention(targetCompiler);

                FileTree source = task.getSource();
                task.setSource(source.plus(filterSources(source, mainTest.getJava(), test.getJava()).getAsFileTree()));
            });


            Configuration testImpl = configurations.getByName(test.getImplementationConfigurationName());
            Configuration testComp = configurations.getByName(test.getCompileOnlyConfigurationName());

            Configuration mainTestImpl = configurations.getByName(mainTest.getImplementationConfigurationName());
            Configuration mainTestComp = configurations.getByName(mainTest.getCompileOnlyConfigurationName());

            testImpl.extendsFrom(mainTestImpl);
            testComp.extendsFrom(mainTestComp);

            testImpl.getDependencies().add(dependencies.create(java.getOutput().getClassesDirs()));
            testImpl.getDependencies().add(dependencies.create(mainJava.getOutput().getClassesDirs()));

            TaskProvider<Test> testTask = tasks.register(name + "Test", Test.class, task -> {
                task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                task.getJavaLauncher().convention(targetLauncher);
            });

            tasks.named("check", task -> task.dependsOn(testTask));
        }


        // Application
        project.getPluginManager().withPlugin("application", plugin -> {
            JavaApplication application = project.getExtensions().getByType(JavaApplication.class);

            if(defaultVersion) {
                tasks.named("run", JavaExec.class, task -> {
                    task.getJavaLauncher().convention(targetLauncher);
                    task.setClasspath(java.getRuntimeClasspath());
                });
            } else {
                tasks.register(name + "Run", JavaExec.class, task -> {
                    task.setGroup(ApplicationPlugin.APPLICATION_GROUP);
                    task.getJavaLauncher().convention(targetLauncher);
                    task.getMainClass().convention(application.getMainClass());
                    task.setClasspath(java.getRuntimeClasspath());
                });
            }
        });

    }

    private String configurationNameOf(String sourceSet, String baseName) {
        return StringUtils.uncapitalize(sourceSet + StringUtils.capitalize(baseName));
    }


    private FileCollection filterSources(FileTree sources, SourceDirectorySet target, SourceDirectorySet destination) {

        return target.filter(file -> {
            for (File destDir : destination.getSourceDirectories()) {
                for (File dir : target.getSourceDirectories()) {

                    String targetPath = dir.getAbsolutePath();
                    String destPath = destDir.getAbsolutePath();

                    if(!file.getAbsolutePath().startsWith(targetPath)) continue;

                    String suffix = file.getAbsolutePath().substring(targetPath.length());
                    File existing = new File(destPath + suffix);

                    if (sources.contains(existing)) {
                        return false;
                    }
                }
            }

            return true;
        });
    }

}
