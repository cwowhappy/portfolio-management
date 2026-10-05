package com.portfolio.invest.agent.trust;

import java.util.Map;

/**
 * 单数字锚定：payload v1 单条 anchor 的内存形态（设计规格 §2.1）。
 * tool/args/asOf/asOfKind 在 sourced/verified 态必有、unverified 态为 null；
 * raw 为工具返回原值（verified 必有，供人工核对）。
 */
public record AnchorRecord(
        String snippet,
        int occ,
        TrustVerdict state,
        String tool,
        Map<String, Object> args,
        String asOf,
        ToolInvocation.AsOfKind asOfKind,
        String raw) {}
