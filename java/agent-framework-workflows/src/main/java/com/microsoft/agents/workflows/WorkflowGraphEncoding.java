// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Produces delimiter-safe canonical encodings for workflow graph identity and ordering. */
final class WorkflowGraphEncoding {
    private static final int FORMAT_VERSION = 1;

    private WorkflowGraphEncoding() {}

    static String fingerprint(
            String workflowId,
            int schemaVersion,
            Class<?> inputType,
            Class<?> outputType,
            Map<NodeId, WorkflowNode<?, ?>> nodes,
            List<Edge> edges,
            List<EdgeGroup> edgeGroups,
            NodeId entryNodeId,
            NodeId outputNodeId,
            boolean cyclesAllowed) {
        Encoder encoder = new Encoder();
        encoder.tag("workflow-graph");
        encoder.integer(FORMAT_VERSION);
        encoder.string(workflowId);
        encoder.integer(schemaVersion);
        encoder.string(inputType.getName());
        encoder.string(outputType.getName());
        encoder.integer(nodes.size());
        nodes.values().forEach(node -> {
            encoder.tag("node");
            encoder.nodeId(node.id());
            encoder.string(node.inputType().getName());
            encoder.string(node.outputType().getName());
        });
        encoder.integer(edges.size());
        edges.forEach(edge -> writeEdge(encoder, edge));
        encoder.integer(edgeGroups.size());
        edgeGroups.forEach(group -> writeEdgeGroup(encoder, group));
        encoder.tag("entry");
        encoder.nodeId(entryNodeId);
        encoder.tag("output");
        encoder.nodeId(outputNodeId);
        encoder.tag("cycles-allowed");
        encoder.bool(cyclesAllowed);
        return sha256(encoder.bytes());
    }

    static int compareEdgeGroups(EdgeGroup left, EdgeGroup right) {
        return compareUnsigned(edgeGroupBytes(left), edgeGroupBytes(right));
    }

    private static byte[] edgeGroupBytes(EdgeGroup group) {
        Encoder encoder = new Encoder();
        encoder.tag("edge-group");
        encoder.integer(FORMAT_VERSION);
        writeEdgeGroup(encoder, group);
        return encoder.bytes();
    }

    private static void writeEdge(Encoder encoder, Edge edge) {
        if (edge instanceof DirectEdge direct) {
            encoder.tag("direct-edge");
            encoder.nodeId(direct.sourceId());
            encoder.nodeId(direct.targetId());
            return;
        }
        if (edge instanceof ConditionalEdge<?> conditional) {
            encoder.tag("conditional-edge");
            encoder.nodeId(conditional.sourceId());
            encoder.nodeId(conditional.targetId());
            encoder.string(conditional.payloadType().getName());
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported workflow edge type " + edge.getClass().getName() + ".");
    }

    private static void writeEdgeGroup(Encoder encoder, EdgeGroup group) {
        if (group instanceof FanInEdgeGroup fanIn) {
            encoder.tag("fan-in");
            encoder.nodeIds(fanIn.sourceIds());
            encoder.nodeId(fanIn.targetId());
            return;
        }
        if (group instanceof FanOutEdgeGroup fanOut) {
            encoder.tag("fan-out");
            encoder.nodeId(fanOut.sourceId());
            encoder.nodeIds(fanOut.targetIds());
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported workflow edge-group type " + group.getClass().getName() + ".");
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int shared = Math.min(left.length, right.length);
        for (int index = 0; index < shared; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", exception);
        }
    }

    private static final class Encoder {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private final DataOutputStream output = new DataOutputStream(bytes);

        void tag(String value) {
            string(value);
        }

        void string(String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            write(() -> output.writeInt(encoded.length));
            write(() -> output.write(encoded));
        }

        void nodeId(NodeId nodeId) {
            string(nodeId.value());
        }

        void nodeIds(List<NodeId> nodeIds) {
            integer(nodeIds.size());
            nodeIds.forEach(this::nodeId);
        }

        void integer(int value) {
            write(() -> output.writeInt(value));
        }

        void bool(boolean value) {
            write(() -> output.writeBoolean(value));
        }

        byte[] bytes() {
            write(output::flush);
            return bytes.toByteArray();
        }

        private static void write(IoAction action) {
            try {
                action.run();
            } catch (IOException exception) {
                throw new IllegalStateException("In-memory workflow graph encoding failed.", exception);
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
