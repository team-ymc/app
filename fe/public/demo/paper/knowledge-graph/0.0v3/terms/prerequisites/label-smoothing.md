---
type: Prerequisite Term
title: Label Smoothing
---

## Label Smoothing

Label smoothing is a training regularizer that replaces a fully certain target with a softened target distribution. Instead of placing all target probability on one class, it leaves most weight on the correct class and distributes a small amount elsewhere. The parameter $\epsilon_{ls}$ controls how much smoothing is applied, discouraging the model from becoming maximally confident.

The Transformer base model uses label smoothing with $\epsilon_{ls}=0.1$. The paper explicitly describes the tradeoff: smoothing makes the model more unsure, which hurts perplexity, but improves accuracy and BLEU. Perplexity and translation quality therefore need not move in the same direction under this regularizer.

The model-variation results make that tradeoff visible. With no label smoothing, the listed development perplexity is 4.67 and BLEU is 25.3. With $\epsilon_{ls}=0.2$, perplexity worsens to 5.47 while BLEU reaches 25.7. The base value of 0.1 gives perplexity 4.92 and the best of these listed BLEU values, 25.8.

Label smoothing acts on the target probabilities used for training the output distribution. It is distinct from dropout, which randomly suppresses activations inside the network. The paper uses both: dropout is applied to sub-layer outputs and embedding-plus-position sums, while label smoothing changes the certainty of the training target presented to the decoder output.

<!-- grounded-in
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
