package com.garmin.analytics.chat.diagnostics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatDiagnostics {

  private static final Logger log = LoggerFactory.getLogger(ChatDiagnostics.class);
  private static final Logger trace = LoggerFactory.getLogger("garmin.chat.diagnostics");

  private final ObjectWriter json;

  public ChatDiagnostics(ObjectMapper mapper) {
    this.json = mapper.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public boolean enabled() {
    return trace.isDebugEnabled();
  }

  public void record(String event, Object value) {
    if (enabled()) {
      try {
        trace.debug("event={} data={}", event, json.writeValueAsString(value));
      } catch (JsonProcessingException failure) {
        log.warn("diagnostic_serialization_failed event={}", event);
      }
    }
  }
}
