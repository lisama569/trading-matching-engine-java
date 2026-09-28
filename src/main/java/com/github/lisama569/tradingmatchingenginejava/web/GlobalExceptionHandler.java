package com.github.lisama569.tradingmatchingenginejava.web;

import com.github.lisama569.tradingmatchingenginejava.common.TradingException;
import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;
import com.github.lisama569.tradingmatchingenginejava.web.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口。
 * <ul>
 *     <li>业务拒绝：映射为 4xx + 结构化 {@link ApiError}，原因码稳定可区分；</li>
 *     <li>请求体解析失败：400 + INVALID_QTY（非法输入大类），不透传 Jackson 细节；</li>
 *     <li>其他任何未预期异常：仅返回 500/SYSTEM_ERROR，堆栈只进服务端日志。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TradingException.class)
    public ResponseEntity<ApiError> handleTrading(TradingException ex) {
        HttpStatus status = statusOf(ex.getCode());
        // 风控拒绝属于预期分支，打 info 即可；SYSTEM_ERROR 单独打 error。
        if (ex.getCode() == RejectCode.SYSTEM_ERROR) {
            log.error("业务处理失败: code={}, message={}", ex.getCode(), ex.getMessage(), ex);
        } else {
            log.info("业务拒绝: code={}, message={}", ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(status)
                .body(new ApiError(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.info("请求体无法解析: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiError(RejectCode.VALIDATION_FAILED.name(), "请求体格式错误或字段类型不正确"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("未预期的内部异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(RejectCode.SYSTEM_ERROR.name(), "系统繁忙，请稍后重试"));
    }

    private HttpStatus statusOf(RejectCode code) {
        return switch (code) {
            case ACCOUNT_NOT_FOUND, INSTRUMENT_NOT_FOUND, ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_SIDE, INVALID_ORDER_TYPE, INVALID_PRICE, INVALID_QTY,
                 VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case ORDER_NOT_ACTIVE -> HttpStatus.CONFLICT;
            case PRICE_OUT_OF_BAND, QTY_EXCEEDS_LIMIT, NOTIONAL_EXCEEDS_LIMIT,
                 INSUFFICIENT_CASH, INSUFFICIENT_POSITION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NO_LIQUIDITY -> HttpStatus.OK;
            case SYSTEM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
