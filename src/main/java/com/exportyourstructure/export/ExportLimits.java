package com.exportyourstructure.export;

/**
 * 导出规模上限。
 * <p>
 * 导出在服务器主线程上分 tick 进行，上限用来防止误输坐标把服务器拖进几小时的卡顿或写爆磁盘。
 * 需要更大区域时分成几次导出（不同名称）即可。
 */
public final class ExportLimits {

    /**
     * 单片边长（方块）。48 是原版结构方块 GUI 能保存 / 加载的最大边长，
     * 这样每一片都能直接用结构方块放置。调大会让单 tick 卡顿更明显，且结构方块无法加载。
     */
    public static final int PIECE_SIZE_BLOCKS = 48;

    /** 任一轴最大长度（方块）。约 21 片宽。 */
    public static final int MAX_AXIS_LENGTH_BLOCKS = 1024;

    /**
     * 总体积上限（方块）。6400 万约等于 579 个满尺寸 48³ 片；
     * 每 tick 存一片时整个导出需要数十秒到数分钟。调大会成比例增加耗时与磁盘占用。
     */
    public static final long MAX_VOLUME_BLOCKS = 64_000_000L;

    /** 分片总数上限。薄而宽的区域体积不大但片数多，单独限制以控制文件数。 */
    public static final int MAX_PIECE_COUNT = 4096;

    private ExportLimits() {
    }
}
