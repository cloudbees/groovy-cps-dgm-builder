package com.cloudbees.groovy.cps.tool;

import com.sun.codemodel.writer.FileCodeWriter;
import com.sun.tools.javac.api.JavacTool;
import groovy.lang.GroovyShell;
import hudson.remoting.Which;

import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.File;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static java.util.Arrays.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class Driver {
    public static void main(String[] args) throws Exception {
        new Driver().run();
    }

    public void run() throws Exception {
        JavaCompiler javac = JavacTool.create();
        DiagnosticListener<JavaFileObject> errorListener = createErrorListener();

        try (StandardJavaFileManager fileManager = javac.getStandardFileManager(errorListener, Locale.getDefault(), Charset.defaultCharset())) {
            fileManager.setLocation(StandardLocation.CLASS_PATH,
                    Collections.singleton(Which.jarFile(GroovyShell.class)));

            // annotation processing appears to cause the source files to be reparsed
            // (even though I couldn't find exactly where it's done), which causes
            // Tree symbols created by the original JavacTask.parse() call to be thrown away,
            // which breaks later processing.
            // So for now, don't perform annotation processing
            List<String> options = asList("-proc:none");

            Translator t = new Translator(javac.getTask(null, fileManager, errorListener, options, null,
                    fileManager.getJavaFileObjectsFromFiles(
                            Collections.singleton(new File("DefaultGroovyMethods.java")))));

            t.translate(
                    "org.codehaus.groovy.runtime.DefaultGroovyMethods",
                    "com.cloudbees.groovy.cps.CpsDefaultGroovyMethods");

            File dir = new File("out");
            dir.mkdirs();
            t.generateTo(new FileCodeWriter(dir));
        }
    }

    private DiagnosticListener<JavaFileObject> createErrorListener() {
        return System.out::println;
    }
}
