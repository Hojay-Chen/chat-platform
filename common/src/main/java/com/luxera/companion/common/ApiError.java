package com.luxera.companion.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 统一错误响应体(与 blog-platform 一致): 成功直接返回 DTO,失败返回 { error, hint } */
@Data
@AllArgsConstructor
public class ApiError {
    private String error;
    private String hint;
}
