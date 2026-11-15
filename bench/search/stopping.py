"""Section 11's stopping rule, from ParadeDB's documented harness behaviour ("a 3-run window shows
under 0.1% variance/mean, or 10 runs"): repeat a batch until the last 3 batches' p50 have a
coefficient of variation (standard deviation over mean) under 0.1 %, or 10 batches. A variance over
a mean carries the unit (ms), so the plan reads the documented rule as the coefficient of
variation; P4 records this reading."""
import math
import statistics


def percentile(samples: list[float], q: float) -> float:
    """Nearest rank: the ceil(q n)-th smallest sample (Q12's convention)."""
    s = sorted(samples)
    return s[max(0, math.ceil(q * len(s)) - 1)]


def run_until_stable(batch, batch_size: int = 200, max_batches: int = 10):
    """batch(n) returns n latencies in ms. Returns (all samples, number of batches)."""
    samples, medians = [], []
    for b in range(1, max_batches + 1):
        lat = batch(batch_size)
        samples += lat
        medians.append(statistics.median(lat))
        if b >= 3:
            w = medians[-3:]
            if statistics.pstdev(w) / statistics.mean(w) < 0.001:
                return samples, b
    return samples, max_batches
