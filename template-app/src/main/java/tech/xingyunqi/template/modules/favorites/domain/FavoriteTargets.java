package tech.xingyunqi.template.modules.favorites.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 收藏目标类型；题目与试卷的目标 id 为 UUID。 */
public final class FavoriteTargets {

    public static final String QUESTION = "question";
    public static final String PAPER = "paper";
    public static final Set<String> EXAM_TYPES = Set.of(QUESTION, PAPER);

    /** class-validator {@code isUUID(value)}（validator.js {@code all}）。 */
    private static final Pattern UUID = Pattern.compile(
            "^(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                    + "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$",
            Pattern.CASE_INSENSITIVE);

    private FavoriteTargets() {}

    public static boolean isUuid(String value) {
        return UUID.matcher(value).matches();
    }

    /** 题目与试卷的 UUID 转为小写。 */
    public static String normalizeTargetId(String targetType, String targetId) {
        return EXAM_TYPES.contains(targetType) && isUuid(targetId) ? targetId.toLowerCase(Locale.ROOT) : targetId;
    }
}
