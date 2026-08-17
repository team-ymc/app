---
type: Table
title: Constituency Parsing Results
---

## Constituency Parsing Results

<table><tr><td>Parser</td><td>Training</td><td>WSJ 23 F1</td></tr><tr><td>Vinyals &amp; Kaiser et al. (2014) [37]</td><td>WSJ only, discriminative</td><td>88.3</td></tr><tr><td>Petrov et al. (2006) [29]</td><td>WSJ only, discriminative</td><td>90.4</td></tr><tr><td>Zhu et al. (2013) [40]</td><td>WSJ only, discriminative</td><td>90.4</td></tr><tr><td>Dyer et al. (2016) [8]</td><td>WSJ only, discriminative</td><td>91.7</td></tr><tr><td>Transformer (4 layers)</td><td>WSJ only, discriminative</td><td>91.3</td></tr><tr><td>Zhu et al. (2013) [40]</td><td>semi-supervised</td><td>91.3</td></tr><tr><td>Huang &amp; Harper (2009) [14]</td><td>semi-supervised</td><td>91.3</td></tr><tr><td>McClosky et al. (2006) [26]</td><td>semi-supervised</td><td>92.1</td></tr><tr><td>Vinyals &amp; Kaiser et al. (2014) [37]</td><td>semi-supervised</td><td>92.1</td></tr><tr><td>Transformer (4 layers)</td><td>semi-supervised</td><td>92.7</td></tr><tr><td>Luong et al. (2015) [23]</td><td>multi-task</td><td>93.0</td></tr><tr><td>Dyer et al. (2016) [8]</td><td>generative</td><td>93.3</td></tr></table>

## Description

This table compares English constituency parsers on Section 23 of the Wall Street Journal portion of the Penn Treebank. Each row identifies a parser, its training regime, and its WSJ Section 23 F1 score. The rows separate systems trained only on WSJ data from semi-supervised, multi-task, and generative systems, so the score should be read together with the training column.

The paper evaluates a four-layer Transformer with $d_{model}=1024$. With WSJ-only discriminative training on about 40,000 sentences, it reaches 91.3 F1. That is higher than the listed Petrov et al. parser at 90.4 and below the listed Dyer et al. result at 91.7 within the WSJ-only group. With semi-supervised training using corpora totaling approximately 17 million sentences, the Transformer reaches 92.7 F1, above the other semi-supervised results in the table, which range from 91.3 to 92.1. The remaining comparison rows report 93.0 for the Luong et al. multi-task system and 93.3 for the Dyer et al. generative system.

The table is used to show that the Transformer transfers beyond machine translation to a task whose outputs have strong structural constraints and are substantially longer than its inputs. The authors emphasize that these results were obtained with limited task-specific tuning.

<!-- cited-in
- [6.3 English Constituency Parsing](../source/06-results/06-03-english-constituency-parsing.md)
-->
