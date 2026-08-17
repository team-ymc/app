---
type: Core Term
title: Self-Attention
---

## Self-Attention

Self-attention, also called intra-attention, relates different positions of one sequence to compute a representation of that sequence. Its defining property in the Transformer is the source of its inputs: the queries, keys, and values all come from the same sequence representation. This differs from encoder-decoder attention, where decoder queries attend to keys and values produced by the encoder.

The encoder contains a self-attention sub-layer in every layer. All queries, keys, and values come from the output of the preceding encoder layer, so each encoder position can attend to every position in that representation. The decoder also contains self-attention, but it is masked: a decoder position may attend only to positions up to and including itself. The combination of masking and output embeddings shifted by one position ensures that a prediction cannot use future output symbols.

Self-attention is the architectural replacement for sequence-aligned recurrence and convolution. The paper evaluates this choice through per-layer complexity, parallelizability, and maximum path length between positions. A full self-attention layer has complexity $O(n^2d)$, requires $O(1)$ sequential operations, and gives any pair of positions an $O(1)$ maximum path. A recurrent layer has complexity $O(nd^2)$ but requires $O(n)$ sequential operations and has an $O(n)$ maximum path. The authors argue that shorter paths can make long-range dependencies easier to learn and that self-attention is faster than recurrence when $n<d$, which they state is usually true for the sentence representations used in the machine-translation systems discussed.

Compared with convolution, full self-attention connects every pair of positions in one layer. A convolution with kernel width $k<n$ requires stacked layers to connect distant positions, producing longer paths. For very long sequences, the paper proposes restricting self-attention to a neighborhood of size $r$. This would lower complexity to $O(rnd)$ but increase maximum path length to $O(n/r)$; the authors present it as future work rather than part of the evaluated Transformer.

The paper also suggests a possible interpretability benefit. Attention visualizations from encoder layer 5 of 6 show heads connecting “making” with the distant phrase “more difficult,” heads described as apparently involved in anaphora resolution for “its,” and distinct patterns that seem related to sentence structure. These examples support the authors’ observation that different self-attention heads can learn different behaviors.

<!-- grounded-in
- [2 Background](../../source/02-background.md)
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [7 Conclusion](../../source/07-conclusion.md)
- [Attention Visualizations](../../source/attention-visualizations.md)
-->
