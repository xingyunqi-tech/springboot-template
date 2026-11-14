package tech.xingyunqi.template.common.web.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 客户端 IP 与 User-Agent（截断到 {@value #USER_AGENT_MAX_LENGTH} 字符）。 */
public record ClientInfo(@Nullable String ip, @Nullable String userAgent) {

    public static final int USER_AGENT_MAX_LENGTH = 512;

    public static ClientInfo of(HttpServletRequest request, int trustProxy) {
        String agent = request.getHeader("User-Agent");
        return new ClientInfo(
                clientIp(request, trustProxy),
                agent == null || agent.length() <= USER_AGENT_MAX_LENGTH
                        ? agent
                        : agent.substring(0, USER_AGENT_MAX_LENGTH));
    }

    /** Express {@code trust proxy} 为跳数 N 时的 {@code req.ip}：从直连地址起沿 {@code X-Forwarded-For} 回溯 N 跳。 */
    public static @Nullable String clientIp(HttpServletRequest request, int trustProxy) {
        List<String> chain = new ArrayList<>();
        String remote = request.getRemoteAddr();
        if (remote != null && !remote.isEmpty()) {
            chain.add(remote);
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (trustProxy > 0 && forwarded != null) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].strip();
                if (!part.isEmpty()) {
                    chain.add(part);
                }
            }
        }
        if (chain.isEmpty()) {
            return null;
        }
        return chain.get(Math.min(trustProxy, chain.size() - 1));
    }
}
