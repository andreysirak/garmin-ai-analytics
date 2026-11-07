package com.garmin.analytics.airflow;

import java.util.List;

import org.apache.airflow.sdk.BundleBuilder;
import org.apache.airflow.sdk.Dag;
import org.apache.airflow.sdk.Server;

public final class JavaBundleMain implements BundleBuilder {

    @Override
    public Iterable<Dag> getDags() {
        return List.of(
                JavaHelloDagBuilder.build(),
                GarminDailyDagBuilder.build()
        );
    }

    public static void main(String[] args) {
        Server.create(args).serve(new JavaBundleMain().build());
    }
}
