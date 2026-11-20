package dev.garmin.evaluation.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Verdict;
import dev.garmin.evaluation.dataset.EvaluationSuite;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.time.temporal.TemporalAdjusters.previousOrSame;

/** Validates the versioned routing contract before judging the expected decision. */
public final class RoutingEvaluator {
    private static final Set<String> TARGETS = Set.of("METRIC", "CALCULATION", "PERIOD", "GOAL");
    private static final Set<String> REASONS = Set.of(
            "RELATIONSHIP", "UNSUPPORTED_METRIC", "UNSUPPORTED_CALCULATION", "HEALTH_ADVICE", "OTHER");
    private static final Set<String> AGGREGATIONS = Set.of("SUM", "AVG", "MIN", "MAX");
    private static final Map<String, Set<String>> METRICS = Map.of(
            "totalSteps", AGGREGATIONS, "activeKilocalories", AGGREGATIONS,
            "sleepingSeconds", AGGREGATIONS, "restingHeartRate", Set.of("AVG", "MIN", "MAX"));

    private final LocalDate referenceDate;
    private final int maxDays;

    public RoutingEvaluator(JsonNode protocol) {
        referenceDate = LocalDate.parse(Json.text(protocol, "reference_date"));
        maxDays = protocol.required("max_query_days").intValue();
    }

    public Verdict grade(JsonNode expected, String answer) {
        ObjectNode route;
        try {
            route = decode(answer);
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException rejected) {
            var verdict = Verdict.fail("routing_contract_rejected");
            verdict.details().putObject("routing_validation").put("accepted", false);
            return verdict;
        }
        var verdict = compare(expected, route);
        verdict.details().set("routing_validation", route.put("accepted", true));
        return verdict;
    }

    private ObjectNode decode(String answer) throws IOException {
        var raw = Json.MAPPER.readTree(answer);
        fields(raw, "route", "selection", "target", "question", "reason");
        var route = Json.text(raw, "route");
        var decoded = Json.object().put("route", route);
        switch (route) {
            case "ANALYZE" -> {
                absent(raw, "target", "question", "reason");
                var selection = raw.path("selection");
                fields(selection, "metrics", "aggregation", "period");
                var aggregation = Json.text(selection, "aggregation");
                var metrics = selection.path("metrics");
                require(metrics.isArray() && !metrics.isEmpty() && metrics.size() <= METRICS.size());
                var names = new HashSet<String>();
                for (var metric : metrics) {
                    require(metric.isTextual() && names.add(metric.asText())
                            && METRICS.getOrDefault(metric.asText(), Set.of()).contains(aggregation));
                }
                var period = normalizePeriod(selection.path("period"));
                var range = resolve(period);
                var normalized = decoded.putObject("selection");
                normalized.set("metrics", metrics);
                normalized.put("aggregation", aggregation).set("period", period);
                decoded.putObject("range").put("startDate", range.start().toString())
                        .put("endDate", range.end().toString());
            }
            case "CLARIFY" -> {
                absent(raw, "selection", "reason");
                var target = Json.text(raw, "target");
                var question = Json.text(raw, "question");
                require(TARGETS.contains(target) && question.length() <= 240);
                decoded.put("target", target).put("question", question.strip());
            }
            case "UNSUPPORTED" -> {
                absent(raw, "selection", "target", "question");
                var reason = Json.text(raw, "reason");
                require(REASONS.contains(reason));
                decoded.put("reason", reason);
            }
            default -> throw new IllegalArgumentException("Unknown route");
        }
        return decoded;
    }

    private static ObjectNode normalizePeriod(JsonNode raw) {
        fields(raw, "type", "startDate", "endDate", "days");
        var period = Json.object().put("type", Json.text(raw, "type"));
        for (String key : List.of("startDate", "endDate")) {
            if (raw.hasNonNull(key)) {
                require(raw.get(key).isTextual());
                if (!raw.get(key).asText().isEmpty()) {
                    period.set(key, raw.get(key));
                }
            }
        }
        if (raw.hasNonNull("days")) {
            var days = raw.get("days");
            require(days.isIntegralNumber() && days.canConvertToInt());
            if (days.intValue() != 0) {
                period.put("days", days.intValue());
            }
        }
        return period;
    }

