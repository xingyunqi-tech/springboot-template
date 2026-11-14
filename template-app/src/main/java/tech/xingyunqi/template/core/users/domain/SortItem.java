package tech.xingyunqi.template.core.users.domain;

/** 列表排序项：nestjs 实体字段名与方向（{@code ASC} / {@code DESC}）。 */
public record SortItem(String orderBy, String order) {}
