package com.aiflow.engine.run;

public class LoopGuard {

    private final int maxSteps;
    private int steps;

    public LoopGuard(int maxSteps) {
        this(maxSteps, 0);
    }

    /**
     * 带已消耗步数进入：恢复（人工审批之后的继续执行）必须把挂起前的步数带回来，
     * 否则「挂起一次重置一次预算」就是一个白送的绕过口。
     */
    public LoopGuard(int maxSteps, int usedSteps) {
        this.maxSteps = maxSteps;
        this.steps = Math.max(0, usedSteps);
    }

    public int step(String nodeId) {
        steps++;
        if (steps > maxSteps) {
            throw new LoopDetectedException("执行步数超过上限 " + maxSteps + "（最后节点: " + nodeId + "）");
        }
        return steps;
    }

    public int steps() {
        return steps;
    }
}
