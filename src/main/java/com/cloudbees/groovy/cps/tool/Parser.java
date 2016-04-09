package com.cloudbees.groovy.cps.tool;

import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JOp;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;
import com.sun.codemodel.writer.SingleStreamCodeWriter;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Types.DefaultSymbolVisitor;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import hudson.remoting.Which;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementScanner7;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("Since15")
public class Parser {

    private Types types;
    private Elements elements;
    private Trees trees;
    private JavacTask javac;

    private final JCodeModel codeModel = new JCodeModel();
    private final JDefinedClass $CpsDefaultGroovyMethods;

    // class references
    private final JClass $Caller;
    private final JClass $CpsFunction;
    private final JClass $CpsCallableInvocation;
    private final JClass $Builder;

    public Parser() {
        try {
            $CpsDefaultGroovyMethods = codeModel._class("com.cloudbees.groovy.cps.CpsDefaultGroovyMethods");
        } catch (JClassAlreadyExistsException e) {
            throw new AssertionError(e);
        }
        $Caller                = codeModel.ref("com.cloudbees.groovy.cps.impl.Caller");
        $CpsFunction           = codeModel.ref("com.cloudbees.groovy.cps.impl.CpsFunction");
        $CpsCallableInvocation = codeModel.ref("com.cloudbees.groovy.cps.impl.CpsCallableInvocation");
        $Builder               = codeModel.ref("com.cloudbees.groovy.cps.Builder");
    }

    public static void main(String[] args) throws Exception {
        new Parser().foo(new File("DefaultGroovyMethods.java"));
    }

    public void foo(File dgmJava) throws Exception {
        StandardJavaFileManager fileManager = null;
        try {
            JavaCompiler javac1 = JavacTool.create();
            DiagnosticListener<JavaFileObject> errorListener = createErrorListener();
            fileManager = javac1.getStandardFileManager(errorListener, Locale.getDefault(), Charset.defaultCharset());


            fileManager.setLocation(StandardLocation.CLASS_PATH,
                    Collections.singleton(Which.jarFile(GroovyShell.class)));

            // annotation processing appears to cause the source files to be reparsed
            // (even though I couldn't find exactly where it's done), which causes
            // Tree symbols created by the original JavacTask.parse() call to be thrown away,
            // which breaks later processing.
            // So for now, don't perform annotation processing
            List<String> options = Arrays.asList("-proc:none");

            Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromFiles(
                    Collections.singleton(dgmJava));
            JavaCompiler.CompilationTask task = javac1.getTask(null, fileManager, errorListener, options, null, files);
            javac = (JavacTask) task;
            trees = Trees.instance(javac);
            elements = javac.getElements();
            types = javac.getTypes();

            Iterable<? extends CompilationUnitTree> parsed = javac.parse();
            javac.analyze();

            CompilationUnitTree dgmCut = getDefaultGroovyMethodCompilationUnitTree(parsed);

            final DeclaredType closureType = types.getDeclaredType(elements.getTypeElement(Closure.class.getName()));

            ClassSymbol dgm = (ClassSymbol) elements.getTypeElement("org.codehaus.groovy.runtime.DefaultGroovyMethods");
            dgm.accept(new ElementScanner7<Void,Void>() {
                public Void visitExecutable(ExecutableElement e, Void __) {
                    if (isGdkMethodWithClosureArgument(e)) {
                        translate(dgmCut, e);
                        // DEBUG
                        try {
                            codeModel.build(new SingleStreamCodeWriter(System.out));
                        } catch (IOException x) {
                            x.printStackTrace();
                        }
                        System.exit(0);
                    }
                    return null;
                }

                /**
                 * Criteria:
                 *      1. public static method
                 *      2. has a Closure parameter in one of the arguments, not in the receiver
                 */
                private boolean isGdkMethodWithClosureArgument(ExecutableElement e) {
                    return e.getKind() == ElementKind.METHOD
                        && e.getModifiers().containsAll(PUBLIC_STATIC)
                        && e.getParameters().subList(1, e.getParameters().size()).stream()
                            .anyMatch(p -> types.isAssignable(p.asType(), closureType));
                }
            },null);
        } finally {
            if (fileManager!=null)
                fileManager.close();
        }

        codeModel.build(new SingleStreamCodeWriter(System.out));
    }

    private CompilationUnitTree getDefaultGroovyMethodCompilationUnitTree(Iterable<? extends CompilationUnitTree> parsed) {
        for (CompilationUnitTree cut : parsed) {
            for (Tree t : cut.getTypeDecls()) {
                if (t.getKind() == Kind.CLASS) {
                    ClassTree ct = (ClassTree)t;
                    if (ct.getSimpleName().toString().equals("DefaultGroovyMethods")) {
                        return cut;
                    }
                }
            }
        }
        throw new IllegalStateException("DefaultGroovyMethods wasn't parsed");
    }

