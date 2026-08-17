---
type: Prerequisite Term
title: Perplexity
---

## Perplexity

Perplexity summarizes how much probability a sequence model assigns to observed tokens. It is commonly expressed as the exponential of the average negative log-probability of the target sequence. Lower perplexity means the model assigns higher probability to the observed tokens on average under that particular evaluation and tokenization setup.

The unit being predicted matters. A per-wordpiece perplexity averages over word pieces, while a per-word perplexity averages over whole words. Changing the tokenization changes both the sequence and the prediction vocabulary, so the resulting numbers are not directly comparable. Perplexity should also be interpreted on held-out data rather than as a standalone description of translation quality.

The paper reports development-set perplexity in its Transformer variation table. The caption explicitly states that the values are per wordpiece under byte-pair encoding and must not be compared with per-word perplexities. The base configuration reports 4.92, while the big configuration reports 4.33.

The regularization results show why lower perplexity is not always aligned with higher BLEU. With no label smoothing, perplexity improves to 4.67 but BLEU is 25.3. The base smoothing value $\epsilon_{ls}=0.1$ gives a worse perplexity of 4.92 but a higher BLEU of 25.8. The authors explain that label smoothing makes the model less certain, hurting perplexity while improving accuracy and BLEU.

<!-- grounded-in
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
