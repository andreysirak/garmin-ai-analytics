package com.garmin.analytics.chat.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.model.QueryIntentResolution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class QuestionDateResolver {

  private final Clock clock;
  private final GarminChatProperties properties;
  private final String systemPrompt;
  private final String intentSystemPrompt;
  private final ObjectMapper json;
  private final Map<String, Object> schema;
  private final Map<String, Object> intentSchema;

  public QuestionDateResolver(
      Clock clock, GarminChatProperties properties, ObjectMapper json,
      @Value("classpath:prompts/date-selection-system.txt") Resource systemPrompt,
      @Value("classpath:prompts/query-intent-system.txt") Resource intentSystemPrompt,
      @Value("classpath:prompts/period-selection-rules.txt") Resource periodRules)
      throws IOException {
    this.clock = clock;
    this.properties = properties;
    this.json = json.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    String rules = periodRules.getContentAsString(StandardCharsets.UTF_8);
    this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8) + "\n" + rules;
    this.intentSystemPrompt = intentSystemPrompt.getContentAsString(StandardCharsets.UTF_8)
        + "\n" + rules;
    this.schema = selectionSchema();
    this.intentSchema = intentSchema();
  }

  public Prompt prompt(String question, LocalDate referenceDate) {
    String reference = "Today is " + referenceDate + " in " + properties.timezone()
        + ". Extract the date selection from the following question.\n";
    return new Prompt(List.of(new SystemMessage(systemPrompt + "\n" + reference),
        new UserMessage(question)), OllamaChatOptions.builder()
        .format(schema)
        .numPredict(Math.min(256, properties.responseTokens())).temperature(0.0).build());
  }

  public Optional<DateRange> resolve(String structuredSelection, LocalDate referenceDate) {
    try {
      var node = json.readTree(structuredSelection);
      if (!validPeriodShape(node)) {
        return Optional.empty();
      }
      return resolvePeriod(json.treeToValue(node, DateSelection.class), referenceDate)
          .filter(this::withinRangeLimit);
    } catch (JsonProcessingException | IllegalArgumentException invalidSelection) {
      return Optional.empty();
    }
  }

  /** Interpret only the question; Garmin data is never sent to the interpretation model. */
  public Prompt intentPrompt(String question, LocalDate referenceDate) {
    var catalog = new StringBuilder("\nSupported metric catalog:\n");
    for (var metric : QueryIntent.Metric.values()) {
      catalog.append(metric.field()).append(": ")
          .append(Arrays.stream(QueryIntent.Aggregation.values()).filter(metric::supports).toList())
          .append("; evidence unit ").append(metric.unit()).append(".\n");
    }
    catalog.append("Today is ").append(referenceDate).append(" in ")
        .append(properties.timezone()).append(". The maximum inclusive query period is ")
        .append(properties.maxQueryDays()).append(" calendar days. ")
        .append("Never shorten or change the requested period to fit this limit.\n");
    return new Prompt(List.of(new SystemMessage(intentSystemPrompt + catalog),
        new UserMessage(question)), OllamaChatOptions.builder().format(intentSchema)
        .numPredict(Math.min(384, properties.responseTokens())).temperature(0.0).build());
  }

  public QueryIntentResolution resolveIntent(String structuredIntent, LocalDate referenceDate) {
    try {
      var node = json.readTree(structuredIntent);
      if (!validIntentShape(node)) {
        return invalidIntent();
      }
      var selection = json.treeToValue(node, IntentSelection.class);
      if (selection.status() != IntentStatus.OK) {
        if (!selection.metrics().isEmpty() || !selection.aggregation().equals("NONE")
            || !clarificationPeriod(selection.period())) {
          return invalidIntent();
        }
        return selection.status() == IntentStatus.CLARIFICATION
            ? new QueryIntentResolution(null,
                "Please specify a measurement, how to summarize it, and a clear period.")
            : unsupportedAnalysis(List.of());
      }
      if (selection.metrics().isEmpty()
          || selection.metrics().size() > QueryIntent.Metric.values().length
          || selection.metrics().stream().distinct().count() != selection.metrics().size()) {
        return invalidIntent();
      }
      var metrics = new ArrayList<QueryIntent.Metric>();
      for (String field : selection.metrics()) {
        var metric = QueryIntent.Metric.fromField(field);
        if (metric.isEmpty()) {
          return new QueryIntentResolution(null,
              "Unsupported metric. Supported fields: " + String.join(", ",
                  Arrays.stream(QueryIntent.Metric.values())
                      .map(QueryIntent.Metric::field).toList())
                  + ".");
        }
        metrics.add(metric.get());
      }
      QueryIntent.Aggregation aggregation;
      try {
        aggregation = QueryIntent.Aggregation.valueOf(selection.aggregation());
      } catch (IllegalArgumentException unsupported) {
        return unsupportedAnalysis(metrics);
      }
      var incompatible = metrics.stream()
          .filter(metric -> !metric.supports(aggregation)).findFirst();
      if (incompatible.isPresent()) {
        return new QueryIntentResolution(null,
            "That calculation is not supported for " + metricLabel(incompatible.get()) + ". "
                + supportedExample(incompatible.get()));
      }
      var range = resolvePeriod(selection.period(), referenceDate);
      if (range.isEmpty()) {
        return new QueryIntentResolution(null,
            "Please provide valid, ordered dates no later than today, or a clear relative period.");
      }
      if (!withinRangeLimit(range.get())) {
        return new QueryIntentResolution(null, "Please choose a period of at most "
            + properties.maxQueryDays() + " calendar days (inclusive).");
      }
      return new QueryIntentResolution(new QueryIntent(metrics, aggregation, range.get()), "");
    } catch (JsonProcessingException | IllegalArgumentException invalidSelection) {
      return invalidIntent();
    }
  }

  private Optional<DateRange> resolvePeriod(DateSelection selection, LocalDate referenceDate) {
    try {
      if (selection == null || selection.type() == null || selection.days() == null
          || selection.startDate() == null || selection.endDate() == null) {
        return Optional.empty();
      }
      var today = referenceDate;
      DateRange range = switch (selection.type()) {
        case EXPLICIT -> {
          if (selection.days() != 0 || !selection.startDate().matches("\\d{4}-\\d{2}-\\d{2}")
              || !selection.endDate().matches("\\d{4}-\\d{2}-\\d{2}")) {
            yield null;
          }
          yield new DateRange(LocalDate.parse(selection.startDate()),
              LocalDate.parse(selection.endDate()));
        }
        case LAST_N_DAYS -> {
          if (selection.days() <= 0
              || !selection.startDate().isEmpty() || !selection.endDate().isEmpty()) {
            yield null;
          }
          yield new DateRange(today.minusDays(selection.days()), today.minusDays(1));
        }
        case LAST_WEEK -> {
          var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          yield relativeRange(selection, monday.minusWeeks(1), monday.minusDays(1));
        }
        case THIS_WEEK -> relativeRange(selection,
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
        case LAST_MONTH -> relativeRange(selection,
            today.withDayOfMonth(1).minusMonths(1), today.withDayOfMonth(1).minusDays(1));
        case THIS_MONTH -> relativeRange(selection, today.withDayOfMonth(1), today);
        case YESTERDAY -> relativeRange(selection, today.minusDays(1), today.minusDays(1));
        case TODAY -> relativeRange(selection, today, today);
        case CLARIFICATION -> null;
      };
      if (range == null || range.startDate().getYear() < 1
          || range.endDate().isBefore(range.startDate()) || range.endDate().isAfter(today)) {
        return Optional.empty();
      }
      return Optional.of(range);
    } catch (DateTimeException | ArithmeticException invalidSelection) {
      // Model-generated dates are untrusted. Never query with malformed/ambiguous values.
      return Optional.empty();
    }
  }

  private boolean withinRangeLimit(DateRange range) {
    return ChronoUnit.DAYS.between(range.startDate(), range.endDate()) + 1
        <= properties.maxQueryDays();
  }

  private boolean hasFields(JsonNode node, String... fields) {
    return node != null && node.isObject() && node.size() == fields.length
        && Arrays.stream(fields).allMatch(node::has);
  }

  private boolean validPeriodShape(JsonNode node) {
    return hasFields(node, "type", "days", "startDate", "endDate")
        && node.get("type").isTextual() && node.get("startDate").isTextual()
        && node.get("endDate").isTextual() && node.get("days").isIntegralNumber()
        && node.get("days").canConvertToInt()
        && Arrays.stream(SelectionType.values())
            .anyMatch(type -> type.name().equals(node.get("type").textValue()));
  }

  private boolean validIntentShape(JsonNode node) {
    if (!hasFields(node, "status", "metrics", "aggregation", "period")
        || !node.get("status").isTextual() || !node.get("metrics").isArray()
        || !node.get("aggregation").isTextual() || !validPeriodShape(node.get("period"))) {
      return false;
    }
    if (Arrays.stream(IntentStatus.values())
        .noneMatch(status -> status.name().equals(node.get("status").textValue()))) {
      return false;
    }
    for (var metric : node.get("metrics")) {
      if (!metric.isTextual()) {
        return false;
      }
    }
    return true;
  }

  private boolean clarificationPeriod(DateSelection period) {
    return period.type() == SelectionType.CLARIFICATION && period.days() == 0
        && period.startDate().isEmpty() && period.endDate().isEmpty();
  }

  private QueryIntentResolution invalidIntent() {
    return new QueryIntentResolution(null,
        "The question could not be interpreted safely. Please ask for a supported metric, "
            + "aggregation and clear period.");
  }

  private QueryIntentResolution unsupportedAnalysis(List<QueryIntent.Metric> metrics) {
    return new QueryIntentResolution(null,
        "This demo supports summaries for one period. Correlations, trends, period comparisons "
            + "and causal or medical conclusions are not supported. "
            + supportedExample(metrics.isEmpty() ? QueryIntent.Metric.TOTAL_STEPS
                : metrics.getFirst()));
  }

  private String supportedExample(QueryIntent.Metric metric) {
    String period = properties.maxQueryDays() >= 7 ? "over the last seven days" : "yesterday";
    return "Try: \"What was my average " + metricLabel(metric) + " " + period + "?\"";
  }

  private String metricLabel(QueryIntent.Metric metric) {
    return switch (metric) {
      case TOTAL_STEPS -> "daily step count";
      case RESTING_HEART_RATE -> "resting heart rate";
      case ACTIVE_KILOCALORIES -> "active calories";
      case SLEEPING_SECONDS -> "recorded sleep duration";
    };
  }

  private DateRange relativeRange(DateSelection selection, LocalDate start, LocalDate end) {
    return selection.startDate().isEmpty() && selection.endDate().isEmpty() && selection.days() == 0
        ? new DateRange(start, end) : null;
  }

  public LocalDate referenceDate() {
    return LocalDate.now(clock.withZone(properties.timezone()));
  }

  private Map<String, Object> selectionSchema() {
    var generated = new LinkedHashMap<>(
        new BeanOutputConverter<>(DateSelection.class).getJsonSchemaMap());
    var fields = (Map<?, ?>) generated.get("properties");
    var ordered = new LinkedHashMap<String, Object>();
    var fieldOrder = List.of("type", "days", "startDate", "endDate");
    // Choose the period type before generating its dependent fields.
    for (var field : fieldOrder) {
      ordered.put(field, fields.get(field));
    }
    generated.put("properties", ordered);
    generated.put("required", fieldOrder);
    return generated;
  }

  private Map<String, Object> intentSchema() {
    var generated = new LinkedHashMap<>(
        new BeanOutputConverter<>(IntentSelection.class).getJsonSchemaMap());
    var fields = (Map<?, ?>) generated.get("properties");
    var ordered = new LinkedHashMap<String, Object>();
    ordered.put("status", fields.get("status"));
    ordered.put("metrics", Map.of("type", "array", "minItems", 0,
        "maxItems", QueryIntent.Metric.values().length, "uniqueItems", true,
        "items", Map.of("type", "string", "enum",
            Arrays.stream(QueryIntent.Metric.values()).map(QueryIntent.Metric::field).toList())));
    var aggregations = new ArrayList<String>();
    for (var aggregation : QueryIntent.Aggregation.values()) {
      aggregations.add(aggregation.name());
    }
    aggregations.add("NONE");
    ordered.put("aggregation", Map.of("type", "string", "enum", aggregations));
    var period = new LinkedHashMap<>(schema);
    period.remove("$schema");
    ordered.put("period", period);
    generated.put("properties", ordered);
    generated.put("required", List.of("status", "metrics", "aggregation", "period"));
    return generated;
  }

  public record IntentSelection(
      IntentStatus status, List<String> metrics, String aggregation, DateSelection period) {}

  public enum IntentStatus {
    OK, CLARIFICATION, UNSUPPORTED
  }

  public record DateSelection(SelectionType type, String startDate, String endDate, Integer days) {}

  public enum SelectionType {
    EXPLICIT, LAST_N_DAYS, LAST_WEEK, THIS_WEEK, LAST_MONTH, THIS_MONTH, YESTERDAY, TODAY,
    CLARIFICATION
  }
}
