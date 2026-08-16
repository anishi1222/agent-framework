// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMiddleware;
import com.microsoft.agents.agents.ChatMiddleware;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.FunctionMiddleware;
import com.microsoft.agents.agents.HistoryProvider;
import com.microsoft.agents.agents.InMemoryHistoryProvider;
import com.microsoft.agents.agents.SessionStore;
import com.microsoft.agents.agents.context.CompactionStrategy;
import com.microsoft.agents.agents.skills.SkillsProvider;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.harness.files.AgentFileStore;
import com.microsoft.agents.tools.Tool;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Configures provider assembly, instructions, tools, storage, and autonomous looping. */
public final class HarnessAgentOptions {
    private final String id;

    private final String name;

    private final String description;

    private final String harnessInstructions;

    private final String agentInstructions;

    private final ChatOptions chatOptions;

    private final List<Tool> tools;

    private final HistoryProvider historyProvider;

    private final boolean disableCompaction;

    private final CompactionStrategy compactionStrategy;

    private final Long maxContextWindowTokens;

    private final Long maxOutputTokens;

    private final boolean disableTodo;

    private final TodoProvider todoProvider;

    private final boolean disableMode;

    private final AgentModeProvider modeProvider;

    private final boolean disableFileMemory;

    private final FileMemoryProvider fileMemoryProvider;

    private final AgentFileStore fileMemoryStore;

    private final AgentFileStore fileAccessStore;

    private final FileAccessProviderOptions fileAccessOptions;

    private final SkillsProvider skillsProvider;

    private final List<Path> skillPaths;

    private final BackgroundAgentsProvider backgroundAgentsProvider;

    private final List<Agent<?>> backgroundAgents;

    private final BackgroundAgentsProviderOptions backgroundAgentsOptions;

    private final List<ContextProvider> contextProviders;

    private final List<AgentMiddleware<Void>> agentMiddleware;

    private final List<ChatMiddleware> chatMiddleware;

    private final List<FunctionMiddleware> functionMiddleware;

    private final SessionStore sessionStore;

    private final List<LoopEvaluator> loopEvaluators;

    private final LoopAgentOptions loopOptions;

    private final boolean loopOnTodos;

    private final Set<String> todoLoopingModes;

    private final boolean loopOnBackgroundTasks;

