package tech.xingyunqi.template.modules.favorites;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.BusinessException;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.modules.favorites.domain.Favorite;
import tech.xingyunqi.template.modules.favorites.domain.FavoriteTargets;

/** 收藏（nestjs FavoritesService + FavoriteTargetsService）；题目、试卷目标按目标不存在处理。 */
@Service
public class FavoritesService {

    static final int MAX_PAGE_SIZE = 50;

    private static final Logger LOG = LoggerFactory.getLogger(FavoritesService.class);

    /** 收藏请求：目标与可选的标题快照、图片、附加数据。 */
    public record Target(
            String targetType,
            String targetId,
            @Nullable String title,
            @Nullable String image,
            @Nullable String extra) {}

    /** 列表项：题目、试卷附带下架状态，其它类型为 null。 */
    public record Item(Favorite favorite, @Nullable Boolean targetOffline) {}

    public record Page(List<Item> items, long total) {}

    public record Toggled(boolean favorited, @Nullable Favorite favorite) {}

    private final FavoriteRepository repository;

    public FavoritesService(FavoriteRepository repository) {
        this.repository = repository;
    }

    @WriteTransaction
    public Favorite create(int userId, Target target) {
        FavoriteRepository.NewFavorite values = resolve(userId, target);
        LOG.info("Creating favorite for user: {}, target: {}:{}", userId, values.targetType(), values.targetId());
        if (findByTarget(userId, values.targetType(), values.targetId()).isPresent()) {
            LOG.warn("Already favorited: {}:{}", values.targetType(), values.targetId());
            throw HttpStatusException.conflict(Map.of("error", "alreadyFavorited"));
        }
        Favorite favorite = repository.create(values);
        LOG.info("Favorite created: {}", favorite.id());
        return favorite;
    }

    @WriteTransaction
    public Toggled toggle(int userId, Target target) {
        Optional<Favorite> existing = findByTarget(userId, target.targetType(), target.targetId());
        if (existing.isPresent()) {
            remove(existing.get().id(), userId);
            return new Toggled(false, null);
        }
        return new Toggled(true, create(userId, target));
    }

    public Optional<Favorite> findByTarget(int userId, String targetType, String targetId) {
        return repository.findByUserAndTarget(
                userId, targetType, FavoriteTargets.normalizeTargetId(targetType, targetId));
    }

    @WriteTransaction
    public void remove(int id, int userId) {
        LOG.info("Removing favorite: {} for user: {}", id, userId);
        Optional<Favorite> favorite = repository.findById(id);
        if (favorite.isEmpty() || favorite.get().userId() != userId) {
            LOG.warn("Favorite {} not found or not owned by user {}", id, userId);
            throw HttpStatusException.notFound(Map.of("error", "favoriteNotFound"));
        }
        repository.remove(id);
        LOG.info("Favorite removed: {}", id);
    }

    public Page findPage(int userId, @Nullable String targetType, @Nullable String subjectId, long page, long limit) {
        List<Favorite> data = repository.findMany(userId, targetType, subjectId, (page - 1) * limit, limit);
        long total = repository.count(userId, targetType, subjectId);
        List<Item> items = data.stream()
                .map(favorite -> new Item(
                        favorite, FavoriteTargets.EXAM_TYPES.contains(favorite.targetType()) ? Boolean.TRUE : null))
                .toList();
        return new Page(items, total);
    }

    @WriteTransaction
    public void removeByUser(int userId) {
        repository.removeByUser(userId);
    }

    private static FavoriteRepository.NewFavorite resolve(int userId, Target target) {
        if (FavoriteTargets.QUESTION.equals(target.targetType())) {
            throw new BusinessException("questionNotAccessible");
        }
        if (FavoriteTargets.PAPER.equals(target.targetType())) {
            throw HttpStatusException.notFound(Map.of("error", "paperNotFound"));
        }
        return new FavoriteRepository.NewFavorite(
                userId,
                target.targetType(),
                FavoriteTargets.normalizeTargetId(target.targetType(), target.targetId()),
                target.title(),
                target.image(),
                target.extra(),
                null);
    }
}
