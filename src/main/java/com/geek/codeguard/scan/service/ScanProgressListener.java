package com.geek.codeguard.scan.service;

import com.geek.codeguard.scan.model.ScanFinding;

/**
 * 扫描进度回调：由扫描编排器实现，转成 SSE 事件。
 */
public interface ScanProgressListener {
    default void onStage(String stage, String status, String message) {
    }

    default void onProgress(String stage, int current, int total, String message) {
    }

    default void onFinding(ScanFinding finding) {
    }

    default void onMessage(String type, String message) {
    }
}
