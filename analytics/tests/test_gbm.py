"""GBM sanity tests: the closed-form increment has known moments.

Over N independent single-step samples of length ``dt``::

    E[log(S_t/S_0)]    = (mu - sigma^2/2) · dt
    Var[log(S_t/S_0)]  = sigma^2 · dt

We generate a large independent sample (fresh GbmPath per draw to keep
samples i.i.d.) and check both against the closed-form expectations with
a loose-but-not-vacuous tolerance.
"""

import math
import random

import numpy as np

from nanoexchange_analytics.gbm import GbmPath


def _one_step_log_return(seed: int, *, mu: float, sigma: float, dt: float,
                         s0: float) -> float:
    rng = random.Random(seed)
    path = GbmPath(mu=mu, sigma=sigma, dt=dt, s0=s0, rng=rng)
    s1 = path.step()
    return math.log(s1 / s0)


def test_gbm_single_step_moments():
    mu, sigma, dt, s0 = 0.05, 0.3, 1.0 / 252.0, 100.0
    samples = np.array([
        _one_step_log_return(seed, mu=mu, sigma=sigma, dt=dt, s0=s0)
        for seed in range(20_000)
    ])
    expected_mean = (mu - 0.5 * sigma * sigma) * dt
    expected_var = sigma * sigma * dt
    # 20_000 samples → SE of mean is sqrt(var/n). 99 % CI is ~3 SE.
    se_mean = math.sqrt(expected_var / len(samples))
    assert abs(samples.mean() - expected_mean) < 4 * se_mean
    assert abs(samples.var() - expected_var) / expected_var < 0.05


def test_gbm_is_strictly_positive():
    """GBM paths never go non-positive. The exact-solution form guarantees
    this mathematically; the Euler form does not (which is why we don't
    use it). Floating-point can still underflow to +0 under ridiculous
    sigma, so we assert ``>= 0`` here and pick realistic parameters."""
    rng = random.Random(42)
    path = GbmPath(mu=-0.5, sigma=0.8, dt=0.01, s0=100.0, rng=rng)
    for _ in range(10_000):
        s = path.step()
        assert s > 0, f"GBM went non-positive: {s}"


def test_gbm_deterministic_with_seeded_rng():
    a = GbmPath(mu=0.0, sigma=0.2, dt=0.01, s0=100.0, rng=random.Random(7))
    b = GbmPath(mu=0.0, sigma=0.2, dt=0.01, s0=100.0, rng=random.Random(7))
    for _ in range(50):
        assert a.step() == b.step()
