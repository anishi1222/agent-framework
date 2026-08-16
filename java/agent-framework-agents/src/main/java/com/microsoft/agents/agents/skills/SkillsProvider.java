// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.Tool;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Advertises available skills and contributes progressive-disclosure tools to an agent run.
 *
 * <p>Caller-supplied sources are used as-is and are never automatically cached, because an
 * unkeyed cache could leak context-specific skills across sessions. Skills supplied directly are
 * treated as context-independent and are cached and deduplicated automatically.
 */
public final class SkillsProvider implements ContextProvider {
    /** Default context-provider identifier. */
    public static final String DEFAULT_SOURCE_ID = "agent_skills";

    /** Tool name that loads complete skill instructions. */
    public static final String LOAD_SKILL_TOOL_NAME = "load_skill";

    /** Tool name that reads one skill resource. */
    public static final String READ_SKILL_RESOURCE_TOOL_NAME = "read_skill_resource";

    /** Tool name that runs one skill script. */
    public static final String RUN_SKILL_SCRIPT_TOOL_NAME = "run_skill_script";

    /** Built-in instructions for reading resources. */
    public static final String RESOURCE_INSTRUCTIONS =
            "- Use `read_skill_resource` to read referenced resources using the name exactly as listed.\n";

    /** Built-in instructions for running scripts. */
    public static final String SCRIPT_RUNNER_INSTRUCTIONS =
            "- Use `run_skill_script` to run referenced scripts using the name exactly as listed.\n"
                    + "- Pass named script arguments inside `args` as a JSON object, or positional CLI arguments "
                    + "as a string array.\n";

    /** Default progressive-disclosure instruction template. */
    public static final String DEFAULT_INSTRUCTION_TEMPLATE = """
            You have access to skills containing domain-specific knowledge and capabilities.
            Each skill provides specialized instructions, reference documents, and assets for specific tasks.

            <available_skills>
            {skills}
            </available_skills>

            When a task aligns with a skill's domain, follow these steps in exact order:
            - Use `load_skill` to retrieve the skill's instructions.
            - Follow the provided guidance.
            {resource_instructions}{runner_instructions}Only load what is needed, when it is needed.""";

    private static final StateValue.ObjectValue STRING_OUTPUT =
            StateValue.object(Map.of("type", StateValue.string("string")));

    private static final StateValue.ObjectValue OPEN_OUTPUT = StateValue.object(Map.of());

    private final SkillsSource source;
    private final SkillsProviderOptions options;

    /**
     * Creates a provider for a caller-owned source pipeline.
     *
     * @param source source used without automatic decoration
     */
    public SkillsProvider(SkillsSource source) {
        this(source, SkillsProviderOptions.defaults());
    }

