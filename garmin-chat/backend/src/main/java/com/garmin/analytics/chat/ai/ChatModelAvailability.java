package com.garmin.analytics.chat.ai;

import com.garmin.analytics.chat.ai.exception.ChatModelUnavailableException;

public interface ChatModelAvailability {

  Status status();

  default void requireAvailable() {
    switch (status()) {
      case AVAILABLE -> { }
      case UNAVAILABLE -> throw new ChatModelUnavailableException(null);
      case TOOLS_UNSUPPORTED -> throw ChatModelUnavailableException.toolsUnsupported();
    }
  }

  enum Status {
    AVAILABLE, UNAVAILABLE, TOOLS_UNSUPPORTED
  }
}
