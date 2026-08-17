---
type: Formula
title: Transformer Learning-Rate Schedule
---

## Transformer Learning-Rate Schedule

$$
\begin{array}{r}{l r a t e=d_{\mathrm{m o d e l}}^{-0.5}\cdot\operatorname*{m i n}(s t e p\_{n} u m^{-0.5},s t e p\_{n} u m\cdot w a r m u p\_{s} t e p s^{-1.5})}\end{array}
$$

## Description

This equation defines the learning-rate schedule used to train the Transformer with Adam. The complete rate is scaled by $d_{model}^{-0.5}$. Inside the minimum, $step\_num^{-0.5}$ is the inverse-square-root branch and $step\_num\cdot warmup\_steps^{-1.5}$ is the linear warmup branch. Selecting the smaller branch makes the learning rate rise linearly during the warmup period and then decrease in proportion to the inverse square root of the step number.

The paper sets $warmup\_steps=4000$. At that point the two branches meet, so the schedule changes from increasing to decreasing. The model dimension also affects the overall scale: the rate is multiplied by the inverse square root of $d_{model}$. The accompanying optimizer configuration uses Adam with $\beta_1=0.9$, $\beta_2=0.98$, and $\epsilon=10^{-9}$. This equation therefore specifies how the learning rate changes over training; it does not replace the Adam update itself.

<!-- cited-in
- [5.3 Optimizer](../source/05-training/05-03-optimizer.md)
-->
