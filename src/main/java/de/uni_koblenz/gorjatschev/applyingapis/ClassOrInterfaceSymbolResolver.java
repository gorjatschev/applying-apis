package de.uni_koblenz.gorjatschev.applyingapis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.ReceiverParameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.nodeTypes.NodeWithThrownExceptions;
import com.github.javaparser.ast.nodeTypes.NodeWithType;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedUnionType;
import com.github.javaparser.resolution.types.ResolvedWildcard;

import org.apache.logging.log4j.Logger;

/**
 * This class contains the logic resolve the symbols contained in a
 * {@code ClassOrInterfaceDeclaration}.
 */
public class ClassOrInterfaceSymbolResolver {

    private static final Logger log = Utils.getLogger();

    /**
     * Resolves the symbols contained in the child nodes of a
     * {@code ClassOrInterfaceDeclaration}.
     * 
     * @param classOrInterface
     * @return A list of the resolved symbols as maps
     */
    public static List<Map<String, String>> resolveClassOrInterface(ClassOrInterfaceDeclaration classOrInterface) {
        List<Map<String, String>> usedClasses = new ArrayList<>();
        usedClasses.addAll(Utils.extendMaps(resolvePossibleNodeProperties(classOrInterface), Utils.METHOD_NAME, ""));
        classOrInterface.getChildNodes().forEach(childOfClassOrInterface -> {
            String methodName = "";
            if (childOfClassOrInterface instanceof MethodDeclaration) {
                methodName = ((MethodDeclaration) childOfClassOrInterface).getNameAsString();
            }
            usedClasses.addAll(
                    Utils.extendMaps(resolveChildNodes(childOfClassOrInterface), Utils.METHOD_NAME, methodName));
        });
        return usedClasses;
    }

    /**
     * Resolves all symbols that are descendants of the node {@code n}. Walks over
     * all possible elements that can be descendants of the node and contain at
     * least one type.
     * 
     * @param n
     * @return A list of the resolved symbols as maps
     */
    private static List<Map<String, String>> resolveChildNodes(Node n) {
        List<Map<String, String>> usedClasses = new ArrayList<>();
        // NodeWithType
        usedClasses.addAll(resolveNodeWithType(n, CastExpr.class));
        usedClasses.addAll(resolveNodeWithType(n, ClassExpr.class));
        usedClasses.addAll(resolveNodeWithType(n, InstanceOfExpr.class));
        usedClasses.addAll(resolveNodeWithType(n, ObjectCreationExpr.class));
        usedClasses.addAll(resolveNodeWithType(n, TypeExpr.class));
        usedClasses.addAll(resolveNodeWithType(n, AnnotationMemberDeclaration.class));
        usedClasses.addAll(resolveNodeWithType(n, MethodDeclaration.class));
        usedClasses.addAll(resolveNodeWithType(n, Parameter.class));
        usedClasses.addAll(resolveNodeWithType(n, ReceiverParameter.class));
        usedClasses.addAll(resolveNodeWithType(n, VariableDeclarator.class));
        // Resolvable value declarations
        usedClasses.addAll(resolveNodeWithValueDecl(n, FieldAccessExpr.class));
        usedClasses.addAll(resolveNodeWithValueDecl(n, NameExpr.class));
        // Resolvable enum constant declarations
        usedClasses.addAll(resolveNodeWithEnumConstDecl(n, EnumConstantDeclaration.class));
        // Resolvable type declarations
        usedClasses.addAll(resolveNodeWithTypeDecl(n, ThisExpr.class));
        // Resolvable reference type declarations
        usedClasses.addAll(resolveNodeWithRefTypeDecl(n, ClassOrInterfaceDeclaration.class));
        // Resolvable annotation declarations
        usedClasses.addAll(resolveNodeWithAnnotationDecl(n, AnnotationExpr.class));
        // Resolvable method declarations
        usedClasses.addAll(resolveNodeWithMethodDecl(n, MethodCallExpr.class));
        usedClasses.addAll(resolveNodeWithMethodDecl(n, MethodReferenceExpr.class));
        // Resolvable constructor declarations
        usedClasses.addAll(resolveNodeWithConstrDecl(n, ConstructorDeclaration.class));
        return usedClasses;
    }

