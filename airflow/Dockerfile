FROM apache/airflow:3.3.1

COPY --chown=airflow:root \
    garmin-integration/garmin-daily-summary/python/src/garmin_daily_summary \
    /opt/airflow/garmin-python/garmin_daily_summary
COPY --chown=airflow:root \
    garmin-integration/garmin-sleep/python/src/garmin_sleep \
    /opt/airflow/garmin-python/garmin_sleep

ENV PYTHONPATH="/opt/airflow/garmin-python:/opt/airflow"
