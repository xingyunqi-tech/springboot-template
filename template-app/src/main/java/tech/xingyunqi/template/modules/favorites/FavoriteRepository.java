package tech.xingyunqi.template.modules.favorites;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.favorites.domain.Favorite;

public interface FavoriteRepository {

    record NewFavorite(
            int userId,
            String targetType,
            String targetId,
            @Nullable String title,
            @Nullable String image,
            @Nullable String extra,
            @Nullable String subjectId) {}

    Favorite create(NewFavorite data);

    /** 按创建时间倒序。 */
    List<Favorite> findMany(
            int userId, @Nullable String targetType, @Nullable String subjectId, long offset, long limit);

    long count(int userId, @Nullable String targetType, @Nullable String subjectId);

    Optional<Favorite> findById(int id);

    Optional<Favorite> findByUserAndTarget(int userId, String targetType, String targetId);

    void remove(int id);

    void removeByUser(int userId);
}
