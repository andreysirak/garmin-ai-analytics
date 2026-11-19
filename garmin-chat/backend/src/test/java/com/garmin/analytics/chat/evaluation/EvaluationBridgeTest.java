package com.garmin.analytics.chat.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvaluationBridgeTest {

  private final ObjectMapper json = new ObjectMapper();

  private com.fasterxml.jackson.databind.node.ObjectNode request(String action) {
    return json.createObjectNode().put("action", action).put("reference_date", "2026-11-19")
        .put("timezone", "Europe/Oslo").put("context_tokens", 32768)
        .put("response_tokens", 1024).put("max_query_days", 31);
  }

  @Test
  void exportsActualCatalogAndRoutingSchema() throws Exception {
    var request = request("export");
    request.putArray("items").addObject().put("question", "Total steps yesterday?");
    var result = json.valueToTree(EvaluationBridge.execute(request)).get(0);
    assertThat(result.get("messages").get(0).get("content").asText())
        .contains("totalSteps", "Reference date: 2026-11-19", "Clipped to calendar-day");
    assertThat(result.get("format").has("anyOf")).isTrue();
  }

  @Test
  void exportsEvidenceWithDerivedCoverageAndNullDistinctFromZero() throws Exception {
    var request = request("export");
    var item = request.putArray("items").addObject().put("question", "Total steps last week?");
    item.set("evidence", json.readTree("""
        {"aggregation":"SUM","dateRange":{"startDate":"2026-11-09","endDate":"2026-11-15"},
        "metrics":[{"metric":"totalSteps","value":null,"unit":"steps","coverage":{
        "expectedDays":7,"missingSummaryDays":7,"missingMetricDays":0,"nullMetricDays":0,
        "invalidTypeDays":0,"daysWithData":0,"zeroDays":0,
        "summaryDays":0,"missingDays":7,"complete":false}}]}
        """));
    var result = json.valueToTree(EvaluationBridge.execute(request)).get(0);
    var content = result.at("/messages/2/content").asText();
    var evidence = json.readTree(content.substring(content.indexOf('\n') + 1));
    assertThat(evidence.at("/metrics/0/value").isNull()).isTrue();
    assertThat(evidence.at("/metrics/0/coverage/missingDays").asInt()).isEqualTo(7);
    assertThat(evidence.at("/dateRange/startDate").asText()).isEqualTo("2026-11-09");
  }

  @Test
  void usesProductionParsingAndDateRulesWithoutDatabaseAccess() throws Exception {
    var request = request("validate");
    var items = request.putArray("items");
    items.addObject().put("output", """
        {"route":"ANALYZE","selection":{"metrics":["totalSteps"],"aggregation":"SUM",
        "period":{"type":"LAST_N_DAYS","days":7}}}
        """);
    items.addObject().put("output", "{\"route\":\"CLARIFY\",\"route\":\"ANALYZE\"}");
    items.addObject().put("output", """
        {"route":"ANALYZE","selection":{"metrics":["restingHeartRate"],"aggregation":"SUM",
        "period":{"type":"LAST_WEEK"}}}
        """);
    var results = json.valueToTree(EvaluationBridge.execute(request));
    assertThat(results.get(0).get("accepted").asBoolean()).isTrue();
    assertThat(results.get(0).at("/intent/range/startDate").asText()).isEqualTo("2026-11-12");
    assertThat(results.get(1).get("accepted").asBoolean()).isFalse();
    assertThat(results.get(2).get("accepted").asBoolean()).isFalse();
  }
}
