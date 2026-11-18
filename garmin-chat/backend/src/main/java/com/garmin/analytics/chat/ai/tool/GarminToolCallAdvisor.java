package com.garmin.analytics.chat.ai.tool;

import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.dao.DataAccessException;

/** Request-scoped adapter around Spring AI's synchronous tool loop. */
public final class GarminToolCallAdvisor extends ToolCallAdvisor {

  public static final int ORDER = BaseAdvisor.HIGHEST_PRECEDENCE + 300;
  private static final Logger log = LoggerFactory.getLogger(GarminToolCallAdvisor.class);
  private static final int MAX_TOOL_CALLS = 1;
  private static final ToolCallingManager TOOL_MANAGER = ToolCallingManager.builder()
      .toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder()
          .alwaysThrow(true)
          .rethrowExceptions(List.of(DataAccessException.class, InvalidAnalyticsRequest.class,
              ChatRequestTimeoutException.class, ChatRequestInterruptedException.class))
          .build())
      .build();

  private final GarminToolArguments arguments;
  private final ChatDeadline deadline;
  private int toolCalls;
  private Long toolStarted;

  public GarminToolCallAdvisor(GarminToolArguments arguments, ChatDeadline deadline) {
    super(TOOL_MANAGER, ORDER);
    this.arguments = arguments;
    this.deadline = deadline;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    try {
      return super.adviseCall(request, chain);
    } catch (RuntimeException failure) {
      if (toolStarted != null) {
        log.info("tool_execution tool={} outcome={} elapsed_ms={} error_type={}",
            GarminAnalyticsTool.NAME,
            failure instanceof InvalidAnalyticsRequest ? "rejected" : "failed",
            elapsedMillis(), failure.getClass().getSimpleName());
      }
      throw failure;
    }
  }

  @Override
  protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
    deadline.check();
    return request;
  }

  @Override
  protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
    deadline.check();
    if (response.chatResponse().hasToolCalls()) {
      toolStarted = System.nanoTime();
      var calls = response.chatResponse().getResults().stream()
          .flatMap(generation -> generation.getOutput().getToolCalls().stream()).toList();
      // Spring AI 1.1.8 dispatches the full batch after this hook.
      if (calls.size() > MAX_TOOL_CALLS - toolCalls) {
        throw new InvalidAnalyticsRequest("Please ask for one calculation over one period.");
      }
      var call = calls.getFirst();
      if (!GarminAnalyticsTool.NAME.equals(call.name()) || !arguments.valid(call.arguments())) {
        throw new InvalidAnalyticsRequest(
            "Please specify a supported Garmin metric, calculation and clear period.");
      }
      toolCalls += calls.size();
      deadline.check();
    }
    return response;
  }

  @Override
  protected List<Message> doGetNextInstructionsForToolCall(ChatClientRequest request,
      ChatClientResponse response, ToolExecutionResult result) {
    log.info("tool_execution tool={} outcome=completed elapsed_ms={}",
        GarminAnalyticsTool.NAME, elapsedMillis());
    toolStarted = null;
    return super.doGetNextInstructionsForToolCall(request, response, result);
  }

  private long elapsedMillis() {
    return (System.nanoTime() - toolStarted) / 1_000_000;
  }
}
