package tech.xingyunqi.template.modules.addresses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.modules.addresses.domain.Address;
import tech.xingyunqi.template.modules.addresses.domain.AddressFilter;
import tech.xingyunqi.template.modules.addresses.domain.RegionData;
import tech.xingyunqi.template.modules.regions.RegionsService;
import tech.xingyunqi.template.modules.regions.domain.Region;

class AddressesServiceTest {

    final AddressRepository repository = mock(AddressRepository.class);
    final RegionsService regions = mock(RegionsService.class);
    final AddressesService service = new AddressesService(repository, regions);

    @BeforeEach
    void stubRegions() {
        when(regions.validateRegionCombination(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(regions.validateRegionCombination("110000000000", "c", "d"))
                .thenReturn(Optional.of(List.of(region("110000000000", "P"), region("c", "C"), region("d", "D"))));
    }

    static Region region(String code, String name) {
        return new Region(1, code, name, 1, null, 0, Instant.EPOCH, Instant.EPOCH);
    }

    static Address address(int id, boolean isDefault) {
        return new Address(
                id, 7, "n", "p", null, null, null, "P", "C", "D", "a", isDefault, Instant.EPOCH, Instant.EPOCH, null);
    }

    static Map<String, @Nullable Object> values(@Nullable Object... pairs) {
        Map<String, @Nullable Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static int status(Runnable call) {
        try {
            call.run();
        } catch (HttpStatusException e) {
            return e.status();
        }
        return 200;
    }

    @Test
    void namesRequiredWithoutCodes() {
        assertThat(service.regionData(values("province", "p", "city", "c", "district", "d")))
                .isEqualTo(new RegionData(null, null, null, "p", "c", "d"));
        assertThatThrownBy(() -> service.regionData(values("province", "p", "city", "", "district", "d")))
                .isInstanceOf(HttpStatusException.class)
                .hasMessageContaining("missingRegionNames");
    }

    @Test
    void codesMustBeCompleteAndValid() {
        assertThat(status(() -> service.regionData(values("provinceCode", null))))
                .isEqualTo(400);
        assertThat(service.regionData(
                        values("provinceCode", "110000000000", "cityCode", "c", "districtCode", "d", "province", "x")))
                .isEqualTo(new RegionData("110000000000", "c", "d", "P", "C", "D"));
        assertThatThrownBy(() -> service.regionData(values("provinceCode", "x", "cityCode", "c", "districtCode", "d")))
                .hasMessageContaining("invalidRegionCombination");
    }

    @Test
    void sortItemsComeFirstAndDefaultsFillTheRest() {
        assertThat(AddressesService.order(List.of())).isEqualTo(AddressesService.DEFAULT_ORDER);
        assertThat(AddressesService.order(List.of(
                        new AddressRepository.Order("createdAt", false),
                        new AddressRepository.Order("name", true),
                        new AddressRepository.Order("createdAt", true))))
                .containsExactly(
                        new AddressRepository.Order("createdAt", true),
                        new AddressRepository.Order("name", true),
                        new AddressRepository.Order("isDefault", true));
    }

    @Test
    @DisplayName("更新为默认地址时先取用户锁再更新本行与默认标记")
    void updateToDefaultLocksFirst() {
        when(repository.findByIdAndUser(3, 7)).thenReturn(Optional.of(address(3, false)));
        when(repository.update(eq(3), any())).thenReturn(Optional.of(address(3, false)));

        assertThat(service.update(3, 7, values("name", "x", "isDefault", true)).isDefault())
                .isTrue();

        InOrder order = inOrder(repository);
        order.verify(repository).lockUser(7);
        order.verify(repository).update(eq(3), any());
        order.verify(repository).markDefault(3, 7);
    }

    @Test
    @DisplayName("不设为默认的更新不取用户锁")
    void updateWithoutDefaultDoesNotLock() {
        when(repository.findByIdAndUser(3, 7)).thenReturn(Optional.of(address(3, true)));
        when(repository.update(eq(3), any())).thenReturn(Optional.of(address(3, false)));

        service.update(3, 7, values("isDefault", false));

        verify(repository, never()).lockUser(anyInt());
        verify(repository, never()).markDefault(anyInt(), anyInt());
    }

    @Test
    @DisplayName("删除地址时先取用户锁再删除本行并转移默认地址")
    void removeLocksFirst() {
        when(repository.findByIdAndUser(3, 7)).thenReturn(Optional.of(address(3, true)));
        when(repository.findMany(eq(7), eq(AddressFilter.none()), any(), anyLong(), anyLong()))
                .thenReturn(List.of(address(4, false)));

        service.remove(3, 7);

        InOrder order = inOrder(repository);
        order.verify(repository).lockUser(7);
        order.verify(repository).softDelete(3);
        order.verify(repository).markDefault(4, 7);
    }
}
