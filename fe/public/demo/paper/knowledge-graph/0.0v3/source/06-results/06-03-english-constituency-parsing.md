---
type: Paper Section
title: 6.3 English Constituency Parsing
---

### 6.3 English Constituency Parsing

To evaluate if the Transformer can generalize to other tasks we performed experiments on English constituency parsing. This task presents specific challenges: the output is subject to strong structural constraints and is significantly longer than the input. Furthermore, RNN sequence-to-sequence models have not been able to attain state-of-the-art results in small-data regimes [37].

We trained a 4-layer transformer with  $ d_{model} = 1024 $ on the Wall Street Journal (WSJ) portion of the Penn Treebank [25], about 40K training sentences. We also trained it in a semi-supervised setting, using the larger high-confidence and BerkleyParser corpora from with approximately 17M sentences [37]. We used a vocabulary of 16K tokens for the WSJ only setting and a vocabulary of 32K tokens for the semi-supervised setting.

We performed only a small number of experiments to select the dropout, both attention and residual (section 5.4), learning rates and beam size on the Section 22 development set, all other parameters remained unchanged from the English-to-German base translation model. During inference, we

<figure_title>Table 4: The Transformer generalizes well to English constituency parsing (Results are on Section 23 of WSJ)</figure_title>

<table data-asset-key="table_3"><tr><td>Parser</td><td>Training</td><td>WSJ 23 F1</td></tr><tr><td>Vinyals &amp; Kaiser et al. (2014) [37]</td><td>WSJ only, discriminative</td><td>88.3</td></tr><tr><td>Petrov et al. (2006) [29]</td><td>WSJ only, discriminative</td><td>90.4</td></tr><tr><td>Zhu et al. (2013) [40]</td><td>WSJ only, discriminative</td><td>90.4</td></tr><tr><td>Dyer et al. (2016) [8]</td><td>WSJ only, discriminative</td><td>91.7</td></tr><tr><td>Transformer (4 layers)</td><td>WSJ only, discriminative</td><td>91.3</td></tr><tr><td>Zhu et al. (2013) [40]</td><td>semi-supervised</td><td>91.3</td></tr><tr><td>Huang &amp; Harper (2009) [14]</td><td>semi-supervised</td><td>91.3</td></tr><tr><td>McClosky et al. (2006) [26]</td><td>semi-supervised</td><td>92.1</td></tr><tr><td>Vinyals &amp; Kaiser et al. (2014) [37]</td><td>semi-supervised</td><td>92.1</td></tr><tr><td>Transformer (4 layers)</td><td>semi-supervised</td><td>92.7</td></tr><tr><td>Luong et al. (2015) [23]</td><td>multi-task</td><td>93.0</td></tr><tr><td>Dyer et al. (2016) [8]</td><td>generative</td><td>93.3</td></tr></table>

increased the maximum output length to input length + 300. We used a beam size of 21 and  $ \alpha = 0.3 $ for both WSJ only and the semi-supervised setting.

Our results in Table 4 show that despite the lack of task-specific tuning our model performs surprisingly well, yielding better results than all previously reported models with the exception of the Recurrent Neural Network Grammar [8].

In contrast to RNN sequence-to-sequence models [37], the Transformer outperforms the Berkeley-Parser [29] even when training only on the WSJ training set of 40K sentences.

<!-- followed-by
- [7 Conclusion](../07-conclusion.md)
-->
