package com.garmin.analytics.airflow;

import java.util.Map;

import org.apache.airflow.sdk.Builder;
import org.apache.airflow.sdk.Client;

@Builder.Dag(id = "java_sdk_hello")
public class JavaHelloDag {

    @Builder.Task(id = "hello_java")
    public Map<String, Object> helloJava(Client client) {
        return Map.of(
                "status", "ok",
                "message", "hello-from-java",
                "task", "hello_java"
        );
    }
}
