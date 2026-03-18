"""
Geometric Brownian Motion price-path generator for the simulator.

    dS/S = μ dt + σ dW

Discretised exactly via the closed-form increment so that the sampled
distribution has the correct mean and variance for any step size::

    S_{t+Δt} = S_t · exp( (μ − σ²/2) Δt  +  σ √Δt · Z )

where Z ~ N(0, 1). We use the exact-solution form (not the Euler
approximation) because the discretisation error of Euler accumulates
badly on long paths and the exact form is the same number of flops.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass


@dataclass(slots=True)
class GbmPath:
    mu: float
    sigma: float
    dt: float
    s0: float
    rng: random.Random
    _s: float = 0.0

    def __post_init__(self) -> None:
        self._s = self.s0

    def step(self) -> float:
        z = self.rng.gauss(0.0, 1.0)
        drift = (self.mu - 0.5 * self.sigma * self.sigma) * self.dt
        diffusion = self.sigma * math.sqrt(self.dt) * z
        self._s *= math.exp(drift + diffusion)
        return self._s

    @property
    def current(self) -> float:
        return self._s
