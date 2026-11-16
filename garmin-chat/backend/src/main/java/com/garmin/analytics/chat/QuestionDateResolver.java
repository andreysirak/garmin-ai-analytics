package com.garmin.analytics.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
  private final ObjectMapper json;
  private final Map<String, Object> schema;

  public QuestionDateResolver(
      Clock clock, GarminChatProperties properties, ObjectMapper json,
      @Value("classpath:prompts/date-selection-system.txt") Resource systemPrompt)
      throws IOException {
    this.clock = clock;
    this.properties = properties;
    this.json = json.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
    this.schema = selectionSchema();
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
      var selection = json.readValue(structuredSelection, DateSelection.class);
      if (selection == null || selection.type() == null || selection.days() == null
          || selection.startDate() == null || selection.endDate() == null) {
        return Optional.empty();
      }
      var today = referenceDate;
      DateRange range = switch (selection.type()) {
        case EXPLICIT -> {
          if (selection.days() != 0) {
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
    } catch (JsonProcessingException | RuntimeException invalidSelection) {
      // Model-generated dates are untrusted. Never query with malformed/ambiguous values.
      return Optional.empty();
    }
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

  public record DateSelection(SelectionType type, String startDate, String endDate, Integer days) {}

  public enum SelectionType {
    EXPLICIT, LAST_N_DAYS, LAST_WEEK, THIS_WEEK, LAST_MONTH, THIS_MONTH, YESTERDAY, TODAY,
    CLARIFICATION
  }
}