    /**
     * Resolves all nodes of type {@code NodeWithType} that are descendants of node
     * {@code n}. Resolve the symbols that are possibly part of the node's
     * properties as well.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithType(Node n,
            Class<? extends NodeWithType<? extends Node, ? extends Type>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedType type = null;
            try {
                type = ((NodeWithType<? extends Node, ? extends Type>) node).getType().resolve();
                solveType(type).forEach(resolvedType -> nodeMaps.add(createNodeMap(node, resolvedType)));
            } catch (RuntimeException | StackOverflowError e) {
                // Many unresolved symbols are type variables that are not of interest here. Try
                // to identify them by their typical length of 1
                try {
                    if (((NodeWithType<? extends Node, ? extends Type>) node).getType().asString().length() == 1) {
                        logUnsolvedSymbol(node, "The type is most likely a type variable");
                        nodeMaps.add(createNodeMap(node, "null (Type variable)"));
                    } else {
                        logUnsolvedSymbol(node);
                        nodeMaps.add(createNodeMap(node, "null"));
                    }
                } catch (RuntimeException | StackOverflowError e2) {
                    logUnsolvedSymbol(node, "2nd attempt failed");
                    nodeMaps.add(createNodeMap(node, "null"));
                }
            }
            nodeMaps.addAll(resolvePossibleNodeProperties(node));
        });
        return nodeMaps;
    }

    /**
     * Resolves all nodes of type {@code FieldAccessExpr} or {@code NameExpr}
     * (extend {@code Resolvable<ResolvedValueDeclaration>}) that are descendants of
     * node {@code n}.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithValueDecl(Node n,
            Class<? extends Resolvable<ResolvedValueDeclaration>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedType type = null;
            boolean isPackageName = false;
            try {
                type = ((Resolvable<ResolvedValueDeclaration>) node).resolve().getType();
            } catch (RuntimeException | StackOverflowError e) {
                try {
                    type = ((Expression) node).calculateResolvedType();
                } catch (RuntimeException | StackOverflowError e2) {
                    // Try to exclude unresolved symbols that are actually packages, e.g., "org"
                    if (isPackageName(node)) {
                        isPackageName = true;
                    } else {
                        logUnsolvedSymbol(node, "2nd attempt failed");
                    }
                }
            }
            if (type != null) {
                solveType(type).forEach(resolvedType -> nodeMaps.add(createNodeMap(node, resolvedType)));
            } else if (!isPackageName) {
                nodeMaps.add(createNodeMap(node, "null"));
            }
        });
        return nodeMaps;

    }

    /**
     * Resolves all nodes of type {@code EnumConstantDeclaration} (extend
     * {@code Resolvable<ResolvedEnumConstantDeclaration>}) that are descendants of
     * node {@code n}. Resolve the symbols that are possibly part of the node's
     * properties as well.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithEnumConstDecl(Node n,
            Class<? extends Resolvable<ResolvedEnumConstantDeclaration>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedType type = null;
            try {
                type = ((Resolvable<ResolvedEnumConstantDeclaration>) node).resolve().getType();
            } catch (RuntimeException | StackOverflowError e) {
                logUnsolvedSymbol(node);
            }
            solveType(type).forEach(resolvedType -> nodeMaps.add(createNodeMap(node, resolvedType)));
            nodeMaps.addAll(resolvePossibleNodeProperties(node));
        });
        return nodeMaps;
    }

    /**
     * Resolves all nodes of type {@code ThisExpr} (extend
     * {@code Resolvable<ResolvedTypeDeclaration>}) that are descendants of node
     * {@code n}.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithTypeDecl(Node n,
            Class<? extends Resolvable<ResolvedTypeDeclaration>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedTypeDeclaration typeDecl = null;
            try {
                typeDecl = ((Resolvable<ResolvedTypeDeclaration>) node).resolve();
            } catch (RuntimeException | StackOverflowError e) {
                logUnsolvedSymbol(node);
            }
            String apiClass = typeDecl == null ? "null" : typeDecl.getQualifiedName();
            nodeMaps.add(createNodeMap(node, apiClass));
        });
        return nodeMaps;
    }

    /**
     * Resolves all nodes of type {@code ClassOrInterfaceDeclaration} (extend
     * {@code Resolvable<ResolvedReferenceTypeDeclaration>}) that are descendants of
     * node {@code n}. Resolve the symbols that are possibly part of the node's
     * properties as well.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithRefTypeDecl(Node n,
            Class<? extends Resolvable<ResolvedReferenceTypeDeclaration>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedReferenceTypeDeclaration refTypeDecl = null;
            try {
                refTypeDecl = ((Resolvable<ResolvedReferenceTypeDeclaration>) node).resolve();
            } catch (RuntimeException | StackOverflowError e) {
                logUnsolvedSymbol(node);
            }
            String apiClass = refTypeDecl == null ? "null" : refTypeDecl.getQualifiedName();
            nodeMaps.add(createNodeMap(node, apiClass));
            nodeMaps.addAll(resolvePossibleNodeProperties(node));
        });
        return nodeMaps;
    }

    /**
     * Resolves all nodes of type {@code AnnotationExpr} (extend
     * {@code Resolvable<ResolvedAnnotationDeclaration>}) that are descendants of
     * node {@code n}.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithAnnotationDecl(Node n,
            Class<? extends Resolvable<ResolvedAnnotationDeclaration>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedAnnotationDeclaration annotationDecl = null;
            try {
                annotationDecl = ((Resolvable<ResolvedAnnotationDeclaration>) node).resolve();
            } catch (RuntimeException | StackOverflowError e) {
                logUnsolvedSymbol(node);
            }
            String apiClass = annotationDecl == null ? "null" : annotationDecl.getQualifiedName();
            nodeMaps.add(createNodeMap(node, apiClass));
        });
        return nodeMaps;
    }

    /**
     * Resolves all nodes of type {@code MethodCallExpr} or
     * {@code MethodReferenceExpr} (extend
     * {@code Resolvable<ResolvedMethodDeclaration>}) that are descendants of node
     * {@code n}.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithMethodDecl(Node n,
            Class<? extends Resolvable<ResolvedMethodDeclaration>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedMethodDeclaration methodDecl = null;
            try {
                methodDecl = ((Resolvable<ResolvedMethodDeclaration>) node).resolve();
            } catch (RuntimeException | StackOverflowError e) {
                logUnsolvedSymbol(node);
            }
            String apiClass = methodDecl == null ? "null" : methodDecl.declaringType().getQualifiedName();
            nodeMaps.add(createNodeMap(node, apiClass));
        });
        return nodeMaps;
    }

    /**
     * Resolves all nodes of type {@code ConstructorDeclaration} (extend
     * {@code Resolvable<ResolvedConstructorDeclaration>}) that are descendants of
     * node {@code n}. Resolve the symbols that are possibly part of the node's
     * properties as well.
     * 
     * @param n
     * @param classOfNode
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolveNodeWithConstrDecl(Node n,
            Class<? extends Resolvable<ResolvedConstructorDeclaration>> classOfNode) {
        List<Map<String, String>> nodeMaps = new ArrayList<>();
        n.findAll(classOfNode.asSubclass(Node.class)).forEach(node -> {
            ResolvedConstructorDeclaration constrDecl = null;
            try {
                constrDecl = ((Resolvable<ResolvedConstructorDeclaration>) node).resolve();
            } catch (RuntimeException | StackOverflowError e) {
                logUnsolvedSymbol(node);
            }
            String apiClass = constrDecl == null ? "null" : constrDecl.declaringType().getQualifiedName();
            nodeMaps.add(createNodeMap(node, apiClass));
            nodeMaps.addAll(resolvePossibleNodeProperties(node));
        });
        return nodeMaps;
    }

    /**
     * Resolves the symbols that are properties of the {@code node}. Properties are
     * type parameters, thrown exceptions, extended types, and implemented types.
     * 
     * @param node
     * @return A list of the resolved symbols as maps
     */
    @SuppressWarnings(value = "unchecked")
    private static List<Map<String, String>> resolvePossibleNodeProperties(Node node) {
        List<Map<String, String>> resolvedTypes = new ArrayList<>();
        if (node instanceof NodeWithTypeParameters<?>) {
            ((NodeWithTypeParameters<? extends Node>) node).getTypeParameters()
                    .forEach(typeParameter -> typeParameter.getTypeBound().forEach(type -> {
                        try {
                            ResolvedReferenceType refType = type.resolve();
                            solveType(refType)
                                    .forEach(solvedType -> resolvedTypes.add(createNodeMap(typeParameter, solvedType)));
                        } catch (RuntimeException | StackOverflowError e) {
                            logUnsolvedSymbol(node);
                            resolvedTypes.add(createNodeMap(typeParameter, "null"));
                        }
                    }));
        }
        if (node instanceof NodeWithThrownExceptions<?>) {
            ((NodeWithThrownExceptions<? extends Node>) node).getThrownExceptions()
                    .forEach(type -> resolvedTypes.addAll(tryToResolveType(type)));
        }
        if (node instanceof NodeWithExtends<?>) {
            ((NodeWithExtends<? extends Node>) node).getExtendedTypes()
                    .forEach(type -> resolvedTypes.addAll(tryToResolveType(type)));
        }
        if (node instanceof NodeWithImplements<?>) {
            ((NodeWithImplements<? extends Node>) node).getImplementedTypes()
                    .forEach(type -> resolvedTypes.addAll(tryToResolveType(type)));
        }
        return resolvedTypes;
    }

