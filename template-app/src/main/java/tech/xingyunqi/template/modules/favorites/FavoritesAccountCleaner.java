package tech.xingyunqi.template.modules.favorites;

import org.springframework.stereotype.Component;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;

/** 删除该用户的全部收藏。 */
@Component
class FavoritesAccountCleaner implements AccountDataCleaner {

    static final int ORDER = 70;

    private final FavoritesService favorites;

    FavoritesAccountCleaner(FavoritesService favorites) {
        this.favorites = favorites;
    }

    @Override
    public String name() {
        return "favorites";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void clean(int userId) {
        favorites.removeByUser(userId);
    }
}
