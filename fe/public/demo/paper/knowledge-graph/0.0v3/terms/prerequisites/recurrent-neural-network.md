---
type: Prerequisite Term
title: Recurrent Neural Network
---

## Recurrent Neural Network

A recurrent neural network processes an ordered sequence through a chain of hidden states. At position $t$, it computes $h_t$ from the preceding state $h_{t-1}$ and the current input. Earlier information can affect later positions because it is carried through this state sequence. Long short-term memory and gated recurrent networks are recurrent variants named in the paper’s introduction.

The recurrence imposes an order on computation. State $h_t$ cannot be completed until $h_{t-1}$ is available, so positions within one training example cannot all be processed simultaneously. The paper identifies this inherently sequential structure as a fundamental limit on parallelization, especially for longer sequences where memory constraints also limit batching across examples.

The efficiency comparison expresses the same issue quantitatively. A recurrent layer has per-layer complexity $O(nd^2)$, requires $O(n)$ sequential operations, and has an $O(n)$ maximum path between distant positions. By comparison, a self-attention layer has complexity $O(n^2d)$ but requires $O(1)$ sequential operations and has an $O(1)$ maximum path. The authors state that self-attention is faster when sequence length $n$ is smaller than representation dimension $d$, which is commonly true for the sentence representations they discuss.

The Transformer’s defining change is to remove sequence-aligned recurrence from both its encoder and decoder representations. It retains autoregressive output generation—the decoder still predicts symbols one at a time—but the internal sequence layers are built from multi-head attention and position-wise feed-forward networks rather than recurrent hidden-state transitions.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [1 Introduction](../../source/01-introduction.md)
- [2 Background](../../source/02-background.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](../../source/03-model-architecture/03-05-positional-encoding.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
- [7 Conclusion](../../source/07-conclusion.md)
-->
