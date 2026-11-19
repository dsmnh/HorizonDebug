package dev.dsmnh.horizon.debug.api;

/**
 * 调试 API 的业务异常：携带响应里的 {@code code} 字段（BAD_ARGS / NOT_FOUND / LIMIT / ...）。
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
