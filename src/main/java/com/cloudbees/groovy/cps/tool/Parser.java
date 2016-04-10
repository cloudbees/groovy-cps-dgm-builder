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
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
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
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
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
        f.arg(codeModel.ref(Arrays.class).staticInvoke("asList").tap( inv -> {
            for (VariableElement p : e.getParameters()) {
                inv.arg(n(p.getSimpleName()));
            }
        }));

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
                JInvocation inv = $b.invoke("functionCall")
                        .arg(loc(mt));

                if (ms instanceof MemberSelectTree) {
                    MemberSelectTree mst = (MemberSelectTree) ms;
                    inv
                        .arg(visit(mst.getExpression()))
                        .arg(JExpr.lit(n(mst.getIdentifier())));
                } else
                if (ms instanceof JCIdent) {
                    JCIdent it = (JCIdent) ms;
                    if (!it.sym.owner.toString().equals(DefaultGroovyMethods.class.getName())) {
                        // TODO: static import
                        throw new UnsupportedOperationException();
                    }
                    inv
                        .arg($b.invoke("this_"))
                        .arg(JExpr.lit(n(it.getName())));
                } else {
                // TODO: figure out what can come here
                throw new UnsupportedOperationException();
                }

                for (ExpressionTree arg : mt.getArguments()) {
                    inv.arg(visit(arg));
                }
                return inv;
            }

            @Override
            public JExpression visitVariable(VariableTree vt, Void __) {
                return $b.invoke("declareVariable")
                        .arg(loc(vt))
                        .arg(t(vt.getType()).dotclass())
                        .arg(n(vt.getName()))
                        .arg(visit(vt.getInitializer()));
            }

            @Override
            public JExpression visitIdentifier(IdentifierTree it, Void __) {
                JCIdent idt = (JCIdent) it;
                return idt.sym.accept(new DefaultSymbolVisitor<JExpression, Void>() {
                    @Override
                    public JExpression visitClassSymbol(ClassSymbol cs, Void __) {
                        return $b.invoke("constant").arg(t(cs.asType()).dotclass());
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

            /**
             * When used outside {@link MethodInvocationTree}, this is property access.
             */
            @Override
            public JExpression visitMemberSelect(MemberSelectTree mt, Void __) {
                return $b.invoke("property")
                        .arg(loc(mt))
                        .arg(visit(mt.getExpression()))
                        .arg(n(mt.getIdentifier()));
            }

            @Override
            public JExpression visitTypeCast(TypeCastTree tt, Void __) {
                return $b.invoke("cast")
                        .arg(loc(tt))
                        .arg(visit(tt.getExpression()))
                        .arg(t(tt.getType()).dotclass())
                        .arg(JExpr.lit(false));
            }


            @Override
            public JExpression visitIf(IfTree it, Void __) {
                JInvocation inv = $b.invoke("if_")
                        .arg(visit(it.getCondition()))
                        .arg(visit(it.getThenStatement()));
                if (it.getElseStatement()!=null)
                    inv.arg(visit(it.getElseStatement()));
                return inv;
            }

            @Override
            public JExpression visitNewClass(NewClassTree nt, Void __) {
                // TODO: outer class
                if (nt.getEnclosingExpression()!=null)
                    throw new UnsupportedOperationException();

                return $b.invoke("new_").tap(inv -> {
                    inv.arg(loc(nt));
                    inv.arg(t(((JCTree) nt).type).dotclass());
                    for (ExpressionTree et : nt.getArguments()) {
                        inv.arg(visit(et));
                    }
                });
            }

            @Override
            public JExpression visitExpressionStatement(ExpressionStatementTree et, Void __) {
                return visit(et.getExpression());
            }

            @Override
            public JExpression visitLiteral(LiteralTree lt, Void __) {
                return JExpr.literal(lt.getValue());
            }

            @Override
            public JExpression visitParenthesized(ParenthesizedTree pt, Void __) {
                return visit(pt.getExpression());
            }

            @Override
            public JExpression visitBinary(BinaryTree bt, Void __) {
                JExpression lhs = visit(bt.getLeftOperand());
                JExpression rhs = visit(bt.getRightOperand());
                switch (bt.getKind()) {
                case EQUAL_TO:      return JOp.eq(lhs,rhs);
                }
                throw new UnsupportedOperationException();
            }

            @Override
            public JExpression visitUnary(UnaryTree ut, Void __) {
                JExpression e = visit(ut.getExpression());
                switch (ut.getKind()) {
                case POSTFIX_INCREMENT: return JOp.incr(e);
                }
                throw new UnsupportedOperationException();
            }

            @Override
            public JExpression visitAssignment(AssignmentTree at, Void __) {
                return $b.invoke("assign")
                        .arg(loc(at))
                        .arg(visit(at.getVariable()))
                        .arg(visit(at.getExpression()));
            }

            @Override
            public JExpression visitNewArray(NewArrayTree nt, Void __) {
                if (nt.getInitializers()!=null)
                    throw new UnsupportedOperationException();
                return $b.invoke("newArray").tap(inv -> {
                    inv.arg(loc(nt));
                    inv.arg(t(nt.getType()).dotclass());
                    nt.getDimensions().stream().forEach( d -> inv.arg(visit(d)));
                });
            }

            @Override
            public JExpression visitForLoop(ForLoopTree ft, Void __) {
                return $b.invoke("forLoop")
                        .arg(JExpr._null())
                        .arg($b.invoke("sequence").tap(inv -> ft.getInitializer().forEach(i -> inv.arg(visit(i)))))
                        .arg(visit(ft.getCondition()))
                        .arg($b.invoke("sequence").tap(inv -> ft.getUpdate().forEach(i -> inv.arg(visit(i)))))
                        .arg(visit(ft.getStatement()));
            }

            @Override
            public JExpression visitEnhancedForLoop(EnhancedForLoopTree et, Void __) {
                return $b.invoke("forInLoop")
                        .arg(loc(et))
                        .arg(JExpr._null())
                        .arg(t(et.getVariable().getType()).dotclass())
                        .arg(n(et.getVariable().getName()))
                        .arg(visit(et.getExpression()))
                        .arg(visit(et.getStatement()));
            }

            @Override
            public JExpression visitArrayAccess(ArrayAccessTree at, Void __) {
                return $b.invoke("array")
                        .arg(loc(at))
                        .arg(visit(at.getExpression()))
                        .arg(visit(at.getIndex()));
            }

            @Override
            protected JExpression defaultAction(Tree node, Void aVoid) {
                throw new UnsupportedOperationException();
            }
        }, null));

        JVar $f = m.body().decl($CpsFunction, "f", f);
        m.body()._throw(JExpr._new($CpsCallableInvocation).tap(inv -> {
            inv.arg($f);
            inv.arg(JExpr._null());
            for (JVar p : params) {
                inv.arg(p);
            }
        }));
    }

    /**
     * Convert a type representation from javac to codemodel.
     */
    private JType t(Tree t) {
        return t.accept(new TypeTranslator(), null);
    }

    private JType t(TypeMirror m) {
        if (m.getKind().isPrimitive())
            return JType.parse(codeModel,m.toString());
        return codeModel.directClass(m.toString());
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

    /**
     * Converts a type expression from javac to codemodel.
     */
    private class TypeTranslator extends SimpleTreeVisitor<JType, Void> {
        @Override
        public JType visitParameterizedType(ParameterizedTypeTree pt, Void __) {
            JClass base = (JClass)pt.getType().accept(this, __);
            List<JClass> args = new ArrayList<>();
            for (Tree arg : pt.getTypeArguments()) {
                args.add((JClass)arg.accept(this,__));
            }
            return base.narrow(args);
        }

        @Override
        public JType visitIdentifier(IdentifierTree it, Void __) {
            JCIdent idt = (JCIdent) it;
            return t(idt.sym.asType());
        }

        @Override
        public JType visitPrimitiveType(PrimitiveTypeTree pt, Void aVoid) {
            switch (pt.getPrimitiveTypeKind()) {
            case BOOLEAN:   return codeModel.INT;
            case BYTE:      return codeModel.BYTE;
            case SHORT:     return codeModel.SHORT;
            case INT:       return codeModel.INT;
            case LONG:      return codeModel.LONG;
            case CHAR:      return codeModel.CHAR;
            case FLOAT:     return codeModel.FLOAT;
            case DOUBLE:    return codeModel.DOUBLE;
            case VOID:      return codeModel.VOID;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public JType visitArrayType(ArrayTypeTree at, Void __) {
            return visit(at.getType(),__).array();
        }

        /**
         * Nested type
         */
        @Override
        public JType visitMemberSelect(MemberSelectTree mt, Void __) {
            return t(((JCFieldAccess)mt).type);
        }

        @Override
        protected JType defaultAction(Tree node, Void aVoid) {
            throw new UnsupportedOperationException();
        }
    }
}
