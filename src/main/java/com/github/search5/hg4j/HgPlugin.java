package com.github.search5.hg4j;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Gradle plugin integration for hg4j (Mercurial SCM).
 */
public class HgPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // Register hg4j initialization, logging, or integration tasks in the future
        project.getLogger().lifecycle("Applying hg4j Mercurial SCM plugin to project: " + project.getName());
    }
}
