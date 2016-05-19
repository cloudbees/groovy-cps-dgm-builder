package com.cloudbees.groovy.cps.tool;

import com.sun.codemodel.CodeWriter;
import com.sun.codemodel.writer.FileCodeWriter;
import groovy.lang.GroovyShell;
import hudson.remoting.Which;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.util.Collections;

/**
 * Command line invocation for test run.
 *
 * @author Kohsuke Kawaguchi
 */
public class CliDriver extends Driver {
    public static void main(String[] args) throws Exception {
        new CliDriver().run();
    }

    @Override
    protected File resolveSourceJar() throws IOException {
        return Which.jarFile(CliDriver.class.getClassLoader().getResource("groovy/lang/GroovyShell.java"));
    }

    @Override
    protected void setupClassPath(StandardJavaFileManager fileManager) throws IOException {
        fileManager.setLocation(StandardLocation.CLASS_PATH,
                Collections.singleton(Which.jarFile(GroovyShell.class)));
    }

    @Override
    protected CodeWriter createWriter() throws IOException {
        File dir = new File("out");
        dir.mkdirs();
        return new FileCodeWriter(dir);
    }

    @Override
    protected DiagnosticListener<JavaFileObject> createErrorListener() {
        return System.out::println;
    }

}
