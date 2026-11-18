package com.garmin.analytics.chat.model;

import java.util.Objects;

/** Either a validated query or an application-authored clarification/rejection. */
public record QueryIntentResolution(QueryIntent intent, String message) {

  public QueryIntentResolution {
    Objects.requireNonNull(message);
    if ((intent == null) == message.isBlank()) {
      throw new IllegalArgumentException("Provide either an intent or a rejection message");
    }
  }

  public boolean accepted() {
    return intent != null;
  }
}
