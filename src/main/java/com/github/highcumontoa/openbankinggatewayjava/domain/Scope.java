package com.github.highcumontoa.openbankinggatewayjava.domain;

/**
 * 合作方授权范围（粗粒度 capability）：
 * ACCOUNTS_READ  账户/余额查询
 * PAYMENTS_INITIATE 发起支付
 */
public enum Scope {
    ACCOUNTS_READ,
    PAYMENTS_INITIATE
}
