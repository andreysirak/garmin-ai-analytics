"""Independent Decimal oracle for synthetic evidence; never reads Garmin records."""

from decimal import Decimal

CATEGORIES = (
    "missingSummaryDays",
    "missingMetricDays",
    "nullMetricDays",
    "invalidTypeDays",
)


def evidence(case, protocol):
    counts = dict.fromkeys(CATEGORIES, 0)
    readings = []
    for observation in case["observations"]:
        kind = observation["kind"]
        if kind == "reading":
            value = observation["value"]
            if isinstance(value, bool):
                raise ValueError("Boolean is not a reading")
            number = Decimal(str(value))
            if not number.is_finite():
                raise ValueError("Nonfinite reading")
            readings.append(number)
        elif kind in counts:
            counts[kind] += 1
        else:
            raise ValueError("Unknown observation category")
    if len(case["observations"]) != 7:
        raise ValueError("These versioned fixtures cover exactly seven days")
    operation = case["aggregation"]
    if operation not in {"SUM", "AVG", "MIN", "MAX"}:
        raise ValueError("Unsupported fixture operation")
    value = None
    if readings:
        value = {
            "SUM": lambda: sum(readings),
            "AVG": lambda: sum(readings) / len(readings),
            "MIN": lambda: min(readings),
            "MAX": lambda: max(readings),
        }[operation]()
        value /= Decimal(str(case.get("divisor", 1)))
    expected = case["expected_value"]
    if (value is None) != (expected is None) or (
        value is not None and value != Decimal(str(expected))
    ):
        raise ValueError("Fixture expected value disagrees with independent oracle")
    coverage = {
        "expectedDays": 7,
        **counts,
        "daysWithData": len(readings),
        "zeroDays": readings.count(Decimal(0)),
        "summaryDays": 7 - counts["missingSummaryDays"],
        "missingDays": sum(counts[k] for k in CATEGORIES[:3]),
        "complete": len(readings) == 7,
    }
    # Frozen cases use exactly representable decimal values.
    number = (
        None if value is None else int(value) if value == int(value) else float(value)
    )
    return {
        "aggregation": operation,
        "dateRange": protocol["evidence_range"],
        "metrics": [
            {
                "metric": case["metric"],
                "value": number,
                "unit": case["unit"],
                "coverage": coverage,
            }
        ],
    }
