package com.github.highcumontoa.openbankinggatewayjava.domain;

/**
 * 合作方授权范围（粗粒度 capability）：
 * ACCOUNTS_READ    账户/余额查询
 * PAYMENTS_INITIATE 发起支付
 * CONSENTS_READ    同意书只读（列表/详情），不含任何状态变更
 * CONSENTS_MANAGE  同意书管理（创建/授权/暂停/恢复/撤销），隐含读取
 *
 * 兼容语义：令牌若两者都不含（分层前签发的旧令牌），维持旧的"认证即可管理"行为。
 */
public enum Scope {
    ACCOUNTS_READ,
    PAYMENTS_INITIATE,
    CONSENTS_READ,
    CONSENTS_MANAGE
}
