// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.Tool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Contributes plan/execute mode guidance and session-persisted mode tools. */
public final class AgentModeProvider implements ContextProvider {
    /** Default context-provider identifier. */
    public static final String DEFAULT_SOURCE_ID = "agent_mode";

    /** Mode setter tool. */
    public static final String SET_TOOL_NAME = "mode_set";

    /** Mode getter tool. */
    public static final String GET_TOOL_NAME = "mode_get";

    /** Built-in plan and execute modes. */
    public static final List<AgentMode> DEFAULT_MODES = List.of(
            new AgentMode(
                    "plan",
                    "Plan complex work, create concrete todos, and obtain any required user "
                            + "decisions before switching to execute mode."),
            new AgentMode(
                    "execute",
                    "Work autonomously through the todo list, adapt to failures, and do not stop "
                            + "until the requested outcome is complete or genuinely blocked."));

    /** Built-in mode instruction template. */
    public static final String DEFAULT_INSTRUCTION_TEMPLATE = """
            Current operating mode: {current_mode}

            Available modes:
            {available_modes}

            Follow the current mode instructions. Use `mode_set` when a deliberate mode transition
            is appropriate, and use `mode_get` if the current mode is uncertain.""";

    private static final String STATE_PREFIX = "harness.mode.";

    private final AgentModeProviderOptions options;

    private final Map<String, AgentMode> modes;

    /** Creates a provider with the default plan/execute modes. */
    public AgentModeProvider() {
        this(AgentModeProviderOptions.defaults());
    }

    /**
     * Creates a configured provider.
     *
     * @param options provider options
     */
    public AgentModeProvider(AgentModeProviderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        LinkedHashMap<String, AgentMode> byName = new LinkedHashMap<>();
        options.modes().forEach(mode -> byName.put(mode.name(), mode));
        modes = Map.copyOf(byName);
    }

    @Override
    public String id() {
        return options.sourceId();
    }

    /** Returns the provider-owned session-state key. */
    public String stateKey() {
        return STATE_PREFIX + options.sourceId();
    }

    @Override
    public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        Objects.requireNonNull(request, "request");
        requireActive(request.runContext().cancellation());
        ModeState state = consumeNotification(request.session());
        String instructions = options.instructionTemplate()
                .replace("{current_mode}", state.currentMode())
                .replace("{available_modes}", availableModesText());
        List<Message> messages = state.previousMode() == null
                ? List.of()
                : List.of(Message.text(
                        Role.USER,
                        "[Mode changed from \""
                                + state.previousMode()
                                + "\" to \""
                                + state.currentMode()
                                + "\". Follow the new mode instructions.]"));
        return CompletableFuture.completedFuture(
                new ContextContribution(List.of(instructions), messages, Map.of(), createTools(request.session())));
    }

    /**
     * Reads the current normalized mode.
     *
     * @param session active session
     * @param cancellation caller-owned cancellation
     * @return current mode stage
     */
    public CompletionStage<String> getModeAsync(AgentSession session, RunCancellation cancellation) {
        requireActive(cancellation);
        return CompletableFuture.completedFuture(
                readState(Objects.requireNonNull(session, "session")).currentMode());
    }

    /**
     * Changes the current mode externally and schedules a notification for the next run.
     *
     * @param session active session
     * @param mode requested mode
     * @param cancellation caller-owned cancellation
     * @return normalized current mode
     */
    public CompletionStage<String> setModeAsync(AgentSession session, String mode, RunCancellation cancellation) {
        requireActive(cancellation);
        return CompletableFuture.completedFuture(setMode(Objects.requireNonNull(session, "session"), mode, true));
    }

    private List<Tool> createTools(AgentSession session) {
        return List.of(
                FunctionTool.create(
                        metadata(
                                SET_TOOL_NAME,
                                "Changes the harness operating mode.",
                                HarnessToolSupport.objectSchema(
                                        Map.of("mode", HarnessToolSupport.stringProperty("Available mode name.")),
                                        List.of("mode"))),
                        (context, arguments) -> CompletableFuture.completedFuture(StateValue.string(
                                setMode(session, HarnessToolSupport.string(arguments, "mode"), false)))),
                FunctionTool.create(
                        metadata(
                                GET_TOOL_NAME,
                                "Returns the current harness operating mode.",
                                HarnessToolSupport.objectSchema(Map.of(), List.of())),
                        (context, arguments) -> CompletableFuture.completedFuture(
                                StateValue.string(readState(session).currentMode()))));
    }

    private String setMode(AgentSession session, String mode, boolean notify) {
        String normalized = AgentModeProviderOptions.normalizeName(mode);
        if (!modes.containsKey(normalized)) {
            throw new IllegalArgumentException("Unsupported mode '" + mode + "'. Available modes: " + modes.keySet());
        }
        Holder<String> result = new Holder<>();
        session.updateState(stateKey(), current -> {
            ModeState state = decodeState(current);
            String previous = state.currentMode();
            String notification = notify && !previous.equals(normalized) ? previous : state.previousMode();
            result.value = normalized;
            return encodeState(new ModeState(normalized, notification));
        });
        return result.value;
    }

    private ModeState consumeNotification(AgentSession session) {
        Holder<ModeState> consumed = new Holder<>();
        session.updateState(stateKey(), current -> {
            ModeState state = decodeState(current);
            consumed.value = state;
            return encodeState(new ModeState(state.currentMode(), null));
        });
        return consumed.value;
    }

    private ModeState readState(AgentSession session) {
        return decodeState(session.state().get(stateKey()).orElse(null));
    }

    private ModeState decodeState(StateValue value) {
        if (value == null) {
            return new ModeState(options.defaultMode(), null);
        }
        StateValue.ObjectValue object = HarnessToolSupport.object(value, "mode state");
        String current = requiredString(object, "currentMode");
        if (!modes.containsKey(current)) {
            throw new IllegalArgumentException("Persisted mode '" + current + "' is not available.");
        }
        return new ModeState(current, nullableString(object, "previousMode"));
    }

    private static StateValue encodeState(ModeState state) {
        return StateValue.object(Map.of(
                "currentMode",
                StateValue.string(state.currentMode()),
                "previousMode",
                HarnessToolSupport.nullable(state.previousMode())));
    }

    private String availableModesText() {
        ArrayList<String> rendered = new ArrayList<>();
        options.modes().forEach(mode -> rendered.add("- " + mode.name() + ": " + mode.instructions()));
        return String.join("\n", rendered);
    }

    private static ToolMetadata metadata(String name, String description, StateValue.ObjectValue input) {
        return new ToolMetadata(
                name,
                description,
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                input,
                HarnessToolSupport.STRING_OUTPUT);
    }

    private static String requiredString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string.");
    }

    private static String nullableString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string or null.");
    }

    private static void requireActive(RunCancellation cancellation) {
        if (Objects.requireNonNull(cancellation, "cancellation").isCancellationRequested()) {
            throw new RunCancelledException();
        }
    }

    private record ModeState(String currentMode, String previousMode) {}

    private static final class Holder<T> {
        private T value;
    }
}
