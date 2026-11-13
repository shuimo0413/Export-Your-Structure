package com.exportyourstructure.export;

/** 导出准备阶段的可预期失败，消息直接展示给执行命令的人。 */
public final class ExportException extends Exception {

    public ExportException(String message) {
        super(message);
    }

    public ExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
