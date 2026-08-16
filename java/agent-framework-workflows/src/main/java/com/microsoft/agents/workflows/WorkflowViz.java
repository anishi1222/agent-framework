// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Produces deterministic Graphviz DOT and Mermaid source for an immutable workflow graph.
 *
 * <p>This class has no Graphviz runtime dependency and does not invoke external binaries. Callers
 * may pass the returned source to their renderer of choice.
 */
public final class WorkflowViz {
    private final Workflow<?, ?> workflow;

    /**
     * Creates a visualizer for one immutable workflow.
     *
     * @param workflow workflow to visualize
     */
    public WorkflowViz(Workflow<?, ?> workflow) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    /**
     * Returns deterministic Graphviz DOT source.
     *
     * @return DOT digraph
     */
    public String toDot() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("digraph Workflow {");
        lines.add("  rankdir=TB;");
        for (WorkflowNode<?, ?> node : workflow.nodes().values()) {
            ArrayList<String> attributes = new ArrayList<>();
            attributes.add("label=\"" + escapeDot(node.id().value()) + "\"");
            attributes.add("shape=box");
            if (node.id().equals(workflow.entryNodeId())) {
                attributes.add("style=filled");
                attributes.add("fillcolor=lightgreen");
            }
            if (node.id().equals(workflow.outputNodeId())) {
                attributes.add("peripheries=2");
            }
            lines.add("  \"" + escapeDot(node.id().value()) + "\" [" + String.join(", ", attributes) + "];");
        }
        workflow.edges().forEach(edge -> {
            String attributes = edge instanceof ConditionalEdge<?> ? " [style=dashed, label=\"conditional\"]" : "";
            lines.add("  \""
                    + escapeDot(edge.sourceId().value())
                    + "\" -> \""
                    + escapeDot(edge.targetId().value())
                    + "\""
                    + attributes
                    + ";");
        });
        for (EdgeGroup group : workflow.edgeGroups()) {
            if (group instanceof FanOutEdgeGroup fanOut) {
                for (NodeId target : fanOut.targetIds()) {
                    lines.add("  \""
                            + escapeDot(fanOut.sourceId().value())
                            + "\" -> \""
                            + escapeDot(target.value())
                            + "\" [label=\"fan-out\"];");
                }
            } else if (group instanceof FanInEdgeGroup fanIn) {
                String junction = fanInJunctionId(fanIn);
                lines.add("  \""
                        + escapeDot(junction)
                        + "\" [shape=ellipse, label=\"fan-in\", style=filled, fillcolor=lightblue];");
                for (NodeId source : fanIn.sourceIds()) {
                    lines.add("  \"" + escapeDot(source.value()) + "\" -> \"" + escapeDot(junction) + "\";");
                }
                lines.add("  \""
                        + escapeDot(junction)
                        + "\" -> \""
                        + escapeDot(fanIn.targetId().value())
                        + "\";");
            }
        }
        lines.add("}");
        return String.join("\n", lines);
    }

    /**
     * Returns deterministic Mermaid flowchart source.
     *
     * @return Mermaid flowchart
     */
    public String toMermaid() {
        AliasRegistry aliases = new AliasRegistry();
        workflow.nodes().keySet().forEach(node -> aliases.alias(node.value()));
        ArrayList<String> lines = new ArrayList<>();
        lines.add("flowchart TD");
        for (WorkflowNode<?, ?> node : workflow.nodes().values()) {
            String label = escapeMermaidLabel(
                    node.id().value() + (node.id().equals(workflow.entryNodeId()) ? " (Start)" : ""));
            lines.add("  " + aliases.alias(node.id().value()) + "[\"" + label + "\"]");
        }
        for (Edge edge : workflow.edges()) {
            String source = aliases.alias(edge.sourceId().value());
            String target = aliases.alias(edge.targetId().value());
            lines.add(
                    edge instanceof ConditionalEdge<?>
                            ? "  " + source + " -. conditional .-> " + target
                            : "  " + source + " --> " + target);
        }
        for (EdgeGroup group : workflow.edgeGroups()) {
            if (group instanceof FanOutEdgeGroup fanOut) {
                for (NodeId target : fanOut.targetIds()) {
                    lines.add("  "
                            + aliases.alias(fanOut.sourceId().value())
                            + " -->|fan-out| "
                            + aliases.alias(target.value()));
                }
            } else if (group instanceof FanInEdgeGroup fanIn) {
                String junction = aliases.alias(fanInJunctionId(fanIn));
                lines.add("  " + junction + "((fan-in))");
                for (NodeId source : fanIn.sourceIds()) {
                    lines.add("  " + aliases.alias(source.value()) + " --> " + junction);
                }
                lines.add("  " + junction + " --> "
                        + aliases.alias(fanIn.targetId().value()));
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Renders one supported textual diagram format.
     *
     * @param format diagram format
     * @return diagram source
     */
    public String render(WorkflowDiagramFormat format) {
        return switch (Objects.requireNonNull(format, "format")) {
            case DOT -> toDot();
            case MERMAID -> toMermaid();
        };
    }

    /**
     * Writes UTF-8 diagram source to a caller-selected path.
     *
     * @param path output path
     * @param format diagram format
     * @return normalized absolute output path
     */
    public Path write(Path path, WorkflowDiagramFormat format) {
        Path output = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        try {
            Files.writeString(output, render(format), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new WorkflowException("Failed to write workflow diagram to '" + output + "'.", failure);
        }
        return output;
    }

    private static String fanInJunctionId(FanInEdgeGroup fanIn) {
        return "__fan_in__" + fanIn.targetId().value();
    }

    private static String escapeDot(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String escapeMermaidLabel(String value) {
        return value.replace("&", "&amp;")
                .replace("|", "&#124;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", "<br/>")
                .replace("\n", "<br/>")
                .replace("\r", "<br/>");
    }

    private static final class AliasRegistry {
        private final Map<String, String> aliases = new LinkedHashMap<>();

        private final Set<String> used = new HashSet<>();

        String alias(String raw) {
            return aliases.computeIfAbsent(raw, this::create);
        }

        private String create(String raw) {
            String base = sanitize(raw);
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate)) {
                candidate = base + "_" + suffix++;
            }
            return candidate;
        }

        private static String sanitize(String raw) {
            StringBuilder result = new StringBuilder(raw.length());
            boolean previousReplacement = false;
            for (int index = 0; index < raw.length(); index++) {
                char character = raw.charAt(index);
                boolean valid = character == '_'
                        || character >= 'a' && character <= 'z'
                        || character >= 'A' && character <= 'Z'
                        || character >= '0' && character <= '9';
                if (valid) {
                    result.append(character);
                    previousReplacement = false;
                } else if (!previousReplacement) {
                    result.append('_');
                    previousReplacement = true;
                }
            }
            while (!result.isEmpty() && result.charAt(result.length() - 1) == '_') {
                result.deleteCharAt(result.length() - 1);
            }
            if (result.isEmpty()) {
                result.append("node");
            }
            if (Character.isDigit(result.charAt(0))) {
                result.insert(0, "n_");
            }
            return result.toString();
        }
    }
}
