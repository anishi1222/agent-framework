// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptAgentDefinitionParserTest {
    private static final String JSON = """
            {
              "kind": "Prompt",
              "name": "support",
              "displayName": "Support",
              "description": "Answers support questions.",
              "metadata": {
                "tenant": "north",
                "priority": 3
              },
              "model": {
                "id": "gpt-4.1",
                "provider": "OpenAI",
                "apiType": "Responses",
                "options": {
                  "frequencyPenalty": 0.1,
                  "maxOutputTokens": 512,
                  "presencePenalty": -0.2,
                  "seed": 42,
                  "temperature": 0.25,
                  "topP": 0.9,
                  "stopSequences": ["DONE"],
                  "allowMultipleToolCalls": true
                }
              },
              "tools": ["weather"],
              "contextProviders": ["memory"],
              "instructions": "Be concise.",
              "additionalInstructions": "Cite the source."
            }
            """;

    private static final String YAML = """
            kind: Prompt
            name: support
            displayName: Support
            description: Answers support questions.
            metadata:
              tenant: north
              priority: 3
            model:
              id: gpt-4.1
              provider: OpenAI
              apiType: Responses
              options:
                frequencyPenalty: 0.1
                maxOutputTokens: 512
                presencePenalty: -0.2
                seed: 42
                temperature: 0.25
                topP: 0.9
                stopSequences:
                  - DONE
                allowMultipleToolCalls: true
            tools:
              - weather
            contextProviders:
              - memory
            instructions: Be concise.
            additionalInstructions: Cite the source.
            """;

    @Test
    void parseJsonAndYaml_shouldProduceEquivalentImmutableDefinitions() {
        // Arrange and Act
        PromptAgentDefinition json = PromptAgentDefinitionParser.parseJson(JSON);
        PromptAgentDefinition yaml = PromptAgentDefinitionParser.parseYaml(YAML);

        // Assert
        assertThat(json).isEqualTo(yaml);
        assertThat(json.name()).isEqualTo("support");
        assertThat(json.model().options().maxOutputTokens()).isEqualTo(512);
        assertThat(json.combinedInstructions()).isEqualTo("Be concise.\n\nCite the source.");
        assertThat(json.metadata())
                .containsEntry("tenant", StateValue.string("north"))
                .containsEntry("priority", StateValue.integer(3));
        assertThatThrownBy(() -> json.tools().add("other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> json.metadata().put("other", StateValue.bool(true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parsePath_shouldSelectFormatFromExtension(@TempDir Path directory) throws Exception {
        // Arrange
        Path path = directory.resolve("agent.yaml");
        Files.writeString(path, YAML);

        // Act
        PromptAgentDefinition definition = PromptAgentDefinitionParser.parse(path);

        // Assert
        assertThat(definition.name()).isEqualTo("support");
    }

    @Test
    void parseJson_shouldRejectMalformedDuplicateAndTrailingDocuments() {
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson("{"))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("Malformed JSON");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson("""
                {"kind":"Prompt","kind":"Agent","name":"a","model":{"id":"m","provider":"p"}}
                """))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("Malformed JSON");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson(JSON + "{}"))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("Malformed JSON");
    }

    @Test
    void parseYaml_shouldRejectMalformedDuplicatesAliasesAndMultipleDocuments() {
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseYaml("kind: ["))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("Malformed YAML");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseYaml("""
                kind: Prompt
                name: first
                name: second
                model:
                  id: model
                  provider: provider
                """))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("Malformed YAML");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseYaml("""
                kind: Prompt
                name: agent
                model: &model
                  id: model
                  provider: provider
                metadata:
                  copy: *model
                """))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("aliases");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseYaml(YAML + "\n---\n" + YAML))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("exactly one document");
    }

    @Test
    void parse_shouldRejectUnknownFieldsAtEverySchemaLevel() {
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson(
                        JSON.replace("\"kind\": \"Prompt\",", "\"kind\": \"Prompt\", \"unexpected\": true,")))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("unexpected");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson(
                        JSON.replace("\"provider\": \"OpenAI\",", "\"provider\": \"OpenAI\", \"endpoint\": \"x\",")))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("endpoint");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson(
                        JSON.replace("\"temperature\": 0.25,", "\"temperature\": 0.25, \"topK\": 5,")))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("topK");
    }

    @Test
    void parse_shouldRejectWrongTypesMissingFieldsAndInvalidRanges() {
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson("""
                {"kind":"Prompt","name":"a","model":{"provider":"p"}}
                """))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("$.model.id");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson("""
                {"kind":"Prompt","name":"a","model":{"id":"m","provider":"p"},"tools":"tool"}
                """))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("array of strings");
        assertThatThrownBy(() -> PromptAgentDefinitionParser.parseJson("""
                {
                  "kind":"Prompt",
                  "name":"a",
                  "model":{"id":"m","provider":"p","options":{"temperature":3}}
                }
                """))
                .isInstanceOf(DeclarativeAgentParseException.class)
                .hasMessageContaining("temperature");
    }

    @Test
    void definition_shouldRejectDuplicateReferencesAndDefensivelyCopyInputs() {
        ArrayList<String> tools = new ArrayList<>();
        tools.add("one");
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put("key", StateValue.string("value"));

        PromptAgentDefinition definition = new PromptAgentDefinition(
                "Prompt",
                "agent",
                null,
                null,
                metadata,
                new PromptModelDefinition("model", "provider", null, null),
                tools,
                null,
                null,
                null);
        tools.add("two");
        metadata.put("other", StateValue.string("changed"));

        assertThat(definition.tools()).containsExactly("one");
        assertThat(definition.metadata()).containsOnlyKeys("key");
        assertThatThrownBy(() -> new PromptAgentDefinition(
                        "Prompt",
                        "agent",
                        null,
                        null,
                        null,
                        new PromptModelDefinition("model", "provider", null, null),
                        java.util.List.of("one", "one"),
                        null,
                        null,
                        null))
                .isInstanceOf(DeclarativeAgentValidationException.class)
                .hasMessageContaining("Duplicate tools reference");
    }
}
