"""Section 11's stopping rule, from ParadeDB's documented harness behaviour ("a 3-run window shows
under 0.1% variance/mean, or 10 runs"): repeat a batch until the last 3 batches' p50 have a
coefficient of variation (standard deviation over mean) under 0.1 %, or 10 batches. A variance over
a mean carries the unit (ms), so the plan reads the documented rule as the coefficient of
variation; P4 records this reading."""
import math
import statistics
from typing import NamedTuple, Optional


class Stable(NamedTuple):
    """What run_until_stable measured: every sample, the number of batches run, whether the rule
    was met (False: the cap of max_batches was hit first), each batch's median and the coefficient
    of variation of the last 3 medians when the run stopped (None under 3 batches)."""
    samples: list
    batches: int
    met: bool
    medians: list
    cv: Optional[float]


def percentile(samples: list[float], q: float) -> float:
    """Nearest rank: the ceil(q n)-th smallest sample (Q12's convention)."""
    s = sorted(samples)
    return s[max(0, math.ceil(q * len(s)) - 1)]


def run_until_stable(batch, batch_size: int = 200, max_batches: int = 10) -> Stable:
    """batch(n) returns n latencies in ms. Stops at the first batch whose last 3 medians have a
    coefficient of variation under 0.1 % (met = True), else after max_batches with met = False
    (the rule is checked on the last batch too, so met = False means it never held)."""
    samples, medians = [], []
    cv = None
    for b in range(1, max_batches + 1):
        lat = batch(batch_size)
        samples += lat
        medians.append(statistics.median(lat))
        if b >= 3:
            w = medians[-3:]
            cv = statistics.pstdev(w) / statistics.mean(w)
            if cv < 0.001:
                return Stable(samples, b, True, medians, cv)
    return Stable(samples, max_batches, False, medians, cv)


def stability_fields(r: Stable) -> dict:
    """The jsonl fields recording how a run stopped."""
    return {"rule_met": r.met, "batch_medians_ms": [round(m, 4) for m in r.medians],
            "final_cv": None if r.cv is None else round(r.cv, 6)}
