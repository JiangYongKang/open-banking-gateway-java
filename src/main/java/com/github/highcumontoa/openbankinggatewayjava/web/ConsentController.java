package com.github.highcumontoa.openbankinggatewayjava.web;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.api.ConsentResponse;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.security.CallerHolder;
import com.github.highcumontoa.openbankinggatewayjava.service.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同意书生命周期：创建 / 授权 / 暂停 / 恢复 / 撤销。
 * 仅需通过认证；资源级操作受归属与状态机约束。
 */
@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping
    public ResponseEntity<ConsentResponse> create(HttpServletRequest http,
                                                  @RequestBody ConsentRequest request) {
        CallerContext caller = CallerHolder.require(http);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConsentResponse.from(consentService.create(caller, request)));
    }

    @PostMapping("/{consentId}/authorize")
    public ConsentResponse authorize(HttpServletRequest http, @PathVariable String consentId) {
        CallerContext caller = CallerHolder.require(http);
        return ConsentResponse.from(consentService.authorize(caller, consentId));
    }

    @PostMapping("/{consentId}/suspend")
    public ConsentResponse suspend(HttpServletRequest http, @PathVariable String consentId) {
        CallerContext caller = CallerHolder.require(http);
        return ConsentResponse.from(consentService.suspend(caller, consentId));
    }

    @PostMapping("/{consentId}/resume")
    public ConsentResponse resume(HttpServletRequest http, @PathVariable String consentId) {
        CallerContext caller = CallerHolder.require(http);
        return ConsentResponse.from(consentService.resume(caller, consentId));
    }

    @PostMapping("/{consentId}/revoke")
    public ConsentResponse revoke(HttpServletRequest http, @PathVariable String consentId) {
        CallerContext caller = CallerHolder.require(http);
        return ConsentResponse.from(consentService.revoke(caller, consentId));
    }
}
