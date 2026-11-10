"""Airflow integration for the endpoint-agnostic Garmin ingestion application."""

from garmin_data_ingestion.operator import GarminIngestionOperator

__all__ = ["GarminIngestionOperator"]
