// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

final class GenAiAttributes {
    static final String OPERATION_NAME = "gen_ai.operation.name";

    static final String PROVIDER_NAME = "gen_ai.provider.name";

    static final String REQUEST_MODEL = "gen_ai.request.model";

    static final String RESPONSE_MODEL = "gen_ai.response.model";

    static final String RESPONSE_ID = "gen_ai.response.id";

    static final String RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";

    static final String CONVERSATION_ID = "gen_ai.conversation.id";

    static final String INPUT_MESSAGES = "gen_ai.input.messages";

    static final String OUTPUT_MESSAGES = "gen_ai.output.messages";

    static final String OUTPUT_MESSAGES_TRUNCATED = "agent_framework.gen_ai.output.messages.truncated";

    static final String AGENT_ID = "gen_ai.agent.id";

    static final String AGENT_NAME = "gen_ai.agent.name";

    static final String AGENT_DESCRIPTION = "gen_ai.agent.description";

    static final String WORKFLOW_NAME = "gen_ai.workflow.name";

    static final String TOOL_NAME = "gen_ai.tool.name";

    static final String TOOL_TYPE = "gen_ai.tool.type";

    static final String TOOL_CALL_ID = "gen_ai.tool.call.id";

    static final String TOOL_CALL_ARGUMENTS = "gen_ai.tool.call.arguments";

    static final String TOOL_CALL_RESULT = "gen_ai.tool.call.result";

    static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";

    static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

    static final String USAGE_CACHE_CREATION_INPUT_TOKENS = "gen_ai.usage.cache_creation.input_tokens";

    static final String USAGE_CACHE_READ_INPUT_TOKENS = "gen_ai.usage.cache_read.input_tokens";

    static final String USAGE_REASONING_OUTPUT_TOKENS = "gen_ai.usage.reasoning.output_tokens";

    static final String TOKEN_TYPE = "gen_ai.token.type";

    static final String ERROR_TYPE = "error.type";

    static final String OUTCOME = "agent_framework.operation.outcome";

    static final String RUN_ID = "agent_framework.run.id";

    static final String INVOCATION_ID = "agent_framework.tool.invocation.id";

    private GenAiAttributes() {}
}
