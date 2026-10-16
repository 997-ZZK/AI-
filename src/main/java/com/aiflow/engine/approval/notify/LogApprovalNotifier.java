package com.aiflow.engine.approval.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日志渠道（默认开）：把通知写进 `aiflow.approval.notify` 这条 logger，便于本地联调与排障时对时间线。
 *
 * <p>它不承担「让人看到待办」的职责（那是待办接口与 webhook 的事），所以标题只截 200 字，别让日志变成第二个正文仓库。
 */
@Component
public class LogApprovalNotifier implements ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger("aiflow.approval.notify");

    @Override
    public String channel() {
        return "log";
    }

    @Override
    public void notify(ApprovalNotification notification) {
        log.info("审批通知 {} approval={} run={} node={} level={} status={} assignees={} dueAt={} by={} title={}",
                notification.event(), notification.approvalId(), notification.runId(), notification.nodeId(),
                notification.level(), notification.status(), notification.assignees(), notification.dueAt(),
                notification.decidedBy() == null ? "-" : notification.decidedBy(),
                shorten(notification.title()));
    }

    private static String shorten(String title) {
        if (title == null) {
            return "";
        }
        return title.length() <= 200 ? title : title.substring(0, 200) + "…";
    }
}
