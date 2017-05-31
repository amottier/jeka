package org.jerkar.api.depmanagement;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.jerkar.api.utils.JkUtilsString;

/**
 * A representation of a node in a dependency tree. A dependency tree may be
 * represented simply by its asScopedDependency node.
 *
 * @author Jerome Angibaud
 */
public class JkDependencyNode implements Serializable {

    private static final String INDENT = "    ";

    private static final long serialVersionUID = 1L;

    private final JkScopedDependency scopedDependency;

    private final List<JkDependencyNode> children;

    /**
     * Constructs a node for the specified versioned module having the specified
     * direct descendants.
     */
    public static JkDependencyNode empty() {
        return new JkDependencyNode(null, new LinkedList<JkDependencyNode>());
    }

    /**
     * Constructs a node for the specified versioned module having the specified
     * direct descendants.
     */
    public JkDependencyNode(JkScopedDependency module, List<JkDependencyNode> children) {
        super();
        this.scopedDependency = module;
        this.children = Collections.unmodifiableList(children);
    }

    /**
     * Returns the asScopedDependency module+scope of this dependency node.
     */
    public JkScopedDependency asScopedDependency() {
        return scopedDependency;
    }

    /**
     * Returns the asScopedDependency module of this dependency node.
     */
    public JkModuleDependency asModuleDependency() {
        return (JkModuleDependency) scopedDependency.dependency();
    }

    /**
     * Returns the children nodes for this node in the tree structure.
     */
    public List<JkDependencyNode> children() {
        return children;
    }

    /**
     * Returns the child node having the specified moduleId.
     */
    public JkDependencyNode child(JkModuleId moduleId) {
        for (JkDependencyNode node : children) {
            if (node.moduleId().equals(moduleId)) {
                return node;
            }
        }
        return null;
    }


    /**
     * Returns a merge of this dependency node with the specified one. The
     * children of the merged node is a union of the two node children.
     */
    public JkDependencyNode merge(JkDependencyNode other) {
        final List<JkDependencyNode> list = new LinkedList<JkDependencyNode>(this.children);
        for (final JkDependencyNode otherNodeChild : other.children) {
            final JkScopedDependency otherScopedDependencyChild = otherNodeChild.scopedDependency;
            final JkModuleDependency moduleDependency = (JkModuleDependency) otherScopedDependencyChild.dependency();
            if (!directChildrenContains(moduleDependency.moduleId())) {
                list.add(otherNodeChild);
            }
        }
        return new JkDependencyNode(this.scopedDependency, list);
    }

    /**
     * Returns all nodes descendant of this one, deep first.
     */
    public List<JkDependencyNode> descendants() {
        List<JkDependencyNode> result = new LinkedList<JkDependencyNode>();
        for (JkDependencyNode child : this.children()) {
            result.add(child);
            result.addAll(child.descendants());
        }
        return result;
    }

    /**
     * Returns first node descendant of this one standing for the specified moduleId, deep first.
     */
    public JkDependencyNode find(JkModuleId moduleId) {
        if (moduleId.equals(this.moduleId())) {
            return this;
        }
        for (JkDependencyNode child : this.descendants()) {
            if (moduleId.equals(child.moduleId())) {
                return child;
            }
        }
        return null;
    }

    private boolean directChildrenContains(JkModuleId moduleId) {
        for (final JkDependencyNode dependencyNode : this.children) {
            if (dependencyNode.moduleId().equals(moduleId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of lines standing for the representation of this
     * dependency tree.
     */
    public List<String> toStrings() {
        return this.toStrings(false, -1, new HashSet<JkModuleId>());
    }

    private List<String> toStrings(boolean showRoot, int indentLevel, Set<JkModuleId> expandeds) {
        final List<String> result = new LinkedList<String>();
        if (showRoot) {
            final String label = indentLevel == 0 ? this.scopedDependency.toString() : this.asScopedDependency().toString();
            result.add(JkUtilsString.repeat(INDENT, indentLevel) + label);
        }
        if (this.scopedDependency == null || !expandeds.contains(this.moduleId())) {
            if (this.scopedDependency != null) {
                expandeds.add(this.moduleId());
            }
            for (final JkDependencyNode child : children) {
                result.addAll(child.toStrings(true, indentLevel+1, expandeds));
            }
        }
        return result;
    }

    /**
     * Returns a complete representation string of the tree.
     */
    public String toStringComplete() {
        StringBuilder builder = new StringBuilder();
        for (String line: toStrings()) {
            builder.append(line).append("\n");
        }
        return builder.toString();
    }

    private JkModuleId moduleId() {
        final JkModuleDependency moduleDependency = (JkModuleDependency) this.scopedDependency.dependency();
        return moduleDependency.moduleId();
    }

    @Override
    public String toString() {
        return this.asScopedDependency().toString();
    }

}
