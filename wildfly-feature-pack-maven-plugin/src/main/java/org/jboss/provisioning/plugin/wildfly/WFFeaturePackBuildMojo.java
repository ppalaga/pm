/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.provisioning.plugin.wildfly;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Properties;

import javax.xml.stream.XMLStreamException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.provisioning.Constants;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.GAV;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.descr.FeaturePackDescription;
import org.jboss.provisioning.descr.FeaturePackDescription.Builder;
import org.jboss.provisioning.descr.PackageDescription;
import org.jboss.provisioning.plugin.FPMavenErrors;
import org.jboss.provisioning.plugin.util.MavenPluginUtil;
import org.jboss.provisioning.plugin.wildfly.featurepack.build.model.FeaturePackBuild;
import org.jboss.provisioning.plugin.wildfly.featurepack.build.model.FeaturePackBuildModelParser;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.CopyArtifact;
import org.jboss.provisioning.util.IoUtils;
import org.jboss.provisioning.util.PropertyUtils;
import org.jboss.provisioning.util.ZipUtils;
import org.jboss.provisioning.xml.FeaturePackXMLWriter;
import org.jboss.provisioning.xml.PackageXMLWriter;

/**
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "wf-build", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class WFFeaturePackBuildMojo extends AbstractMojo {

    private static final boolean OS_WINDOWS = PropertyUtils.isWindows();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * The configuration file used for feature pack.
     */
    @Parameter(alias = "config-file", defaultValue = "feature-pack-build.xml", property = "wildfly.feature.pack.configFile")
    private String configFile;

    /**
     * The directory the configuration file is located in.
     */
    @Parameter(alias = "config-dir", defaultValue = "${basedir}", property = "wildfly.feature.pack.configDir")
    private File configDir;

    /**
     * A path relative to {@link #configDir} that represents the directory under which of resources such as
     * {@code configuration/standalone/subsystems.xml}, {modules}, {subsystem-templates}, etc.
     */
    @Parameter(alias = "resources-dir", defaultValue = "src/main/resources", property = "wildfly.feature.pack.resourcesDir", required = true)
    private String resourcesDir;

    /**
     * The name of the server.
     */
    @Parameter(alias = "server-name", defaultValue = "${project.build.finalName}", property = "wildfly.feature.pack.serverName")
    private String serverName;

    /**
     * The directory for the built artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "wildfly.feature.pack.buildName")
    private String buildName;

    private MavenProjectArtifactVersions artifactVersions;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        artifactVersions = MavenProjectArtifactVersions.getInstance(project);

        /* normalize resourcesDir */
        if (!resourcesDir.isEmpty()) {
            switch (resourcesDir.charAt(0)) {
            case '/':
            case '\\':
                break;
            default:
                resourcesDir = "/" + resourcesDir;
                break;
            }
        }

        final Path targetResources = Paths.get(buildName, "resources");
        try {
            IoUtils.copy(Paths.get(configDir.getAbsolutePath() + resourcesDir), targetResources);
        } catch (IOException e1) {
            throw new MojoExecutionException(Errors.copyFile(Paths.get(configDir.getAbsolutePath() + resourcesDir), targetResources));
        }

        processFeaturePackBuildConfig(targetResources);

        final Path workDir = Paths.get(buildName, "layout");
        System.out.println("WFFeaturePackBuildMojo.execute " + workDir);
        IoUtils.recursiveDelete(workDir);
        final String fpArtifactId = project.getArtifactId() + "-new";
        final Path fpDir = workDir.resolve(project.getGroupId()).resolve(fpArtifactId).resolve(project.getVersion());
        final Path fpPackagesDir = fpDir.resolve(Constants.PACKAGES);

        System.out.println("WFFeaturePackBuildMojo.execute " + targetResources);
        final Path srcModulesDir = targetResources.resolve("modules").resolve("system").resolve("layers").resolve("base");
        if(!Files.exists(srcModulesDir)) {
            throw new MojoExecutionException(Errors.pathDoesNotExist(srcModulesDir));
        }

        final Builder fpBuilder = FeaturePackDescription.builder(new GAV(project.getGroupId(), fpArtifactId, project.getVersion()));
        try {
            processContent(fpBuilder, targetResources.resolve(Constants.CONTENT), fpPackagesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process content", e);
        }

        final PackageDescription.Builder modulesBuilder = PackageDescription.builder("modules");

        try {
            processModules(fpBuilder, modulesBuilder, targetResources, srcModulesDir, fpPackagesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }

        final PackageDescription modulesPkg = modulesBuilder.build();
        writeXml(modulesPkg, fpDir.resolve(Constants.PACKAGES).resolve(modulesPkg.getName()));

        try {
            processConfiguration(targetResources.resolve("configuration"), fpDir.resolve("resources"));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to copy configuration", e);
        }

        fpBuilder.addProvisioningPlugin(new GAV("org.jboss.pm", "wildfly-feature-pack-maven-plugin", "1.0.0.Alpha-SNAPSHOT"));

        final FeaturePackDescription fpDescr = fpBuilder.addTopPackage(modulesPkg).build();
        try {
            FeaturePackXMLWriter.INSTANCE.write(fpDescr, fpDir.resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeXml(fpDir.resolve(Constants.FEATURE_PACK_XML)));
        }

        try {
            repoSystem.install(repoSession, MavenPluginUtil.getInstallLayoutRequest(workDir));
        } catch (InstallationException e) {
            throw new MojoExecutionException(FPMavenErrors.featurePackInstallation(), e);
        }
    }

    private void processFeaturePackBuildConfig(final Path targetResources) {
        try (InputStream configStream = Files.newInputStream(Paths.get(configDir.getAbsolutePath(), configFile))) {
            Properties properties = new Properties();
            properties.putAll(project.getProperties());
            properties.putAll(System.getProperties());
            properties.put("project.version", project.getVersion()); //TODO: figure out the correct way to do this
            final FeaturePackBuild build = new FeaturePackBuildModelParser(new MapPropertyResolver(properties)).parse(configStream);
            for(CopyArtifact copyArtifact : build.getCopyArtifacts()) {
                final String gavString = artifactVersions.getVersion(copyArtifact.getArtifact());
                final Path jarSrc = resolveArtifact(GAV.fromString(gavString), "jar");
                String location = copyArtifact.getToLocation();
                if (location.endsWith("/")) {
                    // if the to location ends with a / then it is a directory
                    // so we need to append the artifact name
                    location += jarSrc.getFileName();
                }

                final Path jarTarget = targetResources.resolve("content").resolve(location);
                Files.createDirectories(jarTarget.getParent());
                if (copyArtifact.isExtract()) {
                    ZipUtils.unzip(jarSrc, jarTarget);
                } else {
                    IoUtils.copy(jarSrc, jarTarget);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void processConfiguration(Path configurationDir, Path resourcesDir) throws IOException {
        IoUtils.copy(configurationDir, resourcesDir.resolve("wildfly").resolve("configuration"));
    }

    private void processContent(FeaturePackDescription.Builder fpBuilder, Path contentDir, Path packagesDir) throws IOException, MojoExecutionException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(contentDir)) {
            for(Path p : stream) {
                final String pkgName = p.getFileName().toString();
                final Path pkgDir = packagesDir.resolve(pkgName);
                IoUtils.copy(p, pkgDir.resolve(Constants.CONTENT).resolve(p.getFileName()));
                final PackageDescription pkgDescr = PackageDescription.builder(pkgName).build();
                writeXml(pkgDescr, pkgDir);
                fpBuilder.addTopPackage(pkgDescr);
            }
        }
    }

    private void processModules(FeaturePackDescription.Builder fpBuilder, PackageDescription.Builder modulesBuilder,
            Path resourcesDir, Path modulesDir, Path packagesDir) throws IOException {
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if(!file.getFileName().toString().equals("module.xml")) {
                    return FileVisitResult.CONTINUE;
                }
                final String packageName = modulesDir.relativize(file.getParent()).toString().replace(File.separatorChar, '.');
                final Path packageDir = packagesDir.resolve(packageName);
                final Path targetXml = packageDir.resolve(Constants.CONTENT).resolve(resourcesDir.relativize(file));
                Files.createDirectories(targetXml.getParent());

                IoUtils.copy(file.getParent(), targetXml.getParent());

                final PackageDescription pkgDescr = PackageDescription.builder(packageName).build();
                try {
                    PackageXMLWriter.INSTANCE.write(pkgDescr, packageDir.resolve(Constants.PACKAGE_XML));
                } catch (XMLStreamException e) {
                    throw new IOException(Errors.writeXml(packageDir.resolve(Constants.PACKAGE_XML)), e);
                }
                modulesBuilder.addDependency(packageName);
                fpBuilder.addPackage(pkgDescr);

                final String moduleXmlContents = IoUtils.readFile(file);
                final BuildPropertyReplacer buildPropertyReplacer = new BuildPropertyReplacer(new ModuleArtifactPropertyResolver(artifactVersions));
                IoUtils.writeFile(targetXml, buildPropertyReplacer.replaceProperties(moduleXmlContents));

                if (!OS_WINDOWS) {
                    Files.setPosixFilePermissions(targetXml, Files.getPosixFilePermissions(file));
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeXml(PackageDescription pkgDescr, Path dir) throws MojoExecutionException {
        try {
            Files.createDirectories(dir);
            PackageXMLWriter.INSTANCE.write(pkgDescr, dir.resolve(Constants.PACKAGE_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeXml(dir.resolve(Constants.PACKAGE_XML)));
        }
    }

    private Path resolveArtifact(GAV gav, String extension) throws ProvisioningException {
        final ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, getArtifactRequest(gav, extension));
        } catch (ArtifactResolutionException e) {
            throw new ProvisioningException(FPMavenErrors.artifactResolution(gav), e);
        }
        if(!result.isResolved()) {
            throw new ProvisioningException(FPMavenErrors.artifactResolution(gav));
        }
        if(result.isMissing()) {
            throw new ProvisioningException(FPMavenErrors.artifactMissing(gav));
        }
        return Paths.get(result.getArtifact().getFile().toURI());
    }

    private ArtifactRequest getArtifactRequest(GAV gav, String extension) {
        final ArtifactRequest req = new ArtifactRequest();
        req.setArtifact(new DefaultArtifact(gav.getGroupId(), gav.getArtifactId(), extension, gav.getVersion()));
        req.setRepositories(remoteRepos);
        return req;
    }
}
