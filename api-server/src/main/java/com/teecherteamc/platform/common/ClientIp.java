package com.teecherteamc.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 요청한 클라이언트의 IP. 감사 로그(audit_logs.ip_address)에 남기는 값이다.
 *
 * <p>api-server는 nginx 뒤에 있어서 remoteAddr이 nginx 주소다. 그래서 X-Forwarded-For의 첫 주소를 쓴다.
 * 이 값은 클라이언트가 보낸 헤더를 nginx가 덮어쓴다는 전제에서만 믿을 수 있다
 * (infra/nginx/nginx.conf의 {@code proxy_set_header X-Forwarded-For $remote_addr}).
 */
public final class ClientIp {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private ClientIp() {
    }

    /** X-Forwarded-For의 첫 주소, 없거나 IP 형식이 아니면 remoteAddr. 둘 다 아니면 null. */
    public static String resolve(HttpServletRequest request) {
        String forwardedFor = request.getHeader(FORWARDED_FOR);
        if (forwardedFor != null) {
            String first = forwardedFor.split(",", 2)[0].trim();
            if (parseLiteral(first).isPresent()) {
                return first;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return parseLiteral(remoteAddr).isPresent() ? remoteAddr : null;
    }

    /**
     * IP 리터럴만 InetAddress로 바꾼다. InetAddress.getByName에 호스트 이름이 들어가면 DNS를 조회하므로,
     * 헤더처럼 외부에서 온 값은 형식을 먼저 확인한다. ':'가 들어간 문자열은 IPv6 리터럴로만 해석된다.
     */
    public static Optional<InetAddress> parseLiteral(String value) {
        if (value == null || !(IPV4.matcher(value).matches() || isIpv6Like(value))) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(value));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    private static boolean isIpv6Like(String value) {
        return value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
    }
}
