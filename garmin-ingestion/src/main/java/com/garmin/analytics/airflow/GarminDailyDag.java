package com.garmin.analytics.airflow;

import java.time.LocalDate;
import java.util.Map;

import org.apache.airflow.sdk.Builder;
import org.apache.airflow.sdk.Client;

@Builder.Dag(id = "garmin_daily_steps")
public class GarminDailyDag {

    @Builder.Task(id = "fetch_steps")
    public Map<String, Object> fetchSteps(
            Client client,
            @Builder.XCom(task = "resolve_target_date") String targetDate
    ) throws Exception {
        LocalDate date = LocalDate.parse(targetDate);
        GarminTaskFactory taskFactory = GarminTaskFactory.fromConnection(client.getConnection("garmin_api"));
        return taskFactory.ingestSteps(date);
    }

    @Builder.Task(id = "fetch_sleep")
    public Map<String, Object> fetchSleep(
            Client client,
            @Builder.XCom(task = "resolve_target_date") String targetDate
    ) throws Exception {
        LocalDate date = LocalDate.parse(targetDate);
        GarminTaskFactory taskFactory = GarminTaskFactory.fromConnection(client.getConnection("garmin_api"));
        return taskFactory.ingestSleep(date);
    }
}