    /**
     * Solves a {@code ResolvedType}. A {@code ResolvedType} of interest can be a
     * {@code ResolvedReferenceType}, a {@code ResolvedArrayType}, a
     * {@code ResolvedUnionType}, or a {@code ResolvedWildcard}.
     * 
     * @param type
     * @return A list of the resolved symbols as maps
     */
    private static List<String> solveType(ResolvedType type) {
        List<String> resolvedTypes = new ArrayList<>();
        if (type == null) {
            resolvedTypes.add("null");
        }
        if (type instanceof ResolvedReferenceType) {
            ResolvedReferenceType refType = (ResolvedReferenceType) type;
            if (refType.typeParametersValues().isEmpty()) {
                resolvedTypes.add(refType.describe());
            } else {
                resolvedTypes.add(refType.getQualifiedName());
                refType.typeParametersValues().forEach(typeParameter -> resolvedTypes.addAll(solveType(typeParameter)));
            }
        }
        if (type instanceof ResolvedArrayType) {
            ResolvedArrayType arrayType = (ResolvedArrayType) type;
            ResolvedType componentType = arrayType.getComponentType();
            resolvedTypes.addAll(solveType(componentType));
        }
        if (type instanceof ResolvedUnionType) {
            ResolvedUnionType unionType = (ResolvedUnionType) type;
            String types = unionType.describe();
            resolvedTypes.addAll(Arrays.asList(types.split(" \\| ")));
        }
        if (type instanceof ResolvedWildcard) {
            ResolvedWildcard wildcard = (ResolvedWildcard) type;
            if (wildcard.isBounded()) {
                resolvedTypes.addAll(solveType(wildcard.getBoundedType()));
            }
        }
        return resolvedTypes;
    }

