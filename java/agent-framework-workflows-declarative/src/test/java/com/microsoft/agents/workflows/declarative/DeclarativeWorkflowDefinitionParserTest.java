// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarativeWorkflowDefinitionParserTest {
    private static final String JSON = """
            {
              "kind": "Workflow",
              "id": "support-flow",
              "schemaVersion": 2,
              "allowCycles": false,
              "entry": "start",
              "output": "finish",
              "nodes": [
                {"id": "start", "executor": "normalize"},
                {"id": "positive", "executor": "decorate"},
                {"id": "fallback", "executor": "fallback"},
                {"id": "finish", "executor": "finish"}
              ],
              "edges": [
                {"kind": "conditional", "source": "start", "target": "positive", "condition": "positive"},
                {"kind": "conditional", "source": "start", "target": "fallback", "condition": "notPositive"},
                {"kind": "direct", "source": "positive", "target": "finish"},
                {"kind": "direct", "source": "fallback", "target": "finish"}
              ]
            }
            """;

    private static final String YAML = """
            kind: Workflow
            id: support-flow
            schemaVersion: 2
            allowCycles: false
            entry: start
            output: finish
            nodes:
              - id: start
                executor: normalize
              - id: positive
                executor: decorate
              - id: fallback
                executor: fallback
              - id: finish
                executor: finish
            edges:
              - kind: conditional
                source: start
                target: positive
                condition: positive
              - kind: conditional
                source: start
                target: fallback
                condition: notPositive
              - kind: direct
                source: positive
                target: finish
              - kind: direct
                source: fallback
                target: finish
            """;

    @Test
    void parseJsonAndYaml_shouldProduceEquivalentImmutableDefinitions() {
        DeclarativeWorkflowDefinition json = DeclarativeWorkflowDefinitionParser.parseJson(JSON);
        DeclarativeWorkflowDefinition yaml = DeclarativeWorkflowDefinitionParser.parseYaml(YAML);

        assertThat(json).isEqualTo(yaml);
        assertThat(json.id()).isEqualTo("support-flow");
        assertThat(json.schemaVersion()).isEqualTo(2);
        assertThat(json.nodes()).hasSize(4);
        assertThat(json.edges()).hasSize(4);
        assertThatThrownBy(() -> json.nodes().add(new DeclarativeNodeDefinition("x", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parsePath_shouldSelectFormatFromExtension(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("workflow.json");
        Files.writeString(path, JSON);

        DeclarativeWorkflowDefinition definition = DeclarativeWorkflowDefinitionParser.parse(path);

        assertThat(definition.id()).isEqualTo("support-flow");
    }

    @Test
    void parse_shouldRejectMalformedDuplicateUnknownAndTrailingDocuments() {
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("{"))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("Malformed JSON");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("""
                {"kind":"Workflow","kind":"Other","id":"x","entry":"a","output":"a",
                 "nodes":[{"id":"a","executor":"a"}]}
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("Malformed JSON");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson(
                        JSON.replace("\"id\": \"support-flow\",", "\"id\": \"support-flow\", \"name\": \"x\",")))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseYaml("kind: ["))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("Malformed YAML");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseYaml("""
                kind: Workflow
                id: one
                id: two
                entry: a
                output: a
                nodes:
                  - id: a
                    executor: a
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("Malformed YAML");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseYaml(YAML + "\n---\n" + YAML))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("exactly one document");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseYaml("""
                kind: Workflow
                id: alias
                entry: a
                output: a
                nodes: &nodes
                  - id: a
                    executor: a
                edges: *nodes
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("aliases");
    }

    @Test
    void parse_shouldRejectUnknownEdgeKindsAndWrongTypes() {
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("""
                {
                  "kind":"Workflow","id":"x","entry":"a","output":"a",
                  "nodes":[{"id":"a","executor":"a"}],
                  "edges":[{"kind":"powerFx","source":"a","target":"a"}]
                }
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("Unsupported edge kind");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("""
                {
                  "kind":"Workflow","id":"x","entry":"a","output":"a",
                  "nodes":{"id":"a","executor":"a"}
                }
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("$.nodes");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("""
                {
                  "kind":"Workflow","id":"x","entry":"a","output":"b",
                  "nodes":[{"id":"a","executor":"a"},{"id":"b","executor":"b"}],
                  "edges":[{"kind":"direct","source":"a","target":"b","condition":"unexpected"}]
                }
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("condition");
    }

    @Test
    void definition_shouldRejectDuplicateIdsMissingReferencesAndDuplicateRoutes() {
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("""
                {
                  "kind":"Workflow","id":"x","entry":"a","output":"a",
                  "nodes":[{"id":"a","executor":"one"},{"id":"a","executor":"two"}]
                }
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("Duplicate workflow node id");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("""
                {
                  "kind":"Workflow","id":"x","entry":"missing","output":"a",
                  "nodes":[{"id":"a","executor":"a"}]
                }
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("missing node 'missing'");
        assertThatThrownBy(() -> DeclarativeWorkflowDefinitionParser.parseJson("""
                {
                  "kind":"Workflow","id":"x","entry":"a","output":"b",
                  "nodes":[{"id":"a","executor":"a"},{"id":"b","executor":"b"}],
                  "edges":[
                    {"kind":"direct","source":"a","target":"b"},
                    {"kind":"conditional","source":"a","target":"b","condition":"c"}
                  ]
                }
                """))
                .isInstanceOf(DeclarativeWorkflowParseException.class)
                .hasMessageContaining("Duplicate workflow route");
    }

    @Test
    void definition_shouldValidateFanInAndFanOutShapes() {
        assertThatThrownBy(() -> new FanOutEdgeDefinition("a", java.util.List.of("b", "b")))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("duplicate target");
        assertThatThrownBy(() -> new FanInEdgeDefinition(java.util.List.of("a"), "b"))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("at least two sources");
    }
}
