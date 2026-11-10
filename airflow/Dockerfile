FROM apache/airflow:3.3.1

COPY --chown=airflow:root \
    garmin-integration/garmin-data-ingestion/python/src/garmin_data_ingestion \
    /opt/airflow/garmin-python/garmin_data_ingestion
COPY --chown=airflow:root \
    garmin-integration/garmin-auth/python/src/garmin_auth \
    /opt/airflow/garmin-python/garmin_auth

ENV PYTHONPATH="/opt/airflow/garmin-python:/opt/airflow"
