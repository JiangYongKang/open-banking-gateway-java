package com.github.highcumontoa.openbankinggatewayjava.domain;

/**
 * 合作方授权范围（粗粒度 capability）：
 * ACCOUNTS_READ  账户/余额查询
 * PAYMENTS_INITIATE 发起支付
 * CONSENTS_READ  同意书只读（列表/详情），不能变更状态
 * CONSENTS_MANAGE 同意书管理（创建/授权/暂停/恢复/撤销），隐含只读能力
 *
 * 兼容性：不含任何 CONSENTS_* 范围的令牌视为"分层前"的老调用方，
 * 保持既有能力（可管理同意书），不被新分层挡住。
 */
public enum Scope {
    ACCOUNTS_READ,
    PAYMENTS_INITIATE,
    CONSENTS_READ,
    CONSENTS_MANAGE
}
