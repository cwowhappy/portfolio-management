package com.portfolio.invest.domain.research;

/** 单阶段完成度读模型：三态（D22）+ 完成方式依据（AUTO/MANUAL/PENDING）。 */
public record StageCompletion(ResearchStage stage, StageStatus status, CompletionBasis basis) {}