    /**
     * Creates a configured provider for a caller-owned source pipeline.
     *
     * @param source source used without automatic decoration
     * @param options provider options
     */
    public SkillsProvider(SkillsSource source, SkillsProviderOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Creates a cached and deduplicated provider for context-independent skills.
     *
     * @param skills skill list
     */
    public SkillsProvider(List<? extends Skill> skills) {
        this(skills, SkillsProviderOptions.defaults(), false, null);
    }

    /**
     * Creates a provider for context-independent skills.
     *
     * @param skills skill list
     * @param options provider options
     * @param disableCaching whether every run should re-read the in-memory source
     * @param refreshInterval optional cache refresh interval
     */
    public SkillsProvider(
            List<? extends Skill> skills,
            SkillsProviderOptions options,
            boolean disableCaching,
            Duration refreshInterval) {
        SkillsSource leaf = new InMemorySkillsSource(skills);
        SkillsSource cached =
                disableCaching ? leaf : new CachingSkillsSource(leaf, refreshInterval, ignored -> "in-memory");
        source = new DeduplicatingSkillsSource(cached);
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Creates a provider for file-based skills.
     *
     * @param roots skill roots
     * @param fileOptions file discovery options
     * @param providerOptions provider options
     * @param disableCaching whether every run should rescan files
     * @param refreshInterval optional cache refresh interval
     * @return file-backed provider
     */
    public static SkillsProvider fromPaths(
            List<Path> roots,
            FileSkillsSourceOptions fileOptions,
            SkillsProviderOptions providerOptions,
            boolean disableCaching,
            Duration refreshInterval) {
        SkillsSource leaf = new FileSkillsSource(roots, fileOptions);
        SkillsSource cached =
                disableCaching ? leaf : new CachingSkillsSource(leaf, refreshInterval, ignored -> "files");
        return new SkillsProvider(new DeduplicatingSkillsSource(cached), providerOptions);
    }

    @Override
    public String id() {
        return options.sourceId();
    }

    @Override
    public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        Objects.requireNonNull(request, "request");
        RunCancellation cancellation = request.runContext().cancellation();
        SkillsSourceContext sourceContext = new SkillsSourceContext(request.runContext(), request.session());
        return source.getSkillsAsync(sourceContext, cancellation).thenApply(skills -> {
            List<Skill> safeSkills = List.copyOf(Objects.requireNonNull(skills, "skills"));
            if (safeSkills.isEmpty()) {
                return ContextContribution.empty();
            }
            String instructions = SkillRendering.availableSkills(options.instructionTemplate(), safeSkills);
            return new ContextContribution(List.of(instructions), List.of(), Map.of(), createTools(safeSkills));
        });
    }

    private List<Tool> createTools(List<Skill> skills) {
        return List.of(
                FunctionTool.create(
                        new ToolMetadata(
                                LOAD_SKILL_TOOL_NAME,
                                "Loads the full instructions for a specific skill.",
                                Set.of(ToolCapability.FUNCTION),
                                options.loadApprovalMode(),
                                oneStringInput("skill_name", "The name of the skill to load."),
                                STRING_OUTPUT),
                        (context, arguments) ->
                                loadSkill(skills, string(arguments, "skill_name"), context.cancellation())),
                FunctionTool.create(
                        new ToolMetadata(
                                READ_SKILL_RESOURCE_TOOL_NAME,
                                "Reads a resource associated with a skill.",
                                Set.of(ToolCapability.FUNCTION),
                                options.resourceApprovalMode(),
                                twoStringInput(
                                        "skill_name",
                                        "The name of the skill.",
                                        "resource_name",
                                        "The name of the resource."),
                                STRING_OUTPUT),
                        (context, arguments) -> readResource(
                                skills,
                                string(arguments, "skill_name"),
                                string(arguments, "resource_name"),
                                context.cancellation())),
                FunctionTool.create(
                        new ToolMetadata(
                                RUN_SKILL_SCRIPT_TOOL_NAME,
                                "Runs a script associated with a skill.",
                                Set.of(ToolCapability.FUNCTION),
                                options.scriptApprovalMode(),
                                scriptInput(),
                                OPEN_OUTPUT),
                        (context, arguments) -> runScript(
                                skills,
                                string(arguments, "skill_name"),
                                string(arguments, "script_name"),
                                arguments.values().getOrDefault("args", StateValue.nullValue()),
                                context.cancellation())));
    }

    private static CompletionStage<StateValue> loadSkill(
            List<Skill> skills, String skillName, RunCancellation cancellation) {
        if (skillName.isBlank()) {
            return completed("Error: Skill name cannot be empty.");
        }
        Skill skill = findSkill(skills, skillName);
        if (skill == null) {
            return completed("Error: Skill '" + skillName + "' not found.");
        }
        return skill.contentAsync(cancellation).thenApply(StateValue::string);
    }

    private static CompletionStage<StateValue> readResource(
            List<Skill> skills, String skillName, String resourceName, RunCancellation cancellation) {
        if (skillName.isBlank()) {
            return completed("Error: Skill name cannot be empty.");
        }
        if (resourceName.isBlank()) {
            return completed("Error: Resource name cannot be empty.");
        }
        Skill skill = findSkill(skills, skillName);
        if (skill == null) {
            return completed("Error: Skill '" + skillName + "' not found.");
        }
        return skill.resourceAsync(resourceName, cancellation).thenCompose(resource -> {
            if (resource == null) {
                return completed("Error: Resource '" + resourceName + "' not found in skill '" + skillName + "'.");
            }
            return resource.readAsync(cancellation).thenApply(SkillResourceContent::toStateValue);
        });
    }

    private static CompletionStage<StateValue> runScript(
            List<Skill> skills,
            String skillName,
            String scriptName,
            StateValue arguments,
            RunCancellation cancellation) {
        if (skillName.isBlank()) {
            return completed("Error: Skill name cannot be empty.");
        }
        if (scriptName.isBlank()) {
            return completed("Error: Script name cannot be empty.");
        }
        Skill skill = findSkill(skills, skillName);
        if (skill == null) {
            return completed("Error: Skill '" + skillName + "' not found.");
        }
        return skill.scriptAsync(scriptName, cancellation).thenCompose(script -> {
            if (script == null) {
                return completed("Error: Script '" + scriptName + "' not found in skill '" + skillName + "'.");
            }
            return script.runAsync(skill, arguments, cancellation);
        });
    }

    private static Skill findSkill(List<Skill> skills, String name) {
        String key = SkillValidation.caseKey(name);
        return skills.stream()
                .filter(skill ->
                        SkillValidation.caseKey(skill.frontmatter().name()).equals(key))
                .findFirst()
                .orElse(null);
    }

    private static CompletionStage<StateValue> completed(String value) {
        return CompletableFuture.completedFuture(StateValue.string(value));
    }

    private static String string(StateValue.ObjectValue arguments, String name) {
        StateValue value = arguments.values().get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string.");
    }

    private static StateValue.ObjectValue oneStringInput(String name, String description) {
        return twoStringInput(name, description, null, null);
    }

    private static StateValue.ObjectValue twoStringInput(
            String firstName, String firstDescription, String secondName, String secondDescription) {
        LinkedHashMap<String, StateValue> properties = new LinkedHashMap<>();
        properties.put(firstName, describedString(firstDescription));
        ArrayList<StateValue> required = new ArrayList<>();
        required.add(StateValue.string(firstName));
        if (secondName != null) {
            properties.put(secondName, describedString(secondDescription));
            required.add(StateValue.string(secondName));
        }
        LinkedHashMap<String, StateValue> schema = new LinkedHashMap<>();
        schema.put("type", StateValue.string("object"));
        schema.put("properties", StateValue.object(properties));
        schema.put("required", StateValue.array(required));
        schema.put("additionalProperties", StateValue.bool(false));
        return StateValue.object(schema);
    }

    private static StateValue.ObjectValue scriptInput() {
        LinkedHashMap<String, StateValue> properties = new LinkedHashMap<>();
        properties.put("skill_name", describedString("The name of the skill."));
        properties.put(
                "script_name", describedString("The script name exactly as listed, including any directory prefix."));
        properties.put(
                "args",
                StateValue.object(Map.of(
                        "anyOf",
                        StateValue.array(List.of(
                                StateValue.object(Map.of(
                                        "type",
                                        StateValue.string("object"),
                                        "additionalProperties",
                                        StateValue.bool(true))),
                                StateValue.object(Map.of(
                                        "type",
                                        StateValue.string("array"),
                                        "items",
                                        StateValue.object(Map.of("type", StateValue.string("string"))))),
                                StateValue.object(Map.of("type", StateValue.string("null"))))))));
        LinkedHashMap<String, StateValue> schema = new LinkedHashMap<>();
        schema.put("type", StateValue.string("object"));
        schema.put("properties", StateValue.object(properties));
        schema.put(
                "required",
                StateValue.array(List.of(StateValue.string("skill_name"), StateValue.string("script_name"))));
        schema.put("additionalProperties", StateValue.bool(false));
        return StateValue.object(schema);
    }

    private static StateValue.ObjectValue describedString(String description) {
        return StateValue.object(Map.of(
                "type", StateValue.string("string"),
                "description", StateValue.string(description)));
    }
}
