---
type: Prerequisite Term
title: Autoregressive Generation
---

## Autoregressive Generation

Autoregressive generation constructs a sequence one element at a time. At position $i$, the model predicts the next symbol using the input and the output prefix that is already known. The sequence probability can be viewed as a product of conditional next-symbol probabilities, with each factor conditioned on positions before the current one. Generation continues by adding a selected symbol to the prefix and predicting again.

The Transformer decoder follows this pattern. It receives the encoder representation and previously produced output symbols, then its final linear transformation and softmax produce predicted next-token probabilities. Because later output symbols must not influence an earlier prediction, the decoder cannot use unrestricted self-attention over the entire target sequence.

The paper enforces this direction in two coordinated ways. Output embeddings are shifted one position to the right, and decoder self-attention masks subsequent positions. Inside scaled dot-product attention, scores for illegal future connections are set to $-\infty$ before softmax. A position can therefore attend only to the output positions up to and including itself, ensuring that the prediction at position $i$ depends only on outputs before $i$.

At inference time, the next-symbol probabilities still leave a choice about which partial output to continue. The paper uses beam search rather than committing to only one candidate at each step. For translation it uses beam size 4, and for constituency parsing it uses beam size 21. Autoregression defines the dependency direction; beam search is the procedure used to explore possible sequences under those next-symbol predictions.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [6.1 Machine Translation](../../source/06-results/06-01-machine-translation.md)
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
-->