    private record Range(LocalDate start, LocalDate end) {}

    private Range resolve(ObjectNode period) {
        var monday = referenceDate.with(previousOrSame(DayOfWeek.MONDAY));
        var firstOfMonth = referenceDate.withDayOfMonth(1);
        String type = period.get("type").asText();
        require(switch (type) {
            case "EXPLICIT" -> period.size() == 3 && period.has("startDate") && period.has("endDate");
            case "LAST_N_DAYS" -> period.size() == 2 && period.path("days").asInt() > 0;
            default -> period.size() == 1;
        });
        var range = switch (type) {
            case "EXPLICIT" -> new Range(date(period.get("startDate").asText()),
                    date(period.get("endDate").asText()));
            case "LAST_N_DAYS" -> new Range(referenceDate.minusDays(period.get("days").intValue()),
                    referenceDate.minusDays(1));
            case "LAST_WEEK" -> new Range(monday.minusWeeks(1), monday.minusDays(1));
            case "THIS_WEEK" -> new Range(monday, referenceDate);
            case "LAST_MONTH" -> new Range(firstOfMonth.minusMonths(1), firstOfMonth.minusDays(1));
            case "THIS_MONTH" -> new Range(firstOfMonth, referenceDate);
            case "YESTERDAY" -> new Range(referenceDate.minusDays(1), referenceDate.minusDays(1));
            case "TODAY" -> new Range(referenceDate, referenceDate);
            default -> throw new IllegalArgumentException("Unknown period");
        };
        require(range.start().getYear() > 0 && !range.end().isBefore(range.start())
                && !range.end().isAfter(referenceDate)
                && ChronoUnit.DAYS.between(range.start(), range.end()) + 1 <= maxDays);
        return range;
    }

    private static LocalDate date(String value) {
        require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        return LocalDate.parse(value);
    }

    private static Verdict compare(JsonNode expected, JsonNode actual) {
        String route = expected.required("route").asText();
        if (!expected.get("route").equals(actual.get("route"))) {
            return Verdict.fail("wrong_route");
        }
        return switch (route) {
            case "ANALYZE" -> compareSelection(expected.required("selection"), actual.required("selection"));
            case "UNSUPPORTED" -> expected.required("reason").equals(actual.get("reason"))
                    ? Verdict.pass() : Verdict.fail("wrong_reason");
            case "CLARIFY" -> {
                var target = expected.required("target");
                var allowed = target.isArray() ? EvaluationSuite.strings(target) : List.of(target.asText());
                yield allowed.contains(actual.get("target").asText())
                        ? Verdict.review(List.of("focused_clarification_preserves_supplied_information"))
                        : Verdict.fail("wrong_clarification_target");
            }
            default -> throw new IllegalStateException("Invalid expected route");
        };
    }

    private static Verdict compareSelection(JsonNode expected, JsonNode actual) {
        var expectedMetrics = Set.copyOf(EvaluationSuite.strings(expected.required("metrics")));
        if (!expectedMetrics.equals(Set.copyOf(EvaluationSuite.strings(actual.required("metrics"))))
                || !expected.required("aggregation").equals(actual.get("aggregation"))) {
            return Verdict.fail("wrong_metric_or_calculation");
        }
        return expected.required("period").equals(actual.get("period"))
                ? Verdict.pass() : Verdict.fail("wrong_period");
    }

    private static void absent(JsonNode node, String... names) {
        for (String name : names) {
            require(!node.hasNonNull(name));
        }
    }

    private static void fields(JsonNode node, String... names) {
        var allowed = Set.of(names);
        require(node != null && node.isObject() && node.properties().stream().allMatch(entry -> allowed.contains(entry.getKey())));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid routing output");
        }
    }
}