    private HarnessAgentOptions(Builder builder) {
        id = optionalNonBlank(builder.id, "id");
        name = optionalNonBlank(builder.name, "name");
        description = optionalNonBlank(builder.description, "description");
        harnessInstructions = builder.harnessInstructions;
        agentInstructions = builder.agentInstructions;
        chatOptions = Objects.requireNonNull(builder.chatOptions, "chatOptions");
        tools = copy(builder.tools, "tools");
        historyProvider = Objects.requireNonNull(builder.historyProvider, "historyProvider");
        disableCompaction = builder.disableCompaction;
        compactionStrategy = builder.compactionStrategy;
        maxContextWindowTokens = positive(builder.maxContextWindowTokens, "maxContextWindowTokens");
        maxOutputTokens = positive(builder.maxOutputTokens, "maxOutputTokens");
        if ((maxContextWindowTokens == null) != (maxOutputTokens == null)
                && compactionStrategy == null
                && !disableCompaction) {
            throw new IllegalArgumentException(
                    "Default compaction requires maxContextWindowTokens and maxOutputTokens together.");
        }
        if (maxContextWindowTokens != null && maxOutputTokens != null && maxOutputTokens >= maxContextWindowTokens) {
            throw new IllegalArgumentException("maxOutputTokens must be less than maxContextWindowTokens.");
        }
        disableTodo = builder.disableTodo;
        todoProvider = builder.todoProvider;
        disableMode = builder.disableMode;
        modeProvider = builder.modeProvider;
        disableFileMemory = builder.disableFileMemory;
        fileMemoryProvider = builder.fileMemoryProvider;
        fileMemoryStore = builder.fileMemoryStore;
        if (fileMemoryProvider != null && fileMemoryStore != null) {
            throw new IllegalArgumentException("fileMemoryProvider and fileMemoryStore are mutually exclusive.");
        }
        fileAccessStore = builder.fileAccessStore;
        fileAccessOptions = Objects.requireNonNull(builder.fileAccessOptions, "fileAccessOptions");
        skillsProvider = builder.skillsProvider;
        skillPaths = List.copyOf(builder.skillPaths);
        backgroundAgentsProvider = builder.backgroundAgentsProvider;
        backgroundAgents = copy(builder.backgroundAgents, "backgroundAgents");
        if (backgroundAgentsProvider != null && !backgroundAgents.isEmpty()) {
            throw new IllegalArgumentException("backgroundAgentsProvider and backgroundAgents are mutually exclusive.");
        }
        backgroundAgentsOptions = Objects.requireNonNull(builder.backgroundAgentsOptions, "backgroundAgentsOptions");
        contextProviders = copy(builder.contextProviders, "contextProviders");
        agentMiddleware = copy(builder.agentMiddleware, "agentMiddleware");
        chatMiddleware = copy(builder.chatMiddleware, "chatMiddleware");
        functionMiddleware = copy(builder.functionMiddleware, "functionMiddleware");
        sessionStore = builder.sessionStore;
        loopEvaluators = copy(builder.loopEvaluators, "loopEvaluators");
        loopOptions = Objects.requireNonNull(builder.loopOptions, "loopOptions");
        loopOnTodos = builder.loopOnTodos;
        todoLoopingModes = builder.todoLoopingModes == null ? null : Set.copyOf(builder.todoLoopingModes);
        loopOnBackgroundTasks = builder.loopOnBackgroundTasks;
        if (loopOnTodos && disableTodo) {
            throw new IllegalArgumentException("loopOnTodos requires TodoProvider.");
        }
        if (todoLoopingModes != null && disableMode) {
            throw new IllegalArgumentException("Mode-gated todo looping requires AgentModeProvider.");
        }
        if (loopOnBackgroundTasks && backgroundAgentsProvider == null && backgroundAgents.isEmpty()) {
            throw new IllegalArgumentException("loopOnBackgroundTasks requires background agents.");
        }
    }

