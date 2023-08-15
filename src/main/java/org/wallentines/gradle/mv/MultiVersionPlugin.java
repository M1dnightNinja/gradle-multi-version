package org.wallentines.gradle.mv;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.jvm.toolchain.JavaToolchainService;

import javax.inject.Inject;

public class MultiVersionPlugin implements Plugin<Project> {

    @Inject
    protected JavaToolchainService getToolchains() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void apply(Project target) {

        target.getPlugins().apply(JavaPlugin.class);

        ExtensionContainer extensions = target.getExtensions();

        extensions.create("multiVersion", MultiVersionExtension.class, target, getToolchains());

    }
}
