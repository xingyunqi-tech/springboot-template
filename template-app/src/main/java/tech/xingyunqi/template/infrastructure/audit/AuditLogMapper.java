package tech.xingyunqi.template.infrastructure.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.jspecify.annotations.Nullable;

@Mapper
public interface AuditLogMapper {

    /** {@code audit_logs} 插入参数；JSON 列为已序列化的文本，null 写入 SQL NULL。 */
    record NewAuditLog(
            @Nullable String userId,
            String action,
            String entityType,
            String entityId,
            @Nullable String oldValue,
            @Nullable String newValue,
            @Nullable String ipAddress,
            @Nullable String userAgent,
            @Nullable String requestId) {}

    @Insert("insert into audit_logs (\"userId\", action, \"entityType\", \"entityId\", \"oldValue\", \"newValue\","
            + " \"ipAddress\", \"userAgent\", \"requestId\") values (#{userId}, #{action}, #{entityType}, #{entityId},"
            + " cast(#{oldValue} as jsonb), cast(#{newValue} as jsonb), #{ipAddress}, #{userAgent}, #{requestId})")
    void insert(NewAuditLog log);
}
