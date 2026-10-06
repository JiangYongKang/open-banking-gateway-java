package com.github.highcumontoa.openbankinggatewayjava.web;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.api.ConsentResponse;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.security.CallerHolder;
import com.github.highcumontoa.openbankinggatewayjava.security.ConsentAccessPolicy;
import com.github.highcumontoa.openbankinggatewayjava.service.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 同意书生命周期与读取。
 * 读取（列表/详情）：需 CONSENTS_READ 或 CONSENTS_MANAGE（旧令牌兼容放行）；
 * 变更（创建/授权/暂停/恢复/撤销）：需 CONSENTS_MANAGE（旧令牌兼容放行），
 * 只读合作方一律 403 insufficient_scope。
 * 资源级操作仍受归属与状态机约束；跨合作方访问与不存在同构（404）。
 * 变更端点支持可选 expectedVersion 做乐观并发控制。
 */
@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @GetMapping
    public List<ConsentResponse> list(HttpServletRequest http) {
        CallerContext caller = CallerHolder.require(http);
        requireReadable(caller);
        return consentService.listOwned(caller).stream().map(ConsentResponse::from).toList();
    }

    @GetMapping("/{consentId}")
    public ConsentResponse detail(HttpServletRequest http, @PathVariable String consentId) {
        CallerContext caller = CallerHolder.require(http);
        requireReadable(caller);
        return ConsentResponse.from(consentService.getOwned(caller, consentId));
    }

    @PostMapping
    public ResponseEntity<ConsentResponse> create(HttpServletRequest http,
                                                  @RequestBody ConsentRequest request) {
        CallerContext caller = CallerHolder.require(http);
        requireManageable(caller);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConsentResponse.from(consentService.create(caller, request)));
    }

    @PostMapping("/{consentId}/authorize")
    public ConsentResponse authorize(HttpServletRequest http, @PathVariable String consentId,
                                     @RequestParam(required = false) Long expectedVersion) {
        CallerContext caller = CallerHolder.require(http);
        requireManageable(caller);
        return ConsentResponse.from(consentService.authorize(caller, consentId, expectedVersion));
    }

    @PostMapping("/{consentId}/suspend")
    public ConsentResponse suspend(HttpServletRequest http, @PathVariable String consentId,
                                   @RequestParam(required = false) Long expectedVersion) {
        CallerContext caller = CallerHolder.require(http);
        requireManageable(caller);
        return ConsentResponse.from(consentService.suspend(caller, consentId, expectedVersion));
    }

    @PostMapping("/{consentId}/resume")
    public ConsentResponse resume(HttpServletRequest http, @PathVariable String consentId,
                                  @RequestParam(required = false) Long expectedVersion) {
        CallerContext caller = CallerHolder.require(http);
        requireManageable(caller);
        return ConsentResponse.from(consentService.resume(caller, consentId, expectedVersion));
    }

    @PostMapping("/{consentId}/revoke")
    public ConsentResponse revoke(HttpServletRequest http, @PathVariable String consentId,
                                  @RequestParam(required = false) Long expectedVersion) {
        CallerContext caller = CallerHolder.require(http);
        requireManageable(caller);
        return ConsentResponse.from(consentService.revoke(caller, consentId, expectedVersion));
    }

    private void requireReadable(CallerContext caller) {
        if (!ConsentAccessPolicy.canRead(caller)) {
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE,
                    "token requires scope CONSENTS_READ or CONSENTS_MANAGE");
        }
    }

    private void requireManageable(CallerContext caller) {
        if (!ConsentAccessPolicy.canManage(caller)) {
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE,
                    "token requires scope CONSENTS_MANAGE for consent mutations");
        }
    }
}
