---
type: Paper Section
title: 5.3 Optimizer
---

### 5.3 Optimizer

We used the Adam optimizer [20] with  $ \beta_1 = 0.9 $,  $ \beta_2 = 0.98 $ and  $ \epsilon = 10^{-9} $. We varied the learning rate over the course of training, according to the formula:

<display_formula data-asset-key="formula_6">
$$
\begin{array}{r}{l r a t e=d_{\mathrm{m o d e l}}^{-0.5}\cdot\operatorname*{m i n}(s t e p\_{n} u m^{-0.5},s t e p\_{n} u m\cdot w a r m u p\_{s} t e p s^{-1.5})}\end{array}
$$
</display_formula>

This corresponds to increasing the learning rate linearly for the first warmup_steps training steps, and decreasing it thereafter proportionally to the inverse square root of the step number. We used warmup_steps = 4000.

<!-- followed-by
- [5.4 Regularization](05-04-regularization.md)
-->
