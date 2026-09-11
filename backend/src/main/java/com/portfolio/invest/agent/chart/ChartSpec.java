package com.portfolio.invest.agent.chart;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 与前端 lib/chart-spec.ts 对齐的判别联合（2026-09-11 澄清：单一契约，table 是变体之一）。
 * 显式携带 specVersion/type 组件（勿依赖 Jackson 类名推断）；每个 record 标 @JsonInclude(NON_NULL)：
 * wire 契约默认 ALWAYS 会把 null 字段发出去，而前端 zod .optional() 不接受 null。
 */
public sealed interface ChartSpec {
  int specVersion();
  String type();
  String title();
  String subtitle();

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Line(int specVersion, String type, String title, String subtitle,
              List<String> categories, List<Series> series, String unit) implements ChartSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Series(String name, List<Double> data, Boolean area) {}   // data 允许 null 元素（缺口）

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Bar(int specVersion, String type, String title, String subtitle,
             List<String> categories, List<Series> series, Boolean horizontal, String unit)
          implements ChartSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Pie(int specVersion, String type, String title, String subtitle,
             List<Slice> data, String unit) implements ChartSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Slice(String name, Double value) {}

  /** klines 序列化为 4 元数值数组 [开,收,低,高]（前端 zod tuple，非对象）。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Candlestick(int specVersion, String type, String title, String subtitle,
                     String symbol, String period, List<String> dates,
                     List<double[]> klines, List<Long> volumes, List<MaLine> mas)
          implements ChartSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record MaLine(String name, List<Double> data) {}                 // 预热期为 null 元素

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Table(int specVersion, String type, String title, String subtitle,
               List<Column> columns, List<Map<String, Object>> rows, Integer defaultPageSize)
          implements ChartSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Column(String key, String label, String align, Boolean sortable) {}
}
