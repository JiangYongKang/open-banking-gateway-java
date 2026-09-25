package com.github.highcumontoa.openbankinggatewayjava.error;

/**
 * 对外统一错误响应。reason 为稳定、可区分但不泄漏归属的判定依据短语。
 */
public class ApiError {

    private String code;
    private String reason;
    private String message;

    public ApiError() {
    }

    public ApiError(String code, String reason, String message) {
        this.code = code;
        this.reason = reason;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }

    public String getMessage() {
        return message;
    }
}
