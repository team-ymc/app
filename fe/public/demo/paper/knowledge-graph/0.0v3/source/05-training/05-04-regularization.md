---
type: Paper Section
title: 5.4 Regularization
---

### 5.4 Regularization

We employ three types of regularization during training:

<figure_title>Table 2: The Transformer achieves better BLEU scores than previous state-of-the-art models on the English-to-German and English-to-French newstest2014 tests at a fraction of the training cost.</figure_title>

<table data-asset-key="table_1"><tr><td rowspan="2">Model</td><td colspan="2">BLEU</td><td colspan="2">Training Cost (FLOPs)</td></tr><tr><td>EN-DE</td><td>EN-FR</td><td>EN-DE</td><td>EN-FR</td></tr><tr><td>ByteNet [18]</td><td>23.75</td><td></td><td></td><td></td></tr><tr><td>Deep-Att + PosUnk [39]</td><td></td><td>39.2</td><td></td><td>$ 1.0 \cdot 10^{20} $</td></tr><tr><td>GNMT + RL [38]</td><td>24.6</td><td>39.92</td><td>$ 2.3 \cdot 10^{19} $</td><td>$ 1.4 \cdot 10^{20} $</td></tr><tr><td>ConvS2S [9]</td><td>25.16</td><td>40.46</td><td>$ 9.6 \cdot 10^{18} $</td><td>$ 1.5 \cdot 10^{20} $</td></tr><tr><td>MoE [32]</td><td>26.03</td><td>40.56</td><td>$ 2.0 \cdot 10^{19} $</td><td>$ 1.2 \cdot 10^{20} $</td></tr><tr><td>Deep-Att + PosUnk Ensemble [39]</td><td></td><td>40.4</td><td></td><td>$ 8.0 \cdot 10^{20} $</td></tr><tr><td>GNMT + RL Ensemble [38]</td><td>26.30</td><td>41.16</td><td>$ 1.8 \cdot 10^{20} $</td><td>$ 1.1 \cdot 10^{21} $</td></tr><tr><td>ConvS2S Ensemble [9]</td><td>26.36</td><td>41.29</td><td>$ 7.7 \cdot 10^{19} $</td><td>$ 1.2 \cdot 10^{21} $</td></tr><tr><td>Transformer (base model)</td><td>27.3</td><td>38.1</td><td colspan="2">$ 3.3 \cdot 10^{18} $</td></tr><tr><td>Transformer (big)</td><td>28.4</td><td>41.8</td><td colspan="2">$ 2.3 \cdot 10^{19} $</td></tr></table>

Residual Dropout We apply dropout [33] to the output of each sub-layer, before it is added to the sub-layer input and normalized. In addition, we apply dropout to the sums of the embeddings and the positional encodings in both the encoder and decoder stacks. For the base model, we use a rate of  $ P_{drop} = 0.1 $.

Label Smoothing During training, we employed label smoothing of value  $ \epsilon_{ls} = 0.1 $ [36]. This hurts perplexity, as the model learns to be more unsure, but improves accuracy and BLEU score.

<!-- followed-by
- [6 Results](../06-results/overview.md)
-->
