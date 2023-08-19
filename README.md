## GradleMultiVersion
Compile your code for multiple versions of the JVM.

## Overview
This project is designed to allow you to compile your Java code for multiple versions of the JVM. Much of the original 
code for this plugin is based on [Melix's multi-release jars plugin](https://github.com/melix/mrjar-gradle-plugin) gradle plugin. However, this plugin does not 
utilize multi-release jars. Instead, it compiles the same codebase for each version of the JVM you want to target, and 
produces a single artifact for each. Note that this plugin is still in development and things will likely change.

## Usage
### Setup
The plugin is currently not hosted on any public maven repository. To use it, first clone the repository. Then build it
by running `gradlew build` in its root. Then publish it to the local maven repository using 
`gradlew publishToMavenLocal`. Then, add the local maven repository to your `settings.gradle.kts`:
```
pluginManagement {
    repositories {
        mavenLocal()
    }
}
```
Alternatively, you can reference it in your project directly by adding a reference to the cloned repository to your
`settings.gradle.kts`:
```
pluginManagement {
    includeBuild("../path/to/cloned/repo")
}
```

Then, add the plugin to your `build.gradle.kts`:
```
plugins {
    id("org.wallentines.gradle-multi-version") version "0.2.0"
}
```
The final step to get everything set up is to add the additional JVM versions you would like to target to your
`build.gradle.kts`:
```
multiVersion {
    additionalVersions(11,8)   // Compile for Java versions 11 and 8 in addition to the project defaults
}
```
Once that is done, the `build` task will now compile your project for the versions you specified in addition to the
version it is already compiling against. Artifacts for those builds will be produced in the output folder with the name
`{artifactId}-java{version}.jar` (e.g. `myproject-java11.jar`). These artifacts will also automatically be published
alongside your other artifacts for publications configured with `from(components["java"])`. They will be published as
gradle variants, so other gradle projects will automatically find them.


### Default Version Overrides
You may encounter a situation where you need to reference a dependency which is only available for the project's default
java version, or you want to add/override a class only for the default java version. In such cases, add the following to 
the `multiVersion` section of your `build.gradle.kts`:
```
multiVersion {
    defaultVersion(17)   // Set the default language version to Java 17
}
```
This will add configurations and a source set which is specific to the default version. These are formatted exactly the
same as additional versions (described above). However, no additional artifacts will be produced. Instead, all version
overrides and dependency information for the default version will be merged into the default artifact variant.


### Configurations
Each version creates its own set of default configurations prefixed with the java version. For example, targeting
java 11 will result in configurations such as the following being created: `java11Implementation`, `java11CompileOnly`,
`java11Api`, etc. These default configurations can be specified manually in dependency configurations, but they also
extend from the main configurations. (e.g. `java11Implementation` will extend from `implementation`)


### Source Sets
Each version also creates two source sets: one for java code and one for tests. Both source sets will be located in the
`src` folder  (unless configured to use source directory sets, see below) The java source set will be named with the
java version, and the test source set will be named the same plus "Test" (e.g. `src/java11` and `src/java11Test`). If
you put classes in these, they will be compiled for that java version only. If you put a class in there with the same
relative path as a class in the main source set, the one from the main source set will not be compiled at all, and the
one in the version's source set will be used in its place. This allows you to override individual classes for older
versions which do not support features used in the main source set, or override individual classes for newer versions
which support newer features/libraries not available in the main source set.


### Source Sets vs. Source Directory Sets
If you call the function `useSourceDirectorySets()` to the `multiVersion` section of your `build.gradle.kts` file,
before declaring additional or default versions, the plugin will create two source directory sets per version, rather
than two entire source sets. There are a few notable differences between the two approaches, starting with the location
of source files. Source directory sets will be located next to the main `java` folder. (e.g. `src/main/java11`) The
same goes for tests, which will be located next to the test `java` folder. (e.g. `src/test/java11`) This approach may
lead to a cleaner codebase, but with a couple drawbacks. For starters, there are no additional `resources` directories
created, so if you need to add additional resources to your versioned jars, this will not be possible with this method.
Additionally, adding source directory sets does not appear to play nicely with IntelliJ IDEA. If you override any class
files in other source directory sets, IDEA will complain about duplicate classes. This does not affect compilation or
packaging, but may be kind of annoying, which would defeat the purpose of using source directory sets in the first
place.


### Tests
As of now, test source sets can only add additional test files, but cannot override existing tests from the main test
source set. This may be changed in the future. Additionally, all tests added when using source directory sets are given
an implementation dependency on [@API Guardian](https://github.com/apiguardian-team/apiguardian) by default. This is to
avoid the console being spammed with hundreds of warnings during test compilation. If you run into errors regarding that
dependency, either make sure maven central is in your repositories, or call `skipApiGuardianDependency()` in the
`multiVersion` section of your `build.gradle.kts` file before declaring any versions.
