package com.garmin.analytics.chat.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.PromptBudget;
import com.garmin.analytics.chat.ai.QuestionDateResolver;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.DailySummary;
import com.garmin.analytics.chat.model.DataCoverage;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import com.garmin.analytics.chat.service.GarminChatService;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;

public final class ChatTestSupport {

  public static final String WEEK = """
      {"status":"OK","metrics":["totalSteps"],"aggregation":"SUM",
       "period":{"type":"EXPLICIT","startDate":"2026-11-09","endDate":"2026-11-15","days":0}}
      """;
  public static final String LAST_SEVEN = """
      {"type":"LAST_N_DAYS","startDate":"","endDate":"","days":7}
      """;
  public static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
  public static final GarminChatProperties PROPERTIES = new GarminChatProperties(
      123L, ZoneId.of("Europe/Oslo"), 8192, 1024, 31);

  private ChatTestSupport() {}

  public static QuestionDateResolver dates(Clock clock, GarminChatProperties properties)
      throws IOException {
    return new QuestionDateResolver(clock, properties, new ObjectMapper(),
        new ClassPathResource("prompts/date-selection-system.txt"),
        new ClassPathResource("prompts/query-intent-system.txt"),
        new ClassPathResource("prompts/period-selection-rules.txt"));
  }

  public static GarminChatService service(AggregationLoader repository, ChatModel model)
      throws IOException {
    return service(repository, model, () -> true);
  }

  public static GarminChatService service(
      AggregationLoader repository, ChatModel model, ChatModelAvailability availability)
      throws IOException {
    return new GarminChatService(repository, model, availability, dates(CLOCK, PROPERTIES),
        new PromptBudget(PROPERTIES), PROPERTIES, new ObjectMapper().findAndRegisterModules(),
        new ClassPathResource("prompts/garmin-evidence-system.txt"));
  }

  public static ChatModel model(String selection, Function<Prompt, ChatResponse> answer) {
    return prompt -> isSelection(prompt) ? response(selection) : answer.apply(prompt);
  }

  public static boolean isSelection(Prompt prompt) {
    return prompt.getOptions() instanceof OllamaChatOptions options && options.getFormat() != null;
  }

  public static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  public static String intent(String metrics, String aggregation, String period) {
    return "{\"status\":\"OK\",\"metrics\":" + metrics + ",\"aggregation\":\"" + aggregation
        + "\",\"period\":" + period + "}";
  }

  public static String rejected(String status) {
    return "{\"status\":\"" + status + "\",\"metrics\":[],\"aggregation\":\"NONE\","
        + "\"period\":{\"type\":\"CLARIFICATION\",\"days\":0,\"startDate\":\"\",\"endDate\":\"\"}}";
  }

  public static MetricAggregation result(QueryIntent intent, String value) {
    var coverage = value == null ? new DataCoverage(7, 7, 0, 0, 0, 0, 0)
        : new DataCoverage(7, 0, 0, 0, 0, 7, new BigDecimal(value).signum() == 0 ? 7 : 0);
    var metric = intent.metrics().getFirst();
    return new MetricAggregation(metric, intent.aggregation(),
        value == null ? null : new BigDecimal(value), metric.unit(), intent.range(), coverage);
  }

  /** No service test may fall back to loading raw daily summaries. */
  @FunctionalInterface
  public interface AggregationLoader extends DailySummaryRepository {

    @Override
    default List<DailySummary> findSummaries(long profile, LocalDate start, LocalDate end) {
      throw new AssertionError("Chat must use aggregation, never raw daily summaries");
    }
  }
}
