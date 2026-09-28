package com.portfolio.invest.domain.research;

/** 研究项目生命周期状态（S1：仅 ACTIVE/ARCHIVED 两态，归档非删除）。 */
public enum ProjectStatus {
    ACTIVE("研究中"),
    ARCHIVED("已归档");

    private final String label;

    ProjectStatus(String label) { this.label = label; }

    public String label() { return label; }
}
