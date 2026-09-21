# 产物自证与 banner 生效值

> [量程数字对照与产物自证](README.md) ｜ [量程不足会把配置掐死记成能力不足](../README.md) ｜ [工程方法](../../README.md) ｜ [总索引](../../../README.md)
> 来源：原条目「产物里长什么样：一条长文案 ≠ 一个可比较的数字」与「量程的生效值必须自己报出来（banner）」两节（逐字搬移，顺序与原文一致）。

## 产物里长什么样：一条长文案 ≠ 一个可比较的数字

`pilot-rerank.json` 里题 1 的 `degradeReason`（**逐字**，只此一段就说明白了三件事：模型调通了、预算是被推理吃掉的、正文一个字都没有）：

```text
重排模型返回了空正文（finish_reason=LENGTH，prompt 5250 tokens / completion 4096 tokens，
提示词 8710 字符）：已保留融合原序。⚠️ 最常见的原因是 max_tokens 被「推理 token」吃光——
本模型先思考再输出，预算耗尽时 content 为空而 finish_reason=length，
于是「没输出」与「没调通」在产物里长得一样。…
```

⚠️ **这段文字是修复后才有的**——`finish_reason` 与 token 用量原本**从不进产物**，那时
「**被预算截断**」与「**模型吐了非法 JSON**」在产物里**长得一模一样**。补齐这三样之后，
「量程类失败」才第一次成为**可核对**的失败。

## 量程的生效值必须自己报出来（banner）

CLI 自检的 banner 会打印**生效值**（而不是 `--help` 里的默认值）——这是「默认值也是结论的一部分」的落地：

```text
重排额度    ：maxTokens=8192（CANALMIND_RERANK_MAX_TOKENS 可覆盖，默认 8192；
             推理 token 与正文共享这个预算，故提示词越长 / 候选越多越容易写不出来）
重排超时    ：timeout=<生效秒数>s（CANALMIND_RERANK_TIMEOUT_SECONDS 可覆盖，默认 90s；
             超时即降级，且这一档不重试。…）
```
