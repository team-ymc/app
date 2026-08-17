---
type: Table
title: Translation Quality and Cost
---

## Translation Quality and Cost

<table><tr><td rowspan="2">Model</td><td colspan="2">BLEU</td><td colspan="2">Training Cost (FLOPs)</td></tr><tr><td>EN-DE</td><td>EN-FR</td><td>EN-DE</td><td>EN-FR</td></tr><tr><td>ByteNet [18]</td><td>23.75</td><td></td><td></td><td></td></tr><tr><td>Deep-Att + PosUnk [39]</td><td></td><td>39.2</td><td></td><td>$ 1.0 \cdot 10^{20} $</td></tr><tr><td>GNMT + RL [38]</td><td>24.6</td><td>39.92</td><td>$ 2.3 \cdot 10^{19} $</td><td>$ 1.4 \cdot 10^{20} $</td></tr><tr><td>ConvS2S [9]</td><td>25.16</td><td>40.46</td><td>$ 9.6 \cdot 10^{18} $</td><td>$ 1.5 \cdot 10^{20} $</td></tr><tr><td>MoE [32]</td><td>26.03</td><td>40.56</td><td>$ 2.0 \cdot 10^{19} $</td><td>$ 1.2 \cdot 10^{20} $</td></tr><tr><td>Deep-Att + PosUnk Ensemble [39]</td><td></td><td>40.4</td><td></td><td>$ 8.0 \cdot 10^{20} $</td></tr><tr><td>GNMT + RL Ensemble [38]</td><td>26.30</td><td>41.16</td><td>$ 1.8 \cdot 10^{20} $</td><td>$ 1.1 \cdot 10^{21} $</td></tr><tr><td>ConvS2S Ensemble [9]</td><td>26.36</td><td>41.29</td><td>$ 7.7 \cdot 10^{19} $</td><td>$ 1.2 \cdot 10^{21} $</td></tr><tr><td>Transformer (base model)</td><td>27.3</td><td>38.1</td><td colspan="2">$ 3.3 \cdot 10^{18} $</td></tr><tr><td>Transformer (big)</td><td>28.4</td><td>41.8</td><td colspan="2">$ 2.3 \cdot 10^{19} $</td></tr></table>

## Description

This table compares translation quality and estimated training cost for the Transformer and previously published systems on the WMT 2014 newstest2014 English-to-German (EN-DE) and English-to-French (EN-FR) test sets. Quality is reported as BLEU, while training cost is reported as estimated floating-point operations. The comparison includes individual models and ensembles; empty cells indicate values not supplied in the table.

The Transformer base model reports 27.3 EN-DE BLEU and 38.1 EN-FR BLEU with a listed training cost of $3.3\cdot10^{18}$ FLOPs. Transformer (big) reports 28.4 EN-DE and 41.8 EN-FR with $2.3\cdot10^{19}$ FLOPs. For EN-DE, the strongest listed earlier ensemble reaches 26.36 BLEU, below both Transformer configurations. For EN-FR, Transformer (big) exceeds the listed previous results, whose highest value is 41.29 for the ConvS2S ensemble.

The table supports the paper’s claim that the Transformer improves BLEU while using substantially less training computation than the competitive systems shown. The accompanying section states that the big EN-DE model surpasses previously reported models, including ensembles, by more than 2 BLEU, while even the base model exceeds the earlier published results at a fraction of their training cost.

<!-- cited-in
- [6.1 Machine Translation](../source/06-results/06-01-machine-translation.md)
-->
