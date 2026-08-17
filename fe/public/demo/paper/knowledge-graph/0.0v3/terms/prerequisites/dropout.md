---
type: Prerequisite Term
title: Dropout
---

## Dropout

Dropout is a regularization method that randomly suppresses part of a neural network’s activations during training. The dropout probability controls how often elements are removed. Because the model cannot rely on every activation being present in every training pass, dropout is used to reduce overfitting to the training data.

The Transformer applies residual dropout to the output of every sub-layer. Dropout occurs before the sub-layer output is added to its input and before the combined result is normalized. This placement affects multi-head self-attention, encoder-decoder attention, and position-wise feed-forward sub-layers throughout the encoder and decoder.

Dropout is also applied to the sums of token embeddings and positional encodings at the bottoms of both stacks. The base model uses $P_{drop}=0.1$. The big English-to-German configuration uses 0.3, while the big English-to-French model described in the results section uses 0.1 instead.

The variation table shows the paper’s evidence for the regularizer. With the base configuration otherwise retained, setting dropout to 0 gives development perplexity 5.77 and BLEU 24.6. A rate of 0.2 gives perplexity 4.95 and BLEU 25.5, while the base rate of 0.1 gives 4.92 and 25.8. The authors summarize these comparisons by stating that dropout is very helpful in avoiding overfitting.

<!-- grounded-in
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [6.1 Machine Translation](../../source/06-results/06-01-machine-translation.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
-->
