package com.garmin.analytics.integration.common.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonValidationUtilTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "{}",
      "[]",
      "null",
      "true",
      "false",
      "0",
      "-1.25e+3",
      "\"text\"",
      "{\"calendarDate\":\"not-a-date\",\"totalSteps\":\"untyped\",\"unknown\":null}",
      "{\"dailySleepDTO\":{\"anything\":[1,false,null]}}",
      " \n [ {}, [1,2], \"café\" ]\r\n"})
  void acceptsAnySingleJsonValueWithoutFieldValidation(String rawJson) {
    assertDoesNotThrow(() -> JsonValidationUtil.validate(rawJson));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      " \n\t",
      "{",
      "[1,]",
      "{\"a\":}",
      "{\"a\":1} garbage",
      "{} {}",
      "[1] null",
      "true false",
      "1 2",
      "{}/* comment */",
      "{unquoted:1}",
      "'text'",
      "NaN"})
  void rejectsInvalidOrMultipleJsonValues(String rawJson) {
    InvalidResponseException failure = assertThrows(InvalidResponseException.class,
        () -> JsonValidationUtil.validate(rawJson));
    assertInstanceOf(JsonProcessingException.class, failure.getCause());
    assertEquals("Garmin response JSON error", failure.getMessage());
  }
}
