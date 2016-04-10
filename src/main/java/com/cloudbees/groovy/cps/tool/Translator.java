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
import com.sun.codemodel.writer.FileCodeWriter;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.TypeVariableSymbol;
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
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementScanner7;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleElementVisitor7;
import javax.lang.model.util.SimpleTypeVisitor6;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates {@code CpsDefaultGroovyMethods} from the source code of {@code DefaultGroovyMethods}.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("Since15")
public class Translator {

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
    private final JClass $CatchExpression;
    private final JClass $DefaultGroovyMethods;

    public Translator() {
        try {
            $CpsDefaultGroovyMethods = codeModel._class("com.cloudbees.groovy.cps.CpsDefaultGroovyMethods");
        } catch (JClassAlreadyExistsException e) {
            throw new AssertionError(e);
        }
        $Caller                = codeModel.ref("com.cloudbees.groovy.cps.impl.Caller");
        $CpsFunction           = codeModel.ref("com.cloudbees.groovy.cps.impl.CpsFunction");
        $CpsCallableInvocation = codeModel.ref("com.cloudbees.groovy.cps.impl.CpsCallableInvocation");
        $Builder               = codeModel.ref("com.cloudbees.groovy.cps.Builder");
        $CatchExpression       = codeModel.ref("com.cloudbees.groovy.cps.CatchExpression");
        $DefaultGroovyMethods  = codeModel.ref("org.codehaus.groovy.runtime.DefaultGroovyMethods");
    }

    public static void main(String[] args) throws Exception {
        new Translator().translate(new File("DefaultGroovyMethods.java"));
    }

