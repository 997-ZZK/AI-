package com.aiflow.engine.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 超时/升级扫描。
 *
 * <p>多实例安全靠 {@link ApprovalService#expireDue} 里的条件更新（受影响行数 = 1 才处理），
 * 不需要分布式锁：两个实例同时扫到同一条，也只有一个能把状态推过去。
 *
 * <p>恢复在**扫描事务之外**触发（expireDue 返回之后）：事务里派发会让恢复线程读到还没提交的状态。
 */
@Component
public class ApprovalTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutScheduler.class);

    private final ApprovalService approvalService;
    private final ApprovalResumeService resumeService;
    private final ApprovalProperties properties;

    public ApprovalTimeoutScheduler(ApprovalService approvalService, ApprovalResumeService resumeService,
                                    ApprovalProperties properties) {
        this.approvalService = approvalService;
        this.resumeService = resumeService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${aiflow.approval.sweep-ms:5000}")
    public void sweep() {
        if (!properties.sweepEnabled()) {
            return;
        }
        try {
            List<ApprovalService.ExpiryOutcome> outcomes = approvalService.expireDue(Instant.now());
            for (ApprovalService.ExpiryOutcome outcome : outcomes) {
                if (outcome.finalDecision()) {
                    resumeService.resumeAfterTimeout(outcome);
                }
            }
        } catch (Exception e) {
            // 调度线程里抛异常会静默吞掉后续调度（fixedDelay 下尤其难发现），这里必须自己接住并留痕
            log.error("审批超时扫描失败", e);
        }
    }
}
