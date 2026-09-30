package com.ke.domain.card.content;

/** content_json 非法（JSON 不可解析、模板类型未知或约束违反）时抛出。 */
public class InvalidCardContentException extends RuntimeException {
    public InvalidCardContentException(String message) {
        super(message);
    }
}
