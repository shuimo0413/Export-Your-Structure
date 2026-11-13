package com.exportyourstructure.export;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * 待导出的轴对齐长方体区域。
 *
 * @param origin 最小角（西 / 下 / 北），所有分片偏移都相对它
 * @param size   各轴长度（方块），两个输入角点都包含在内
 */
public record ExportRegion(BlockPos origin, Vec3i size) {

    /** 由两个任意顺序的角点构造区域，两个角点所在方块都包含在内。 */
    public static ExportRegion fromCorners(BlockPos firstCorner, BlockPos secondCorner) {
        BlockPos minCorner = new BlockPos(
                Math.min(firstCorner.getX(), secondCorner.getX()),
                Math.min(firstCorner.getY(), secondCorner.getY()),
                Math.min(firstCorner.getZ(), secondCorner.getZ())
        );
        Vec3i size = new Vec3i(
                Math.abs(firstCorner.getX() - secondCorner.getX()) + 1,
                Math.abs(firstCorner.getY() - secondCorner.getY()) + 1,
                Math.abs(firstCorner.getZ() - secondCorner.getZ()) + 1
        );
        return new ExportRegion(minCorner, size);
    }

    /** 最大角（东 / 上 / 南），包含在区域内。 */
    public BlockPos maxCorner() {
        return origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
    }

    public long volume() {
        return (long) size.getX() * size.getY() * size.getZ();
    }

    public int pieceCountX() {
        return Math.ceilDiv(size.getX(), ExportLimits.PIECE_SIZE_BLOCKS);
    }

    public int pieceCountY() {
        return Math.ceilDiv(size.getY(), ExportLimits.PIECE_SIZE_BLOCKS);
    }

    public int pieceCountZ() {
        return Math.ceilDiv(size.getZ(), ExportLimits.PIECE_SIZE_BLOCKS);
    }

    public long totalPieceCount() {
        return (long) pieceCountX() * pieceCountY() * pieceCountZ();
    }

    /**
     * 检查区域能否在该维度导出。
     *
     * @return 不能导出时返回给玩家看的原因
     */
    public Optional<String> validate(ServerLevel level) {
        int longestAxis = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        if (longestAxis > ExportLimits.MAX_AXIS_LENGTH_BLOCKS) {
            return Optional.of(String.format(
                    "单轴最长 %d 格，超过上限 %d 格",
                    longestAxis, ExportLimits.MAX_AXIS_LENGTH_BLOCKS));
        }
        if (volume() > ExportLimits.MAX_VOLUME_BLOCKS) {
            return Optional.of(String.format(
                    "体积 %d 方块，超过上限 %d 方块",
                    volume(), ExportLimits.MAX_VOLUME_BLOCKS));
        }
        if (totalPieceCount() > ExportLimits.MAX_PIECE_COUNT) {
            return Optional.of(String.format(
                    "需要 %d 片，超过上限 %d 片",
                    totalPieceCount(), ExportLimits.MAX_PIECE_COUNT));
        }
        if (!level.isInWorldBounds(origin) || !level.isInWorldBounds(maxCorner())) {
            return Optional.of(String.format(
                    "区域超出世界范围（本维度可用高度 %d ~ %d）",
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1));
        }
        return Optional.empty();
    }

    /** 给玩家看的一行区域摘要。 */
    public String describe() {
        return String.format(
                "原点 (%d, %d, %d)，尺寸 %d×%d×%d，体积 %d 方块，分片 %d×%d×%d = %d 片（每片最大 %d³）",
                origin.getX(), origin.getY(), origin.getZ(),
                size.getX(), size.getY(), size.getZ(),
                volume(),
                pieceCountX(), pieceCountY(), pieceCountZ(), totalPieceCount(),
                ExportLimits.PIECE_SIZE_BLOCKS);
    }
}