    /** Returns default harness options. */
    public static HarnessAgentOptions defaults() {
        return builder().build();
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String harnessInstructions() {
        return harnessInstructions;
    }

    public String agentInstructions() {
        return agentInstructions;
    }

    public ChatOptions chatOptions() {
        return chatOptions;
    }

    public List<Tool> tools() {
        return tools;
    }

    public HistoryProvider historyProvider() {
        return historyProvider;
    }

    public boolean disableCompaction() {
        return disableCompaction;
    }

    public CompactionStrategy compactionStrategy() {
        return compactionStrategy;
    }

    public Long maxContextWindowTokens() {
        return maxContextWindowTokens;
    }

    public Long maxOutputTokens() {
        return maxOutputTokens;
    }

    public boolean disableTodo() {
        return disableTodo;
    }

    public TodoProvider todoProvider() {
        return todoProvider;
    }

    public boolean disableMode() {
        return disableMode;
    }

    public AgentModeProvider modeProvider() {
        return modeProvider;
    }

    public boolean disableFileMemory() {
        return disableFileMemory;
    }

    public FileMemoryProvider fileMemoryProvider() {
        return fileMemoryProvider;
    }

    public AgentFileStore fileMemoryStore() {
        return fileMemoryStore;
    }

    public AgentFileStore fileAccessStore() {
        return fileAccessStore;
    }

    public FileAccessProviderOptions fileAccessOptions() {
        return fileAccessOptions;
    }

    public SkillsProvider skillsProvider() {
        return skillsProvider;
    }

    public List<Path> skillPaths() {
        return skillPaths;
    }

    public BackgroundAgentsProvider backgroundAgentsProvider() {
        return backgroundAgentsProvider;
    }

    public List<Agent<?>> backgroundAgents() {
        return backgroundAgents;
    }

    public BackgroundAgentsProviderOptions backgroundAgentsOptions() {
        return backgroundAgentsOptions;
    }

    public List<ContextProvider> contextProviders() {
        return contextProviders;
    }

    public List<AgentMiddleware<Void>> agentMiddleware() {
        return agentMiddleware;
    }

    public List<ChatMiddleware> chatMiddleware() {
        return chatMiddleware;
    }

    public List<FunctionMiddleware> functionMiddleware() {
        return functionMiddleware;
    }

    public SessionStore sessionStore() {
        return sessionStore;
    }

    public List<LoopEvaluator> loopEvaluators() {
        return loopEvaluators;
    }

    public LoopAgentOptions loopOptions() {
        return loopOptions;
    }

    public boolean loopOnTodos() {
        return loopOnTodos;
    }

    public Set<String> todoLoopingModes() {
        return todoLoopingModes;
    }

    public boolean loopOnBackgroundTasks() {
        return loopOnBackgroundTasks;
    }

    /** Builds immutable harness options. */
    public static final class Builder {
        private String id;

        private String name;

        private String description;

        private String harnessInstructions = HarnessAgent.DEFAULT_INSTRUCTIONS;

        private String agentInstructions;

        private ChatOptions chatOptions = ChatOptions.empty();

        private List<Tool> tools = List.of();

        private HistoryProvider historyProvider = new InMemoryHistoryProvider();

        private boolean disableCompaction;

        private CompactionStrategy compactionStrategy;

        private Long maxContextWindowTokens;

        private Long maxOutputTokens;

        private boolean disableTodo;

        private TodoProvider todoProvider;

        private boolean disableMode;

        private AgentModeProvider modeProvider;

        private boolean disableFileMemory;

        private FileMemoryProvider fileMemoryProvider;

        private AgentFileStore fileMemoryStore;

        private AgentFileStore fileAccessStore;

        private FileAccessProviderOptions fileAccessOptions = FileAccessProviderOptions.defaults();

        private SkillsProvider skillsProvider;

        private List<Path> skillPaths = List.of();

        private BackgroundAgentsProvider backgroundAgentsProvider;

        private List<Agent<?>> backgroundAgents = List.of();

        private BackgroundAgentsProviderOptions backgroundAgentsOptions = BackgroundAgentsProviderOptions.defaults();

        private List<ContextProvider> contextProviders = List.of();

        private List<AgentMiddleware<Void>> agentMiddleware = List.of();

        private List<ChatMiddleware> chatMiddleware = List.of();

        private List<FunctionMiddleware> functionMiddleware = List.of();

        private SessionStore sessionStore;

        private List<LoopEvaluator> loopEvaluators = List.of();

        private LoopAgentOptions loopOptions = LoopAgentOptions.defaults();

        private boolean loopOnTodos;

        private Set<String> todoLoopingModes;

        private boolean loopOnBackgroundTasks;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder harnessInstructions(String harnessInstructions) {
            this.harnessInstructions = harnessInstructions;
            return this;
        }

        public Builder agentInstructions(String agentInstructions) {
            this.agentInstructions = agentInstructions;
            return this;
        }

        public Builder chatOptions(ChatOptions chatOptions) {
            this.chatOptions = chatOptions;
            return this;
        }

        public Builder tools(List<? extends Tool> tools) {
            this.tools = List.copyOf(tools);
            return this;
        }

        public Builder historyProvider(HistoryProvider historyProvider) {
            this.historyProvider = historyProvider;
            return this;
        }

        public Builder disableCompaction(boolean disableCompaction) {
            this.disableCompaction = disableCompaction;
            return this;
        }

        public Builder compactionStrategy(CompactionStrategy compactionStrategy) {
            this.compactionStrategy = compactionStrategy;
            return this;
        }

        public Builder maxContextWindowTokens(long maxContextWindowTokens) {
            this.maxContextWindowTokens = maxContextWindowTokens;
            return this;
        }

        public Builder maxOutputTokens(long maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder disableTodo(boolean disableTodo) {
            this.disableTodo = disableTodo;
            return this;
        }

        public Builder todoProvider(TodoProvider todoProvider) {
            this.todoProvider = todoProvider;
            return this;
        }

        public Builder disableMode(boolean disableMode) {
            this.disableMode = disableMode;
            return this;
        }

        public Builder modeProvider(AgentModeProvider modeProvider) {
            this.modeProvider = modeProvider;
            return this;
        }

        public Builder disableFileMemory(boolean disableFileMemory) {
            this.disableFileMemory = disableFileMemory;
            return this;
        }

        public Builder fileMemoryProvider(FileMemoryProvider fileMemoryProvider) {
            this.fileMemoryProvider = fileMemoryProvider;
            return this;
        }

        public Builder fileMemoryStore(AgentFileStore fileMemoryStore) {
            this.fileMemoryStore = fileMemoryStore;
            return this;
        }

        public Builder fileAccessStore(AgentFileStore fileAccessStore) {
            this.fileAccessStore = fileAccessStore;
            return this;
        }

        public Builder fileAccessOptions(FileAccessProviderOptions fileAccessOptions) {
            this.fileAccessOptions = fileAccessOptions;
            return this;
        }

        public Builder skillsProvider(SkillsProvider skillsProvider) {
            this.skillsProvider = skillsProvider;
            return this;
        }

        public Builder skillPaths(List<Path> skillPaths) {
            this.skillPaths = List.copyOf(skillPaths);
            return this;
        }

        public Builder backgroundAgentsProvider(BackgroundAgentsProvider backgroundAgentsProvider) {
            this.backgroundAgentsProvider = backgroundAgentsProvider;
            return this;
        }

        public Builder backgroundAgents(List<? extends Agent<?>> backgroundAgents) {
            this.backgroundAgents = List.copyOf(backgroundAgents);
            return this;
        }

        public Builder backgroundAgentsOptions(BackgroundAgentsProviderOptions backgroundAgentsOptions) {
            this.backgroundAgentsOptions = backgroundAgentsOptions;
            return this;
        }

        public Builder contextProviders(List<? extends ContextProvider> contextProviders) {
            this.contextProviders = List.copyOf(contextProviders);
            return this;
        }

        public Builder agentMiddleware(List<? extends AgentMiddleware<Void>> agentMiddleware) {
            this.agentMiddleware = List.copyOf(agentMiddleware);
            return this;
        }

        public Builder chatMiddleware(List<? extends ChatMiddleware> chatMiddleware) {
            this.chatMiddleware = List.copyOf(chatMiddleware);
            return this;
        }

        public Builder functionMiddleware(List<? extends FunctionMiddleware> functionMiddleware) {
            this.functionMiddleware = List.copyOf(functionMiddleware);
            return this;
        }

        public Builder sessionStore(SessionStore sessionStore) {
            this.sessionStore = sessionStore;
            return this;
        }

        public Builder loopEvaluators(List<? extends LoopEvaluator> loopEvaluators) {
            this.loopEvaluators = List.copyOf(loopEvaluators);
            return this;
        }

        public Builder loopOptions(LoopAgentOptions loopOptions) {
            this.loopOptions = loopOptions;
            return this;
        }

        public Builder loopOnTodos(Set<String> loopingModes) {
            loopOnTodos = true;
            todoLoopingModes = loopingModes == null ? null : Set.copyOf(loopingModes);
            return this;
        }

        public Builder loopOnBackgroundTasks(boolean loopOnBackgroundTasks) {
            this.loopOnBackgroundTasks = loopOnBackgroundTasks;
            return this;
        }

        /** Creates immutable options. */
        public HarnessAgentOptions build() {
            return new HarnessAgentOptions(this);
        }
    }

    private static <T> List<T> copy(List<? extends T> values, String name) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " contains null");
        }
        return copy;
    }

    private static Long positive(Long value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }
}
