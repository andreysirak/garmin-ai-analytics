from __future__ import annotations

import unittest

from airflow.models.dagbag import DagBag


class GarminDagValidationTest(unittest.TestCase):
    def test_dags_import_without_errors(self) -> None:
        dag_bag = DagBag(dag_folder="/opt/airflow/dags", include_examples=False)
        self.assertEqual({}, dag_bag.import_errors)

    def test_garmin_daily_steps_dag_shape(self) -> None:
        dag_bag = DagBag(dag_folder="/opt/airflow/dags", include_examples=False)
        dag = dag_bag.get_dag("garmin_daily_steps")

        self.assertIsNotNone(dag)
        task_ids = {task.task_id for task in dag.tasks}
        self.assertEqual({"resolve_target_date", "fetch_steps", "fetch_sleep"}, task_ids)

    def test_java_hello_dag_exists(self) -> None:
        dag_bag = DagBag(dag_folder="/opt/airflow/dags", include_examples=False)
        dag = dag_bag.get_dag("java_sdk_hello")

        self.assertIsNotNone(dag)
        task_ids = {task.task_id for task in dag.tasks}
        self.assertEqual({"hello_java"}, task_ids)


if __name__ == "__main__":
    unittest.main()
