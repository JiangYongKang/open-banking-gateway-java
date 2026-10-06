package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;

/**
 * 同意书"能看 / 能改"分层策略：
 * <ul>
 *   <li>CONSENTS_MANAGE：可变更（创建/授权/暂停/恢复/撤销），隐含读取；</li>
 *   <li>仅 CONSENTS_READ：只读（列表/详情），任何变更一律拒绝；</li>
 *   <li>两者都不含：分层前签发的旧令牌，维持"认证即可管理"的兼容行为。</li>
 * </ul>
 */
public final class ConsentAccessPolicy {

    private ConsentAccessPolicy() {
    }

    public static boolean canManage(CallerContext caller) {
        if (caller.hasScope(Scope.CONSENTS_MANAGE)) {
            return true;
        }
        // 旧令牌兼容：未携带任何同意书分层 scope 时按原行为放行
        return !caller.hasScope(Scope.CONSENTS_READ);
    }

    public static boolean canRead(CallerContext caller) {
        return canManage(caller) || caller.hasScope(Scope.CONSENTS_READ);
    }
}
