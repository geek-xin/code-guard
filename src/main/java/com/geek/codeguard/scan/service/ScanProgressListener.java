package com.geek.codeguard.scan.service;

import com.geek.codeguard.scan.model.ScanFinding;

/**
 * 扫描进度回调：由扫描编排器实现，转成 SSE 事件。
 */
public interface ScanProgressListener {
    /** 绑定当前扫描上下文（供并行分析线程更新进度时定位扫描记录） */
    default void bindScanContext(String scanId) {
    }

    /** 解除当前扫描上下文绑定 */
    default void unbindScanContext() {
    }

    default void onStage(String stage, String status, String message) {
    }

    default void onProgress(String stage, int current, int total, String message) {
    }

    default void onFinding(ScanFinding finding) {
    }

}
