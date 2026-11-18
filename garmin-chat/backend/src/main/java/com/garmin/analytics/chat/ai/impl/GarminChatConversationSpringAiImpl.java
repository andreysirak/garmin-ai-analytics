package com.garmin.analytics.chat.ai.impl;

import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.ai.GarminChatConversation;
import com.garmin.analytics.chat.ai.exception.ChatModelUnavailableException;
import com.garmin.analytics.chat.ai.prompt.GarminChatPromptFactory;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.ai.tool.GarminAnalyticsToolFactory;
import com.garmin.analytics.chat.ai.tool.GarminToolArguments;
import com.garmin.analytics.chat.ai.tool.GarminToolCallAdvisor;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.ChatAnswer;
import com.garmin.analytics.chat.model.QueryEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

@Component
public class GarminChatConversationSpringAiImpl implements GarminChatConversation {

  private static final Logger log = LoggerFactory.getLogger(GarminChatConversationSpringAiImpl.class);

  private final ChatClient client;
  private final ChatModelAvailability modelAvailability;
  private final GarminChatPromptFactory prompts;
  private final GarminAnalyticsToolFactory tools;
  private final PromptBudget budget;
  private final GarminToolArguments arguments;
  private final ChatModelTurnAdvisor modelTurn;

  public GarminChatConversationSpringAiImpl(ChatClient.Builder client, ChatModelAvailability modelAvailability,
      GarminChatPromptFactory prompts, GarminAnalyticsToolFactory tools, PromptBudget budget,
      GarminToolArguments arguments, ChatModelTurnAdvisor modelTurn) {
    this.client = client.build();
    this.modelAvailability = modelAvailability;
    this.prompts = prompts;
    this.tools = tools;
    this.budget = budget;
    this.arguments = arguments;
    this.modelTurn = modelTurn;
  }

  @Override
  public ChatAnswer answer(String question, ChatDeadline deadline) {
    var tool = tools.create(deadline);
    var prompt = prompts.create(question, tool.referenceDate(), ToolCallbacks.from(tool));
    if (!budget.fits(prompt)) {
      log.info("chat_rejected reason=initial_context_budget");
      return new ChatAnswer("Your question is too long for the model context. "
          + "Please shorten it and specify a metric, calculation and clear period.", null, null);
    }
    deadline.check();
    modelAvailability.requireAvailable();

    var toolAdvisor = new GarminToolCallAdvisor(arguments, deadline);
    String answer;
    try {
      answer = client.prompt(prompt).advisors(toolAdvisor, modelTurn).call().content();
      if (answer == null || answer.isBlank()) {
        throw new ChatModelUnavailableException(null);
      }
    } catch (InvalidAnalyticsRequest rejected) {
      answer = rejected.getMessage();
    }
    return response(answer, tool.evidence());
  }

  private static ChatAnswer response(String answer, QueryEvidence evidence) {
    return evidence == null ? new ChatAnswer(answer, null, null)
        : new ChatAnswer(answer, evidence.dateRange().startDate(), evidence.dateRange().endDate());
  }
}
