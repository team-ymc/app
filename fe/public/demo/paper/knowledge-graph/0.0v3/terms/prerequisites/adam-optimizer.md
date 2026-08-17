---
type: Prerequisite Term
title: Adam Optimizer
---

## Adam Optimizer

Adam is a gradient-based optimization algorithm used to update a model’s learned parameters during training. For each parameter, it maintains moving estimates of the recent gradient and the recent squared gradient. These estimates adapt the update size to the history of that parameter rather than applying the same raw gradient scale everywhere.

The coefficients $\beta_1$ and $\beta_2$ control how quickly the first and second moving estimates change. The small constant $\epsilon$ stabilizes the denominator in the adaptive update. A separate learning rate controls the overall step size, so selecting Adam does not by itself determine how the learning rate should change over the course of training.

The paper trains the Transformer with Adam using $\beta_1=0.9$, $\beta_2=0.98$, and $\epsilon=10^{-9}$. It supplies a custom learning-rate schedule: $rate=d_{model}^{-0.5}\min(step\_num^{-0.5},step\_num\cdot warmup\_steps^{-1.5})$. The rate is scaled by the inverse square root of the model dimension.

With $warmup\_steps=4000$, the schedule increases linearly through the warmup period and then decreases in proportion to the inverse square root of the step number. This schedule is part of how Adam is used in the paper, not part of the general definition of Adam. The base Transformer trains for 100,000 steps, while the big configuration trains for 300,000 steps.

<!-- grounded-in
- [5.2 Hardware and Schedule](../../source/05-training/05-02-hardware-and-schedule.md)
- [5.3 Optimizer](../../source/05-training/05-03-optimizer.md)
-->
