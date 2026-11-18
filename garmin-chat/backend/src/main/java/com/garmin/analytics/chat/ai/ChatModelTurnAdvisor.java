package com.garmin.analytics.chat.ai;

import com.garmin.analytics.chat.ai.exception.ChatModelUnavailableException;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.ai.tool.GarminToolCallAdvisor;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.diagnostics.ChatDiagnostics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;

/** Applies model-only checks and diagnostics inside the tool advisor's loop. */
@Component
public class ChatModelTurnAdvisor implements CallAdvisor {

  private static final Logger log = LoggerFactory.getLogger(ChatModelTurnAdvisor.class);

  private final PromptBudget budget;
  private final GarminChatProperties properties;
  private final ChatDiagnostics diagnostics;

  public ChatModelTurnAdvisor(PromptBudget budget, GarminChatProperties properties,
      ChatDiagnostics diagnostics) {
    this.budget = budget;
    this.properties = properties;
    this.diagnostics = diagnostics;
  }

  @Override
  public String getName() {
    return "Garmin model turn";
  }

  @Override
  public int getOrder() {
    return GarminToolCallAdvisor.ORDER + 1;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    int turn = 1 + (int) request.prompt().getInstructions().stream()
        .filter(ToolResponseMessage.class::isInstance).count();
    if (turn > 1 && !budget.fits(request.prompt())) {
      log.info("chat_rejected reason=tool_result_context_budget");
      throw new InvalidAnalyticsRequest("The complete question and Garmin evidence exceed the "
          + "model context. Please shorten your question or choose fewer metrics.");
    }
    long started = System.nanoTime();
    recordRequest(turn, request.prompt());
    try {
      var result = ModelTransport.call(() -> chain.nextCall(request));
      var response = result.chatResponse();
      if (response == null || response.getResult() == null) {
        throw new ChatModelUnavailableException(null);
      }
      recordResponse(turn, started, response);
      return result;
    } catch (RuntimeException failure) {
      log.warn("model_failed turn={} elapsed_ms={} error_type={}",
          turn, elapsedMillis(started), failure.getClass().getSimpleName());
      throw failure;
    }
  }

  private void recordRequest(int turn, Prompt prompt) {
    int characters = prompt.getInstructions().stream().map(Message::getText)
        .mapToInt(String::length).sum();
    log.info("model_started turn={} prompt_chars={} context_tokens={} response_tokens={} "
            + "prompt_sha256={}", turn, characters, properties.contextTokens(),
        properties.responseTokens(), fingerprint(prompt.getSystemMessage().getText()));
    if (diagnostics.enabled()) {
      var options = (ToolCallingChatOptions) prompt.getOptions();
      diagnostics.record("model_request", Map.of(
          "turn", turn, "messages", prompt.getInstructions(), "options", options,
          "tools", options.getToolCallbacks().stream().map(callback -> callback.getToolDefinition())
              .toList()));
    }
  }

  private void recordResponse(int turn, long started, ChatResponse response) {
    var usage = response.getMetadata().getUsage();
    boolean usageKnown = usage != null && !(usage instanceof EmptyUsage);
    log.info("model_completed turn={} elapsed_ms={} model={} prompt_tokens={} "
            + "completion_tokens={} total_tokens={} finish_reason={}",
        turn, elapsedMillis(started), response.getMetadata().getModel(),
        usageKnown ? usage.getPromptTokens() : null,
        usageKnown ? usage.getCompletionTokens() : null,
        usageKnown ? usage.getTotalTokens() : null,
        response.getResult().getMetadata().getFinishReason());
    if (diagnostics.enabled()) {
      diagnostics.record("model_response", Map.of("turn", turn, "messages",
          response.getResults().stream().map(generation -> generation.getOutput()).toList()));
    }
  }

  private static String fingerprint(String system) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(system.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}
