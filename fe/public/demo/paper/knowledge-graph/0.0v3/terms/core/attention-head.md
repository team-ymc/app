---
type: Core Term
title: Attention Head
---

## Attention Head

An attention head is one scaled dot-product attention operation within multi-head attention. Head $i$ does not receive the unmodified full-dimensional inputs. It first forms $QW_i^Q$, $KW_i^K$, and $VW_i^V$ with its own learned projection matrices, then computes $head_i=Attention(QW_i^Q,KW_i^K,VW_i^V)$. This gives every head a separately projected view of the same underlying query, key, and value inputs.

Each head produces a $d_v$-dimensional output. Multi-head attention concatenates $head_1$ through $head_h$ and applies the output projection $W^O$. The base Transformer uses eight heads with $d_k=d_v=64$, one eighth of $d_{model}=512$. The lower dimension per head keeps the combined computational cost similar to that of a single attention operation at full model dimensionality.

The paper’s reason for using several heads is that they can attend to information from different representation subspaces and positions. A head is therefore a computational component, not a separate architectural attention type: heads are used inside encoder self-attention, masked decoder self-attention, and encoder-decoder attention. Those three applications differ in the origin of their queries, keys, and values, while the internal head computation remains scaled dot-product attention.

The experimental variation of head count shows that one head gives 24.9 development BLEU, compared with the best listed 25.8, while thirty-two small heads fall to 25.4. The paper’s appendix shows why head-specific outputs are also interesting to inspect. Different colored heads connect “making” with “more difficult”; heads 5 and 6 show sharp attention from “its” that the authors describe as apparently involved in anaphora resolution; and two other heads produce visibly different patterns that seem related to sentence structure. The authors use these examples to support the observation that individual heads learn different tasks.

<!-- grounded-in
- [2 Background](../../source/02-background.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2 Attention](../../source/03-model-architecture/03-02-attention/overview.md)
- [3.2.2 Multi-Head Attention](../../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
- [Attention Visualizations](../../source/attention-visualizations.md)
-->
