package io.github.search5.hg4j;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Gradle plugin integration for hg4j (Mercurial SCM).
 *
 * @apiNote Applied via {@code plugins { id 'io.github.search5.hg4j' version '...' } } (see the
 *     "Gradle Plugin Usage" section of the project README). Currently puts the hg4j library on
 *     the buildscript classpath and logs a lifecycle confirmation; it does not yet register any
 *     tasks or a project extension of its own -- build scripts call the {@link
 *     io.github.search5.hg4j.api.Hg} API directly from a custom task action.
 */
public class HgPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // Register hg4j initialization, logging, or integration tasks in the future
        project.getLogger().lifecycle("Applying hg4j Mercurial SCM plugin to project: " + project.getName());
    }
}
