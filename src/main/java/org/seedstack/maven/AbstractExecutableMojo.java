/**
 * Copyright (c) 2013-2016, The SeedStack authors <http://seedstack.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.seedstack.maven;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.cli.CommandLineUtils;

/**
 * Provides a common base for mojos that run SeedStack applications.
 */
public abstract class AbstractExecutableMojo extends AbstractSeedStackMojo {
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession mavenSession;
    @Component
    private BuildPluginManager buildPluginManager;
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File classesDirectory;
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    private File testClassesDirectory;
    @Parameter(property = "args")
    private String args;
    private final IsolatedThreadGroup isolatedThreadGroup = new IsolatedThreadGroup("seed-app");
    private final Object monitor = new Object();

    @SuppressFBWarnings(value = {"UW_UNCOND_WAIT", "WA_NOT_IN_LOOP"}, justification = "Cannot know when the "
            + "application is started")
    protected void execute(Runnable runnable, boolean testMode) throws MojoExecutionException, MojoFailureException {
        File[] classPathFiles = getClassPathFiles(testMode);

        // Set the system property for proper detection of classpath
        System.setProperty("java.class.path", buildCpProperty(classPathFiles));

        // Start the launcher thread
        ClassLoader classLoader = buildClassLoader(classPathFiles);

        // Create an isolated thread
        Thread bootstrapThread = new Thread(isolatedThreadGroup, runnable, "main");
        bootstrapThread.setContextClassLoader(classLoader);
        bootstrapThread.start();

        // Wait for the application to launch
        synchronized (monitor) {
            try {
                monitor.wait();
            } catch (InterruptedException e) {
                throw new MojoExecutionException("Interrupted while waiting for Seed");
            }
        }

        // Check for any uncaught exception
        synchronized (isolatedThreadGroup) {
            if (isolatedThreadGroup.uncaughtException != null) {
                throw new MojoExecutionException("An exception occurred while executing Seed",
                        isolatedThreadGroup.uncaughtException);
            }
        }
    }

    MavenSession getMavenSession() {
        return mavenSession;
    }

    BuildPluginManager getBuildPluginManager() {
        return buildPluginManager;
    }

    MavenProject getProject() {
        return project;
    }

    File getClassesDirectory() {
        return classesDirectory;
    }

    File getTestClassesDirectory() {
        return testClassesDirectory;
    }

    Object getMonitor() {
        return monitor;
    }

    String[] getArgs() throws MojoExecutionException {
        try {
            return CommandLineUtils.translateCommandline(args);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to parse arguments", e);
        }
    }

    URLClassLoader getClassLoader(final URL[] classPathUrls) {
        return AccessController.doPrivileged(new PrivilegedAction<URLClassLoader>() {
            public URLClassLoader run() {
                return new URLClassLoader(classPathUrls);
            }
        });
    }

    private ClassLoader buildClassLoader(File[] classPathFiles) throws MojoExecutionException {
        URL[] classPathUrls = new URL[classPathFiles.length];
        for (int i = 0; i < classPathFiles.length; i++) {
            try {
                classPathUrls[i] = classPathFiles[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Unable to create URL from " + classPathFiles[i]);
            }
        }
        return getClassLoader(classPathUrls);
    }

    private String buildCpProperty(File[] classPathFiles) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < classPathFiles.length; i++) {
            File classPathFile = classPathFiles[i];
            stringBuilder.append(classPathFile);
            if (i < classPathFiles.length - 1) {
                stringBuilder.append(File.pathSeparator);
            }
        }
        return stringBuilder.toString();
    }

    void waitForShutdown() {
        boolean found = true;

        while (found) {
            found = false;

            for (Thread groupThread : getGroupThreads(isolatedThreadGroup)) {
                if (!groupThread.isDaemon()) {
                    found = true;

                    try {
                        groupThread.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private Thread[] getGroupThreads(final ThreadGroup group) {
        int nAlloc = group.activeCount();
        int n;
        Thread[] threads;

        do {
            nAlloc *= 2;
            threads = new Thread[nAlloc];
            n = group.enumerate(threads);
        } while (n == nAlloc);

        return java.util.Arrays.copyOf(threads, n);
    }

    private File[] getClassPathFiles(boolean testMode) throws MojoExecutionException {
        List<File> files = new ArrayList<>();

        try {
            if (testMode) {
                // Project test resources
                addResources(this.testClassesDirectory, this.project.getTestResources(), files);

                // Project test classes
                files.add(this.testClassesDirectory);
            }

            // Project resources
            addResources(this.classesDirectory, this.project.getResources(), files);

            // Project classes
            files.add(this.classesDirectory);

            // Project dependencies (scope is dependent upon the @Mojo annotation and the already executed phase)
            addArtifacts(this.project.getArtifacts(), files);
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Unable to build classpath", e);
        }

        return files.toArray(new File[files.size()]);
    }

    private void addArtifacts(Collection<Artifact> artifacts, List<File> files) throws MalformedURLException {
        for (Artifact artifact : artifacts) {
            File file = artifact.getFile();
            if (file.getName().endsWith(".jar")) {
                files.add(file);
            }
        }
    }

    private void addResources(File classesDirectory, List<Resource> resources,
            List<File> files) throws MalformedURLException {
        for (Resource resource : resources) {
            File directory = new File(resource.getDirectory());
            files.add(directory);
            removeDuplicatesFromOutputDirectory(classesDirectory, directory);
        }
    }

    private void removeDuplicatesFromOutputDirectory(File outputDirectory, File originDirectory) {
        if (originDirectory.isDirectory()) {
            String[] list = originDirectory.list();
            if (list != null) {
                for (String name : list) {
                    File targetFile = new File(outputDirectory, name);
                    if (targetFile.exists() && targetFile.canWrite()) {
                        if (!targetFile.isDirectory()) {
                            if (!targetFile.delete()) {
                                getLog().warn("Unable to delete duplicate " + targetFile.getAbsolutePath());
                            }
                        } else {
                            removeDuplicatesFromOutputDirectory(targetFile,
                                    new File(originDirectory, name));
                        }
                    }
                }
            }
        }
    }

    private class IsolatedThreadGroup extends ThreadGroup {
        private Throwable uncaughtException;

        IsolatedThreadGroup(String name) {
            super(name);
        }

        public void uncaughtException(Thread thread, Throwable throwable) {
            if (throwable instanceof ThreadDeath) {
                return;
            }

            synchronized (this) {
                if (uncaughtException == null) {
                    uncaughtException = throwable;
                }
            }

            getLog().warn(throwable);
        }
    }
}
