package com.cloudbees.groovy.cps.tool;


import com.sun.codemodel.CodeWriter;
import com.sun.codemodel.writer.FileCodeWriter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Mojo(name="generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class MojoImpl extends AbstractMojo {
    /**
     * The maven project.
     */
    @Component
    MavenProject project;

    @Component
    MavenProjectHelper projectHelper;

    /**
     * List of Remote Repositories used by the resolver
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}",readonly = true, required = true)
    List<ArtifactRepository> remoteRepos;

    @Component
    @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
    ArtifactRepository localRepository;

    @Component
    ArtifactResolver artifactResolver;

    @Component
    ArtifactFactory artifactFactory;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Artifact groovy = findGroovyArtifact();
            artifactResolver.resolve(groovy, remoteRepos, localRepository);

            Artifact srcZip = artifactFactory.createArtifactWithClassifier(
                    groovy.getGroupId(), groovy.getArtifactId(), groovy.getVersion(), "jar", "sources");
            artifactResolver.resolve(srcZip, remoteRepos, localRepository);

            File dir = new File(project.getBasedir(), "target/generated-sources/dgm");
            dir.mkdirs();
            project.addCompileSourceRoot(dir.getAbsolutePath());

            File upToDate = new File(dir,"upToDate");
            String id = StringUtils.join(new Object[]{
                    groovy.getGroupId(),
                    groovy.getArtifactId(),
                    groovy.getVersion()
            },":");

            if (upToDate.exists() && FileUtils.fileRead(upToDate).equals(id)) {
                getLog().info("CpsDefaultGroovyMethods is up to date");
                return;
            }

            new Driver() {
                @Override
                protected CodeWriter createWriter() throws IOException {
                    return new FileCodeWriter(dir);
                }

                @Override
                protected File resolveSourceJar() throws IOException {
                    return srcZip.getFile();
                }

                @Override
                protected void setupClassPath(StandardJavaFileManager fileManager) throws IOException {
                    fileManager.setLocation(StandardLocation.CLASS_PATH,
                            Collections.singleton(groovy.getFile()));
                }

                @Override
                protected DiagnosticListener<JavaFileObject> createErrorListener() {
                    return d -> {
                        switch (d.getKind()) {
                        case ERROR:
                            getLog().error(d.toString());
                            break;
                        case WARNING:
                            getLog().warn(d.toString());
                            break;
                        case MANDATORY_WARNING:
                            getLog().warn(d.toString());
                            break;
                        case NOTE:
                            getLog().info(d.toString());
                            break;
                        case OTHER:
                            getLog().debug(d.toString());
                            break;
                        }
                    };
                }
            }.run();

            FileUtils.fileWrite(upToDate.getPath(),id);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate DefaultGroovyMethods",e);
        }
    }

    private Artifact findGroovyArtifact() throws MojoExecutionException {
        for (Artifact a : (Set<Artifact>)project.getArtifacts()) {
            String artifactId = a.getArtifactId();
            if (a.getGroupId().equals("org.codehaus.groovy")
            && (artifactId.equals("groovy") || artifactId.equals("groovy-all"))) {
                return a;
            }
        }
        throw new MojoExecutionException("There's no groovy in this project's dependencies!");
    }
}