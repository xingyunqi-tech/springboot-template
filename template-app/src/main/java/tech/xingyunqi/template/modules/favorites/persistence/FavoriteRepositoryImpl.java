package tech.xingyunqi.template.modules.favorites.persistence;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.modules.favorites.FavoriteRepository;
import tech.xingyunqi.template.modules.favorites.domain.Favorite;

@Repository
class FavoriteRepositoryImpl implements FavoriteRepository {

    private final FavoriteMapper mapper;

    FavoriteRepositoryImpl(FavoriteMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Favorite create(NewFavorite data) {
        return toDomain(mapper.insert(
                data.userId(),
                data.targetType(),
                data.targetId(),
                data.title(),
                data.image(),
                data.extra(),
                data.subjectId()));
    }

    @Override
    public List<Favorite> findMany(
            int userId, @Nullable String targetType, @Nullable String subjectId, long offset, long limit) {
        return mapper.findMany(userId, targetType, subjectId, offset, limit).stream()
                .map(FavoriteRepositoryImpl::toDomain)
                .toList();
    }

    @Override
    public long count(int userId, @Nullable String targetType, @Nullable String subjectId) {
        return mapper.count(userId, targetType, subjectId);
    }

    @Override
    public Optional<Favorite> findById(int id) {
        return Optional.ofNullable(mapper.findById(id)).map(FavoriteRepositoryImpl::toDomain);
    }

    @Override
    public Optional<Favorite> findByUserAndTarget(int userId, String targetType, String targetId) {
        return Optional.ofNullable(mapper.findByUserAndTarget(userId, targetType, targetId))
                .map(FavoriteRepositoryImpl::toDomain);
    }

    @Override
    public void remove(int id) {
        mapper.delete(id);
    }

    @Override
    public void removeByUser(int userId) {
        mapper.deleteByUser(userId);
    }

    static Favorite toDomain(FavoriteMapper.Row row) {
        return new Favorite(
                row.id(),
                row.userId(),
                row.targetType(),
                row.targetId(),
                row.title(),
                row.image(),
                row.extra(),
                row.subjectId(),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
