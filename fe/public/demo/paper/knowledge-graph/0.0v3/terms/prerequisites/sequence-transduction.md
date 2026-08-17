---
type: Prerequisite Term
title: Sequence Transduction
---

## Sequence Transduction

Sequence transduction is the problem of converting one sequence into another. An input consists of ordered symbols $(x_1,\ldots,x_n)$, and the desired output is another ordered sequence $(y_1,\ldots,y_m)$. The two sequences may contain different kinds of symbols and need not have the same length. A transduction model must therefore represent the input, preserve relevant relationships within it, and produce an output whose content and order depend on that input.

Machine translation is the paper’s main example: an input sentence in one language is transformed into an output sentence in another. English constituency parsing is the second example. The paper notes that parsing outputs are subject to strong structural constraints and are significantly longer than their input sentences, making it a useful test of whether the architecture transfers beyond translation.

The Transformer implements sequence transduction with an encoder-decoder architecture. Its encoder maps $(x_1,\ldots,x_n)$ to continuous representations $\mathbf{z}=(z_1,\ldots,z_n)$. Given $\mathbf{z}$, the decoder generates $(y_1,\ldots,y_m)$ one element at a time and consumes earlier generated symbols when predicting the next. Attention provides the connections within the input representation, within the known output prefix, and from the decoder back to the encoded input.

The paper’s central claim is not that sequence transduction itself is new, but that it can be performed without the recurrent or convolutional sequence layers dominant in earlier systems. The Transformer is presented as the first transduction model to compute its input and output representations entirely with self-attention, and its translation and parsing experiments test that design on different sequence-to-sequence problems.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [1 Introduction](../../source/01-introduction.md)
- [2 Background](../../source/02-background.md)
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [6.1 Machine Translation](../../source/06-results/06-01-machine-translation.md)
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
- [7 Conclusion](../../source/07-conclusion.md)
-->
