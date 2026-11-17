package tech.xingyunqi.template.core.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.xingyunqi.template.common.mybatis.WriteTransactions;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.core.session.SessionService;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupProducer;
import tech.xingyunqi.template.infrastructure.files.FileReferences;

class UsersServiceTest {

    final UserRepository repository = mock(UserRepository.class);
    final SessionService sessions = mock(SessionService.class);
    final UsersService service = new UsersService(
            repository,
            mock(FileReferences.class),
            sessions,
            new PasswordHasher(PasswordHasher.Algorithm.BCRYPT, 1),
            new WriteTransactions(),
            mock(AccountCleanupProducer.class));

    @Test
    @DisplayName("setPassword 写入新密码哈希并删除该用户全部会话")
    @SuppressWarnings("unchecked")
    void setPasswordRevokesSessions() {
        when(repository.update(eq(7), anyMap())).thenReturn(1);

        service.setPassword(7, "e10adc3949ba59abbe56e057f20f883e");

        ArgumentCaptor<Map<String, Object>> changes = ArgumentCaptor.forClass(Map.class);
        verify(repository).update(eq(7), changes.capture());
        assertThat(changes.getValue()).containsOnlyKeys("password");
        assertThat((String) changes.getValue().get("password")).startsWith("$2");
        verify(sessions).deleteByUser(7, null);
    }

    @Test
    @DisplayName("setPassword 更新 0 行时不报错且不删除会话")
    void setPasswordIgnoresMissingUser() {
        when(repository.update(eq(7), anyMap())).thenReturn(0);

        service.setPassword(7, "e10adc3949ba59abbe56e057f20f883e");

        verify(sessions, never()).deleteByUser(anyInt(), isNull());
    }
}
