# Attention Is All You Need — 학습용 픽스처

## 1. Introduction

Recurrent 모델은 시퀀스를 순차 처리하므로 병렬화가 어렵다. 본 논문은 attention만으로
시퀀스 변환을 수행하는 Transformer를 제안한다. 시퀀스 길이 $n$, 표현 차원 $d$에서
self-attention 층의 복잡도는 $O(n^2 \cdot d)$이다.

The dominant sequence transduction models are based on complex recurrent or
convolutional neural networks. We propose a new simple network architecture.

## 2. Model Architecture

### 2.1 Scaled Dot-Product Attention

query·key·value 행렬 $Q, K, V$에 대해 attention은 다음과 같이 정의된다.

$$
\mathrm{Attention}(Q, K, V) = \mathrm{softmax}\!\left(\frac{QK^{\top}}{\sqrt{d_k}}\right)V
$$

$\sqrt{d_k}$로 나누는 이유는 내적 값이 커질수록 softmax의 gradient가 소실되기 때문이다.

### 2.2 Multi-Head Attention

$$
\mathrm{MultiHead}(Q, K, V) = \mathrm{Concat}(\mathrm{head}_1, \ldots, \mathrm{head}_h)W^{O}
$$

![Figure 1: Transformer 아키텍처 — encoder-decoder 구조와 multi-head attention의 배치](/fixtures/figure-attention.svg)

## 3. Results

| Model | BLEU (EN-DE) | Training cost (FLOPs) |
| --- | --- | --- |
| ByteNet | 23.75 | — |
| ConvS2S | 25.16 | $9.6 \times 10^{18}$ |
| Transformer (big) | **28.4** | $2.3 \times 10^{19}$ |

Transformer는 더 적은 학습 비용으로 기존 최고 성능을 넘었다.