    /**
     * @param e
     *      Method in {@link DefaultGroovyMethods} to translate.
     */
    private void translate(final CompilationUnitTree cut, ExecutableElement e) {
        JMethod m = $CpsDefaultGroovyMethods.method(JMod.PUBLIC | JMod.STATIC, t(e.getReturnType()), n(e));
        for (TypeParameterElement p : e.getTypeParameters()) {
            m.generify(n(p));   // TODO: bound
        }
        List<JVar> params = new ArrayList<>();
        for (VariableElement p : e.getParameters()) {
            params.add(m.param(t(p.asType()), n(p)));
        }

        // TODO: preamble
//        m.body()._if(JOp.cand(
//            JOp.not($Caller.staticInvoke("isAsynchronous").arg()),
//            JOp.not($Caller.staticInvoke("isAsynchronous")
//                    .arg($CpsDefaultGroovyMethods.dotclass())
//                    .arg(n(e)))
//                )
//
//        )


        JVar $b = m.body().decl($Builder, "b", JExpr._new($Builder).arg(JExpr.invoke("loc").arg("each")));
        JInvocation f = JExpr._new($CpsFunction);

        // parameter names
        JInvocation paramNames = codeModel.ref(Arrays.class).staticInvoke("asList");
        for (VariableElement p : e.getParameters()) {
            paramNames.arg(n(p.getSimpleName()));
        }
        f.arg(paramNames);

        // translate the method body into an expression that invokes Builder
        f.arg(trees.getTree(e).getBody().accept(new SimpleTreeVisitor<JExpression,Void>() {
            private JExpression visit(Tree t) {
                if (t==null)    return JExpr._null();
                return visit(t, null);
            }

            /**
             * Maps a symbol to its source location.
             */
            private JExpression loc(Tree t) {
                long pos = trees.getSourcePositions().getStartPosition(cut, t);
                return JExpr.lit(cut.getLineMap().getLineNumber(pos));
            }

            @Override
            public JExpression visitWhileLoop(WhileLoopTree wt, Void __) {
                return $b.invoke("while_")
                        .arg(JExpr._null()) // TODO: label
                        .arg(visit(wt.getCondition()))
                        .arg(visit(wt.getStatement()));
            }

            @Override
            public JExpression visitMethodInvocation(MethodInvocationTree mt, Void __) {
                ExpressionTree ms = mt.getMethodSelect();
                if (ms instanceof MemberSelectTree) {
                    MemberSelectTree mst = (MemberSelectTree) ms;
                    JInvocation inv = $b.invoke("functionCall")
                            .arg(loc(mt))
                            .arg(visit(mst.getExpression()))
                            .arg(JExpr.lit(n(mst.getIdentifier())));
                    for (ExpressionTree arg : mt.getArguments()) {
                        inv.arg(visit(arg));
                    }
                    return inv;
                } else {
                    // TODO: figure out what can come here
                    throw new UnsupportedOperationException();
                }
            }

            @Override
            public JExpression visitVariable(VariableTree vt, Void __) {
                return $b.invoke("declareVariable")
                        .arg(loc(vt))
                        .arg(dotclass(t(vt.getType())))
                        .arg(n(vt.getName()))
                        .arg(visit(vt.getInitializer()));
            }

            @Override
            public JExpression visitIdentifier(IdentifierTree it, Void __) {
                JCIdent idt = (JCIdent) it;
                return idt.sym.accept(new DefaultSymbolVisitor<JExpression, Void>() {
                    @Override
                    public JExpression visitClassSymbol(ClassSymbol cs, Void __) {
                        return $b.invoke("constant").arg(dotclass(t(cs.asType())));
                    }

                    @Override
                    public JExpression visitVarSymbol(VarSymbol s, Void __) {
                        return $b.invoke("localVariable").arg(n(s.name));
                    }

                    @Override
                    public JExpression visitSymbol(Symbol s, Void __) {
                        throw new UnsupportedOperationException();
                    }
                }, __);
            }

            @Override
            public JExpression visitBlock(BlockTree bt, Void __) {
                JInvocation inv = $b.invoke("block");
                bt.getStatements().stream().forEach( s -> inv.arg(visit(s)));
                return inv;
            }

            @Override
            public JExpression visitReturn(ReturnTree rt, Void __) {
                return $b.invoke("return_").arg(visit(rt.getExpression()));
            }

            @Override
            protected JExpression defaultAction(Tree node, Void aVoid) {
                throw new UnsupportedOperationException();
            }
        }, null));

        JVar $f = m.body().decl($CpsFunction, "f", f);
        m.body()._throw(appendArgs(JExpr._new($CpsCallableInvocation).arg($f).arg(JExpr._null()), params));
    }

    private JExpression appendArgs(JInvocation inv, List<JVar> params) {
        params.stream().forEach(inv::arg);
        return inv;
    }

    private JType t(Tree t) {
        throw new UnsupportedOperationException();
    }

    private JType t(TypeMirror m) {
        if (m.getKind().isPrimitive())
            return JType.parse(codeModel,m.toString());
        return codeModel.directClass(m.toString());
    }

    private JExpression dotclass(JType t) {
        // TODO: fix this in codemodel
        return JExpr.dotclass(t.boxify());

    }

    private String n(Element e) {
        return e.getSimpleName().toString();
    }
    private String n(Name n) {
        return n.toString();
    }

    protected DiagnosticListener<JavaFileObject> createErrorListener() {
        return diagnostic -> {
            //TODO report
            System.out.println(diagnostic);
        };
    }

    private static final Collection<Modifier> PUBLIC_STATIC = Arrays.asList(Modifier.PUBLIC, Modifier.STATIC);

}
