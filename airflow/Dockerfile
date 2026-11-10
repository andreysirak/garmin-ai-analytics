FROM apache/airflow:3.3.1

COPY --chown=airflow:root \
    garmin-integration/garmin-data-ingestion/python/src/garmin_daily_summary \
    /opt/airflow/garmin-python/garmin_daily_summary
COPY --chown=airflow:root \
    garmin-integration/garmin-data-ingestion/python/src/garmin_sleep \
    /opt/airflow/garmin-python/garmin_sleep
COPY --chown=airflow:root \
    garmin-integration/garmin-auth/python/src/garmin_auth \
    /opt/airflow/garmin-python/garmin_auth

ENV PYTHONPATH="/opt/airflow/garmin-python:/opt/airflow"
