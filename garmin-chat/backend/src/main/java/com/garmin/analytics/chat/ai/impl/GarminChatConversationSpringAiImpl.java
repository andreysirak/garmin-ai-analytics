package com.garmin.analytics.chat.ai.impl;

import com.garmin.analytics.chat.ai.ChatCompletion;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.GarminChatConversation;
import com.garmin.analytics.chat.ai.prompt.GarminChatPromptFactory;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.ai.routing.GarminRequestRouter;
import com.garmin.analytics.chat.ai.routing.RoutingDecision;
import com.garmin.analytics.chat.ai.tool.GarminAnalysisWorkflow;
import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.ChatAnswer;
import org.springframework.stereotype.Component;

@Component
public class GarminChatConversationSpringAiImpl implements GarminChatConversation {

  private final ChatModelAvailability modelAvailability;
  private final GarminChatPromptFactory prompts;
  private final GarminAnalyticsService analytics;
  private final PromptBudget budget;
  private final GarminRequestRouter router;
  private final GarminAnalysisWorkflow workflow;
  private final ChatCompletion completion;

  public GarminChatConversationSpringAiImpl(ChatModelAvailability modelAvailability,
      GarminChatPromptFactory prompts, GarminAnalyticsService analytics, PromptBudget budget,
      GarminRequestRouter router, GarminAnalysisWorkflow workflow, ChatCompletion completion) {
    this.modelAvailability = modelAvailability;
    this.prompts = prompts;
    this.analytics = analytics;
    this.budget = budget;
    this.router = router;
    this.workflow = workflow;
    this.completion = completion;
  }

  @Override
  public ChatAnswer answer(String question, ChatDeadline deadline) {
    var referenceDate = analytics.referenceDate();
    var prompt = prompts.routing(question, referenceDate);
    if (!budget.fits(prompt)) {
      return completion.contextLimit();
    }
    RoutingDecision decision;
    try {
      deadline.check();
      modelAvailability.requireAvailable();
      decision = router.route(prompt, referenceDate, deadline);
    } catch (InvalidAnalyticsRequest rejected) {
      return completion.failed(rejected.getMessage(), null);
    } catch (RuntimeException failure) {
      completion.executionFailed(false);
      throw failure;
    }
    return switch (decision) {
      case RoutingDecision.Clarify clarify -> completion.clarify(clarify.question());
      case RoutingDecision.Unsupported unsupported -> completion.unsupported(unsupported.reason());
      case RoutingDecision.Analyze analyze ->
          workflow.analyze(question, referenceDate, analyze.selection(), deadline);
    };
  }
}