    public void translate(File dgmJava) throws Exception {
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
                    if (isGdkMethodWithClosureArgument(e)
                     && !EXCLUSIONS.contains(n(e))) {
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

            /*
                private static MethodLocation loc(String methodName) {
                    return new MethodLocation(CpsDefaultGroovyMethods.class,methodName);
                }
            */

            JClass $MethodLocation = codeModel.ref("com.cloudbees.groovy.cps.MethodLocation");
            $CpsDefaultGroovyMethods.method(JMod.PRIVATE|JMod.STATIC, $MethodLocation, "loc").tap( m -> {
                JVar $methodName = m.param(String.class, "methodName");
                m.body()._return(JExpr._new($MethodLocation).arg($CpsDefaultGroovyMethods.dotclass()).arg($methodName));
            });
        } finally {
            if (fileManager!=null)
                fileManager.close();
        }

        File dir = new File("out");
        dir.mkdirs();
        codeModel.build(new FileCodeWriter(dir));
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
        String methodName = n(e);
        JMethod m = $CpsDefaultGroovyMethods.method(JMod.PUBLIC | JMod.STATIC, t(e.getReturnType()), methodName);

        e.getTypeParameters().forEach( p -> m.generify(n(p)));  // TODO: bound

        List<JVar> params = new ArrayList<>();
        e.getParameters().forEach(p -> params.add(m.param(t(p.asType()), n(p))));

        e.getThrownTypes().forEach( ex -> m._throws((JClass)t(ex)) );

        {// preamble
            /*
                If the call to this method happen outside CPS code, execute normally via DefaultGroovyMethods
             */
            m.body()._if(JOp.cand(
                    JOp.not($Caller.staticInvoke("isAsynchronous").tap(inv -> {
                        inv.arg(params.get(0));
                        inv.arg(methodName);
                        for (int i = 1; i < params.size(); i++)
                            inv.arg(params.get(i));
                    })),
                    JOp.not($Caller.staticInvoke("isAsynchronous")
                            .arg($CpsDefaultGroovyMethods.dotclass())
                            .arg(methodName)
                            .args(params))
            ))._then().tap(blk -> {
                JInvocation forward = $DefaultGroovyMethods.staticInvoke(methodName).args(params);

                if (e.getReturnType().getKind() == TypeKind.VOID) {
                    blk.add(forward);
                    blk._return();
                } else {
                    blk._return(forward);
                }
            });
        }

        JVar $b = m.body().decl($Builder, "b", JExpr._new($Builder).arg(JExpr.invoke("loc").arg(methodName)));
        JInvocation f = JExpr._new($CpsFunction);

        // parameter names
        f.arg(codeModel.ref(Arrays.class).staticInvoke("asList").tap( inv -> {
            e.getParameters().forEach( p -> inv.arg(n(p)) );
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
                return JExpr.lit((int)cut.getLineMap().getLineNumber(pos));
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
                        .arg(n(mst.getIdentifier()));
                } else
                if (ms instanceof JCIdent) {
                    // invocation without object selection, like  foo(bar,zot)
                    JCIdent it = (JCIdent) ms;
                    if (!it.sym.owner.toString().equals(DefaultGroovyMethods.class.getName())) {
                        // static import
                        inv.arg($b.invoke("constant").arg(t(it.sym.owner.type).dotclass()))
                           .arg(n(it));
                    } else {
                        // invocation on this class
                        inv.arg($b.invoke("this_"))
                           .arg(n(it));
                    }
                } else {
                    // TODO: figure out what can come here
                    throw new UnsupportedOperationException(ms.toString());
                }

                mt.getArguments().forEach( a -> inv.arg(visit(a)) );
                return inv;
            }

            @Override
            public JExpression visitVariable(VariableTree vt, Void __) {
                return $b.invoke("declareVariable")
                        .arg(loc(vt))
                        .arg(erasuse(vt).dotclass())
                        .arg(n(vt))
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
                        throw new UnsupportedOperationException(s.toString());
                    }
                }, __);
            }

            @Override
            public JExpression visitBlock(BlockTree bt, Void __) {
                JInvocation inv = $b.invoke("block");
                bt.getStatements().forEach(s -> inv.arg(visit(s)));
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
                        .arg(erasuse(tt.getType()).dotclass())
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
                    nt.getArguments().forEach( et -> inv.arg(visit(et)) );
                });
            }

            @Override
            public JExpression visitExpressionStatement(ExpressionStatementTree et, Void __) {
                return visit(et.getExpression());
            }

            @Override
            public JExpression visitLiteral(LiteralTree lt, Void __) {
                return $b.invoke("constant").arg(JExpr.literal(lt.getValue()));
            }

            @Override
            public JExpression visitParenthesized(ParenthesizedTree pt, Void __) {
                return visit(pt.getExpression());
            }

            @Override
            public JExpression visitBinary(BinaryTree bt, Void __) {
                return $b.invoke(opName(bt.getKind()))
                        .arg(loc(bt))
                        .arg(visit(bt.getLeftOperand()))
                        .arg(visit(bt.getRightOperand()));
            }

            @Override
            public JExpression visitUnary(UnaryTree ut, Void __) {
                return $b.invoke(opName(ut.getKind()))
                        .arg(loc(ut))
                        .arg(visit(ut.getExpression()));
            }

            @Override
            public JExpression visitCompoundAssignment(CompoundAssignmentTree ct, Void __) {
                return $b.invoke(opName(ct.getKind()))
                        .arg(loc(ct))
                        .arg(visit(ct.getVariable()))
                        .arg(visit(ct.getExpression()));
            }

            private String opName(Kind kind) {
                switch (kind) {
                case EQUAL_TO:              return "compareEqual";
                case NOT_EQUAL_TO:          return "compareNotEqual";
                case LESS_THAN_EQUAL:       return "lessThanEqual";
                case LESS_THAN:             return "lessThan";
                case GREATER_THAN_EQUAL:    return "greaterThanEqual";
                case GREATER_THAN:          return "greaterThan";
                case PREFIX_INCREMENT:      return "prefixInc";
                case POSTFIX_INCREMENT:     return "postfixInc";
                case POSTFIX_DECREMENT:     return "postfixDec";
                case LOGICAL_COMPLEMENT:    return "not";
                case CONDITIONAL_OR:        return "logicalOr";
                case CONDITIONAL_AND:       return "logicalAnd";
                case PLUS:                  return "plus";
                case PLUS_ASSIGNMENT:       return "plusEqual";
                }
                throw new UnsupportedOperationException(kind.toString());
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
                if (nt.getInitializers()!=null) {
                    return $b.invoke("newArrayFromInitializers").tap(inv -> {
                        inv.arg(loc(nt));
                        inv.arg(t(nt.getType()).dotclass());
                        nt.getInitializers().forEach(d -> inv.arg(visit(d)));
                    });
                } else {
                    return $b.invoke("newArray").tap(inv -> {
                        inv.arg(loc(nt));
                        inv.arg(t(nt.getType()).dotclass());
                        nt.getDimensions().forEach(d -> inv.arg(visit(d)));
                    });
                }
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
                        .arg(erasuse(et.getVariable()).dotclass())
                        .arg(n(et.getVariable()))
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
            public JExpression visitBreak(BreakTree node, Void __) {
                if (node.getLabel()!=null)
                    throw new UnsupportedOperationException();
                return $b.invoke("break_").arg(JExpr._null());
            }

            @Override
            public JExpression visitContinue(ContinueTree node, Void aVoid) {
                if (node.getLabel()!=null)
                    throw new UnsupportedOperationException();
                return $b.invoke("continue_").arg(JExpr._null());
            }

            @Override
            public JExpression visitInstanceOf(InstanceOfTree it, Void __) {
                return $b.invoke("instanceOf")
                        .arg(loc(it))
                        .arg(visit(it.getExpression()))
                        .arg($b.invoke("constant").arg(t(it.getType()).dotclass()));
            }

            @Override
            public JExpression visitThrow(ThrowTree tt, Void __) {
                return $b.invoke("throw_")
                        .arg(loc(tt))
                        .arg(visit(tt.getExpression()));
            }

            @Override
            public JExpression visitDoWhileLoop(DoWhileLoopTree dt, Void __) {
                return $b.invoke("doWhile")
                        .arg(JExpr._null())
                        .arg(visit(dt.getStatement()))
                        .arg(visit(dt.getCondition()));
            }

            @Override
            public JExpression visitConditionalExpression(ConditionalExpressionTree ct, Void __) {
                return $b.invoke("ternaryOp")
                        .arg(visit(ct.getCondition()))
                        .arg(visit(ct.getTrueExpression()))
                        .arg(visit(ct.getFalseExpression()));
            }

            @Override
            public JExpression visitTry(TryTree tt, Void __) {
                return $b.invoke("tryCatch")
                        .arg(visit(tt.getBlock()))
                        .arg(visit(tt.getFinallyBlock()))
                        .tap(inv ->
                            tt.getCatches().forEach(ct ->
                                JExpr._new($CatchExpression)
                                    .arg(t(ct.getParameter()).dotclass())
                                    .arg(n(ct.getParameter()))
                                    .arg(visit(ct.getBlock())))
                        );
            }

            @Override
            protected JExpression defaultAction(Tree node, Void aVoid) {
                throw new UnsupportedOperationException(node.toString());
            }
        }, null));

        JVar $f = m.body().decl($CpsFunction, "f", f);
        m.body()._throw(JExpr._new($CpsCallableInvocation)
            .arg($f)
            .arg(JExpr._null())
            .args(params));
    }

    /**
     * Convert a type representation from javac to codemodel.
     */
    private JType t(Tree t) {
        return t.accept(new TypeTranslator(), null);
    }

    /**
     * Converts a type representation to its erasure.
     */
    private JType erasuse(Tree t) {
        return t.accept(new TypeTranslator() {
            @Override
            public JType visitParameterizedType(ParameterizedTypeTree pt, Void __) {
                return visit(pt.getType());
            }

            @Override
            public JType visitWildcard(WildcardTree wt, Void __) {
                Tree b = wt.getBound();
                if (b==null)    return codeModel.ref(Object.class);
                else            return visit(b);
            }

            @Override
            public JType visitIdentifier(IdentifierTree it, Void __) {
                JCIdent idt = (JCIdent) it;
                if (idt.sym instanceof ClassSymbol) {
                    ClassSymbol cs = (ClassSymbol) idt.sym;
                    return codeModel.ref(cs.className());
                }
                if (idt.sym instanceof TypeVariableSymbol) {
                    TypeVariableSymbol tcs = (TypeVariableSymbol) idt.sym;
                    if (tcs.getBounds().isEmpty())
                        return codeModel.ref(Object.class);
                    else
                        return t(tcs.getBounds().get(0));
                }
                throw new UnsupportedOperationException(idt.sym.toString());
            }
        }, null);
    }

    private JType t(TypeMirror m) {
        if (m.getKind().isPrimitive())
            return JType.parse(codeModel,m.toString());

        return m.accept(new SimpleTypeVisitor6<JType, Void>() {
            @Override
            public JType visitPrimitive(PrimitiveType t, Void __) {
                return primitive(t, t.getKind());
            }

            @Override
            public JType visitDeclared(DeclaredType t, Void __) {
                String name = n(((TypeElement) t.asElement()).getQualifiedName());
                if (name.isEmpty())
                    throw new UnsupportedOperationException("Anonymous class: "+t);
                JClass base = codeModel.ref(name);
                if (t.getTypeArguments().isEmpty())
                    return base;

                List<JClass> typeArgs = new ArrayList<>();
                t.getTypeArguments().forEach( a -> typeArgs.add((JClass)t(a)));
                return base.narrow(typeArgs);
            }

            @Override
            public JType visitTypeVariable(TypeVariable t, Void __) {
                // handling this correctly requires us tracking JTypeVar
                return t(t.getUpperBound());
            }

            @Override
            public JType visitNoType(NoType t, Void __) {
                return primitive(t, t.getKind());
            }

            @Override
            public JType visitArray(ArrayType t, Void __) {
                return t(t.getComponentType()).array();
            }

            @Override
            public JType visitWildcard(WildcardType t, Void aVoid) {
                if (t.getExtendsBound()!=null) {
                    return t(t.getExtendsBound()).boxify().wildcard();
                }
                if (t.getSuperBound()!=null) {
                    throw new UnsupportedOperationException();
                }
                return codeModel.wildcard();
            }

            @Override
            protected JType defaultAction(TypeMirror e, Void __) {
                throw new UnsupportedOperationException(e.toString());
            }
        }, null);
    }

    private String n(Element e) {
        return e.getSimpleName().toString();
    }
    private String n(Name n) {
        return n.toString();
    }
    private String n(VariableTree v) {
        return n(v.getName());
    }
    private String n(IdentifierTree v) {
        return n(v.getName());
    }

    protected DiagnosticListener<JavaFileObject> createErrorListener() {
        return diagnostic -> {
            //TODO report
            System.out.println(diagnostic);
        };
    }

    private static final Collection<Modifier> PUBLIC_STATIC = Arrays.asList(Modifier.PUBLIC, Modifier.STATIC);

    private JType primitive(Object src, TypeKind k) {
        switch (k) {
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
        throw new UnsupportedOperationException(src.toString());
    }

    private static final Set<String> EXCLUSIONS = new HashSet<>(Arrays.asList(
            "runAfter", /* use anonymous inner class we can't handle */
            "accept" /* launches a thread */,
            "filterLine",    /* anonymous inner classes */
            "dropWhile","takeWhile" /* TODO: translate inner classes to support this*/
    ));

    private class TypeTranslator extends SimpleTreeVisitor<JType, Void> {
        protected JType visit(Tree t) {
            return visit(t,null);
        }

        @Override
        public JType visitVariable(VariableTree node, Void __) {
            return visit(node.getType());
        }

        @Override
        public JType visitParameterizedType(ParameterizedTypeTree pt, Void __) {
            JClass base = (JClass)visit(pt.getType());
            List<JClass> args = new ArrayList<>();
            for (Tree arg : pt.getTypeArguments()) {
                args.add((JClass)visit(arg));
            }
            return base.narrow(args);
        }

        @Override
        public JType visitIdentifier(IdentifierTree it, Void __) {
            JCIdent idt = (JCIdent) it;
            return codeModel.ref(idt.sym.toString());
        }

        @Override
        public JType visitPrimitiveType(PrimitiveTypeTree pt, Void aVoid) {
            return primitive(pt, pt.getPrimitiveTypeKind());
        }

        @Override
        public JType visitArrayType(ArrayTypeTree at, Void __) {
            return visit(at.getType()).array();
        }

        /**
         * Nested type
         */
        @Override
        public JType visitMemberSelect(MemberSelectTree mt, Void __) {
            return t(((JCFieldAccess)mt).type);
        }

        @Override
        public JType visitWildcard(WildcardTree wt, Void __) {
            Tree b = wt.getBound();
            if (b==null)    return codeModel.wildcard();
            else            return visit(b).boxify().wildcard();
        }

        @Override
        protected JType defaultAction(Tree node, Void __) {
            throw new UnsupportedOperationException(node.toString());
        }
    }
}
