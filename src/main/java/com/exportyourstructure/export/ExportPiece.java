package com.exportyourstructure.export;

import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;

/**
 * 区域中的一片（≤48³），对应一个 .nbt 文件。
 *
 * @param indexX     X 方向片序号（从 0 开始）
 * @param indexY     Y 方向片序号
 * @param indexZ     Z 方向片序号
 * @param offset     相对区域原点的偏移（方块）；还原时把该片放在 原点 + offset
 * @param size       该片尺寸（方块），边缘片可能小于 48
 * @param templateId 结构模板 id，可直接用于 {@code /place template}
 * @param file       落盘路径
 */
public record ExportPiece(
        int indexX,
        int indexY,
        int indexZ,
        Vec3i offset,
        Vec3i size,
        ResourceLocation templateId,
        Path file
) {

    /** 文件名主干（不含 .nbt），与模板 id 最后一段一致。 */
    public static String fileStem(int indexX, int indexY, int indexZ) {
        return "x" + indexX + "_y" + indexY + "_z" + indexZ;
    }

    public boolean isSameColumn(int columnIndexX, int columnIndexZ) {
        return indexX == columnIndexX && indexZ == columnIndexZ;
    }
}
