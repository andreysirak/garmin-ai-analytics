package com.garmin.analytics.chat.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.garmin.analytics.chat.model.PeriodSelection;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RoutingSchemaTest {

  @Test
  void suppliesCompactDisjointPeriodsAndCanonicalMetricNames() {
    var root = ChatTestSupport.JSON.valueToTree(RoutingSchema.generate());
    var selection = root.at("/anyOf/2/properties/selection/properties");
    assertThat(selection.at("/metrics/items/enum"))
        .map(node -> node.asText())
        .containsExactly(Arrays.stream(Metric.values()).map(Metric::field).toArray(String[]::new));
    var periods = selection.at("/period/anyOf");
    assertThat(periods).hasSize(PeriodSelection.Type.values().length);
    for (var period : periods) {
      String type = period.at("/properties/type/const").asText();
      assertThat(period.get("additionalProperties").asBoolean()).isFalse();
      switch (type) {
        case "EXPLICIT" -> assertThat(period.get("required"))
            .map(node -> node.asText()).containsExactly("type", "startDate", "endDate");
        case "LAST_N_DAYS" -> assertThat(period.get("required"))
            .map(node -> node.asText()).containsExactly("type", "days");
        default -> {
          assertThat(period.get("required")).map(node -> node.asText()).containsExactly("type");
          assertThat(period.get("properties").size()).isEqualTo(1);
        }
      }
    }
  }

  @Test
  void decodesCompactPeriodsWithoutInventingExplicitDates() throws Exception {
    var week = ChatTestSupport.JSON.readValue("{\"type\":\"LAST_WEEK\"}", PeriodSelection.class);
    assertThat(week.type()).isEqualTo(PeriodSelection.Type.LAST_WEEK);
    assertThat(week.days()).isZero();
    assertThat(week.startDate()).isEmpty();
    assertThat(week.endDate()).isEmpty();
    var rolling = ChatTestSupport.JSON.readValue(
        "{\"type\":\"LAST_N_DAYS\",\"days\":7}", PeriodSelection.class);
    assertThat(rolling.days()).isEqualTo(7);
    assertThat(rolling.startDate()).isEmpty();
  }
}
