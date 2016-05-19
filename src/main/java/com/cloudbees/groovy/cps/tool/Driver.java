package com.cloudbees.groovy.cps.tool;

import com.sun.codemodel.CodeWriter;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.file.RelativePath.RelativeDirectory;
import com.sun.tools.javac.file.ZipArchive;
import groovy.lang.Closure;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

import static java.util.Arrays.*;

/**
 * Setup Javac and runs {@link Translator} with right configuration.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class Driver {

    public void run() throws Exception {
        JavaCompiler javac = JavacTool.create();
        DiagnosticListener<JavaFileObject> errorListener = createErrorListener();

        try (StandardJavaFileManager fileManager = javac.getStandardFileManager(errorListener, Locale.getDefault(), Charset.defaultCharset())) {
            setupClassPath(fileManager);

            File groovySrcJar = resolveSourceJar();

            // classes to translate
            List<String> fileNames = asList("DefaultGroovyMethods", "ProcessGroovyMethods", "DefaultGroovyStaticMethods");

            List<JavaFileObject> src = new ArrayList<>();
            ZipArchive a = new ZipArchive((JavacFileManager) fileManager, new ZipFile(groovySrcJar));

            for (String name : fileNames) {
                src.add(a.getFileObject(new RelativeDirectory("org/codehaus/groovy/runtime"),name+".java"));
            }

            // annotation processing appears to cause the source files to be reparsed
            // (even though I couldn't find exactly where it's done), which causes
            // Tree symbols created by the original JavacTask.parse() call to be thrown away,
            // which breaks later processing.
            // So for now, don't perform annotation processing
            List<String> options = asList("-proc:none");

            Translator t = new Translator(javac.getTask(null, fileManager, errorListener, options, null, src));

            final DeclaredType closureType = t.types.getDeclaredType(t.elements.getTypeElement(Closure.class.getName()));

            /**
             * Criteria:
             *      1. public static method
             *      2. has a Closure parameter in one of the arguments, not in the receiver
             */
            Predicate<ExecutableElement> selector = (e) -> {
                return e.getKind() == ElementKind.METHOD
                    && e.getModifiers().containsAll(PUBLIC_STATIC)
                    && e.getParameters().subList(1, e.getParameters().size()).stream()
                        .anyMatch(p -> t.types.isAssignable(p.asType(), closureType))
                    && !EXCLUSIONS.contains(e.getEnclosingElement().getSimpleName().toString()+"."+e.getSimpleName().toString());
            };

            for (String name : fileNames) {
                t.translate(
                        "org.codehaus.groovy.runtime."+name,
                        "com.cloudbees.groovy.cps.Cps"+name,
                        selector);
            }


            CodeWriter cw = createWriter();
            try {
                t.generateTo(cw);
            } finally {
                cw.close();
            }
        }
    }

    /**
     * Controls where the generated source files are written to.
     */
    protected abstract CodeWriter createWriter() throws IOException;

    /**
     * Locate the groovy source jar that contains DefaultGroovyMethods.java to compile
     */
    protected abstract File resolveSourceJar() throws IOException;

    /**
     * Set up classpath necessary to invoke javac.
     */
    protected abstract void setupClassPath(StandardJavaFileManager fileManager) throws IOException;

    protected abstract DiagnosticListener<JavaFileObject> createErrorListener();

    private static final Collection<Modifier> PUBLIC_STATIC = Arrays.asList(Modifier.PUBLIC, Modifier.STATIC);

    private static final Set<String> EXCLUSIONS = new HashSet<>(Arrays.asList(
            "DefaultGroovyMethods.runAfter", /* use anonymous inner class we can't handle */
            "DefaultGroovyMethods.accept" /* launches a thread */,
            "DefaultGroovyMethods.filterLine",    /* anonymous inner classes */
            "DefaultGroovyMethods.dropWhile","DefaultGroovyMethods.takeWhile" /* TODO: translate inner classes to support this*/,

            "ProcessGroovyMethods.withWriter",
            "ProcessGroovyMethods.withOutputStream" /* anonymous inner class */
    ));
}