    /**
     * Tries to resolve a {@code Type}.
     * 
     * @param type
     * @return A list of the resolved symbols as maps
     */
    private static List<Map<String, String>> tryToResolveType(Type type) {
        List<Map<String, String>> resolvedTypes = new ArrayList<>();
        try {
            ResolvedType resolvedType = type.resolve();
            solveType(resolvedType).forEach(solvedType -> resolvedTypes.add(createNodeMap(type, solvedType)));
        } catch (RuntimeException | StackOverflowError e) {
            resolvedTypes.add(createNodeMap(type, "null"));
        }
        return resolvedTypes;
    }

    /**
     * Checks if the given {@code node} is actually a package name.
     * 
     * @param node
     * @return True if the {@code node} is a package name, false otherwise.
     */
    private static boolean isPackageName(Node node) {
        if ((node instanceof FieldAccessExpr) && node.getParentNode().isPresent()) {
            Node parent = node.getParentNode().get();
            while (parent instanceof FieldAccessExpr) {
                ResolvedType type = null;
                try {
                    type = ((Expression) parent).calculateResolvedType();
                } catch (RuntimeException | StackOverflowError e) {
                    if (parent.getParentNode().isPresent()) {
                        parent = parent.getParentNode().get();
                    }
                }
                if (type instanceof ResolvedReferenceType) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Creates a map for a {@code node} that contains all its important information.
     * 
     * @param node
     * @param apiClass
     * @return The created node map
     */
    private static Map<String, String> createNodeMap(Node node, String apiClass) {
        return Utils.createMap(Utils.LINE, String.valueOf(node.getBegin().get().line), Utils.COLUMN,
                String.valueOf(node.getBegin().get().column), Utils.JP_TYPE_OF_ELEMENT, node.getClass().getSimpleName(),
                Utils.USED_CLASS_OF_ELEMENT, apiClass);
    }

    /**
     * Logs information about an unresolved symbol.
     * 
     * @param node
     */
    private static void logUnsolvedSymbol(Node node) {
        log.info("------Could not resolve the type of " + node.getClass().getSimpleName() + " \"" + node + "\" in line "
                + node.getBegin().get().line + ".");
    }

    /**
     * Logs information about an unresolved symbol with additional {@code infos}.
     * 
     * @param node
     * @param infos
     */
    private static void logUnsolvedSymbol(Node node, String infos) {
        log.info("------Could not resolve the type of " + node.getClass().getSimpleName() + " \"" + node + "\" in line "
                + node.getBegin().get().line + " (" + infos + ").");
    }

}
