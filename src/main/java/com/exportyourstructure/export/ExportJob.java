package com.exportyourstructure.export;

import com.exportyourstructure.ExportYourStructureMod;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 一次分片导出任务，由 {@link ExportJobManager} 每个服务器 tick 推进一步。
 * <p>
 * 推进顺序按「列」（同一 X/Z 片序号、不同 Y）进行：一列的所有片共享同一批区块，
 * 先给这批区块加自定义加载票据，等区块（以及需要时的实体分区）异步加载完，再每 tick 保存一片。
 * 换列时释放上一列的票据。用自己的 {@link TicketType} 而不是 {@code setChunkForced}，
 * 所以不会动到玩家原本 /forceload 的区块。
 * <p>
 * 每片走原版 {@link StructureTemplate#fillFromWorld}（与结构方块「保存」相同的抓取逻辑，忽略结构空位），
 * 再自己写 NBT：这样可以剔除中心点不在本片内的实体，避免跨片实体在拼回去时被复制。
 * <p>
 * 导出不是原子快照：不同片在不同 tick 抓取，期间世界若被修改，各片可能不一致。
 */
public final class ExportJob {

    /**
     * 本模组专用区块票据。值为区块坐标本身，保证同一区块加 / 减票据一一对应。
     * 票据不会写进存档，服务器崩溃后自然消失。
     */
    private static final TicketType<ChunkPos> CHUNK_TICKET = TicketType.create(
            ExportYourStructureMod.MOD_ID,
            Comparator.comparingLong(ChunkPos::toLong)
    );

    /**
     * 票据距离。0 = 只把目标区块本身提到 FULL 等级（可读方块与实体，但不 tick），
     * 不额外加载周边区块，也不会让导出期间的生物移动或红石运行。
     */
    private static final int CHUNK_TICKET_DISTANCE = 0;

    /**
     * 一列区块（含实体分区）加载的最长等待（tick，20 tick = 1 秒）。
     * 未生成区块需要现场生成地形，所以给得较宽；超时直接判失败而不是导出残缺数据。
     */
    private static final int COLUMN_LOAD_TIMEOUT_TICKS = 1200;

    /** 进度播报粒度（百分比）。调小会刷屏，调大则长任务期间反馈稀少。 */
    private static final int PROGRESS_REPORT_STEP_PERCENT = 10;

    static final String MANIFEST_FILE_NAME = "manifest.json";

    private static final Pattern PIECE_FILE_NAME_PATTERN = Pattern.compile("x\\d+_y\\d+_z\\d+\\.nbt");

    /** 任务状态，{@link #manifestValue} 写进清单。 */
    public enum Status {
        RUNNING("in_progress"),
        COMPLETE("complete"),
        FAILED("failed"),
        CANCELLED("cancelled");

        private final String manifestValue;

        Status(String manifestValue) {
            this.manifestValue = manifestValue;
        }

        public String manifestValue() {
            return manifestValue;
        }
    }

    private final ResourceLocation baseId;
    private final ResourceKey<Level> dimension;
    private final ExportRegion region;
    private final boolean includeEntities;
    /** 发起者；控制台 / 命令方块发起时为 null，只写日志。不保留 CommandSourceStack，避免跨 tick 持有失效上下文。 */
    @Nullable
    private final UUID requesterId;
    private final Path outputDirectory;
    private final List<ExportPiece> pieces;
    private final boolean[] pieceSaved;
    private final int[] pieceEntityCounts;
    private final Instant startedAt = Instant.now();

    private Status status = Status.RUNNING;
    private int nextPieceIndex;
    private int savedEntityTotal;
    private final Set<ChunkPos> heldTickets = new LinkedHashSet<>();
    private int heldColumnIndexX = -1;
    private int heldColumnIndexZ = -1;
    private int columnWaitTicks;
    private int lastReportedProgressStep;

    private ExportJob(
            ResourceLocation baseId,
            ResourceKey<Level> dimension,
            ExportRegion region,
            boolean includeEntities,
            @Nullable UUID requesterId,
            Path outputDirectory,
            List<ExportPiece> pieces
    ) {
        this.baseId = baseId;
        this.dimension = dimension;
        this.region = region;
        this.includeEntities = includeEntities;
        this.requesterId = requesterId;
        this.outputDirectory = outputDirectory;
        this.pieces = pieces;
        this.pieceSaved = new boolean[pieces.size()];
        this.pieceEntityCounts = new int[pieces.size()];
    }

    /**
     * 规划分片、校验输出路径、处理旧导出，并写出 {@code in_progress} 清单。
     * 任何一步失败都抛 {@link ExportException}，此时还没有加任何区块票据。
     *
     * @param overwrite 目标目录已有本模组导出文件时是否删除重来；为 false 时拒绝导出
     */
    public static ExportJob prepare(
            MinecraftServer server,
            ServerLevel level,
            ResourceLocation baseId,
            ExportRegion region,
            boolean includeEntities,
            boolean overwrite,
            @Nullable UUID requesterId
    ) throws ExportException {
        String basePath = baseId.getPath();
        if (basePath.isEmpty() || basePath.startsWith("/") || basePath.endsWith("/") || basePath.contains("//")) {
            throw new ExportException("名称路径不合法：" + baseId);
        }

        StructureTemplateManager templateManager = server.getStructureManager();
        List<ExportPiece> pieces = new ArrayList<>();
        for (int indexX = 0; indexX < region.pieceCountX(); indexX++) {
            for (int indexZ = 0; indexZ < region.pieceCountZ(); indexZ++) {
                for (int indexY = 0; indexY < region.pieceCountY(); indexY++) {
                    pieces.add(planPiece(templateManager, baseId, region, indexX, indexY, indexZ));
                }
            }
        }

        Path outputDirectory = pieces.get(0).file().getParent();
        clearPreviousExport(templateManager, baseId, outputDirectory, overwrite);

        ExportJob job = new ExportJob(
                baseId, level.dimension(), region, includeEntities, requesterId, outputDirectory, pieces);
        try {
            ExportManifest.write(job, "导出进行中");
        } catch (IOException exception) {
            throw new ExportException("清单写入失败：" + exception.getMessage(), exception);
        }
        return job;
    }

    private static ExportPiece planPiece(
            StructureTemplateManager templateManager,
            ResourceLocation baseId,
            ExportRegion region,
            int indexX,
            int indexY,
            int indexZ
    ) throws ExportException {
        int pieceSize = ExportLimits.PIECE_SIZE_BLOCKS;
        Vec3i offset = new Vec3i(indexX * pieceSize, indexY * pieceSize, indexZ * pieceSize);
        Vec3i size = new Vec3i(
                Math.min(pieceSize, region.size().getX() - offset.getX()),
                Math.min(pieceSize, region.size().getY() - offset.getY()),
                Math.min(pieceSize, region.size().getZ() - offset.getZ())
        );
        ResourceLocation templateId = baseId.withSuffix("/" + ExportPiece.fileStem(indexX, indexY, indexZ));
        Path file;
        try {
            file = templateManager.createAndValidatePathToGeneratedStructure(templateId, ".nbt");
        } catch (ResourceLocationException exception) {
            throw new ExportException("名称无法作为文件路径：" + baseId, exception);
        }
        return new ExportPiece(indexX, indexY, indexZ, offset, size, templateId, file);
    }

    /**
     * 目录里已有分片或清单时：不允许覆盖则报错；允许覆盖则只删除本模组格式的文件（不递归、不碰其它文件），
     * 并清掉结构管理器里的缓存，避免 /place 读到旧模板。
     */
    private static void clearPreviousExport(
            StructureTemplateManager templateManager,
            ResourceLocation baseId,
            Path outputDirectory,
            boolean overwrite
    ) throws ExportException {
        try {
            if (Files.isDirectory(outputDirectory)) {
                List<Path> previousFiles;
                try (Stream<Path> entries = Files.list(outputDirectory)) {
                    previousFiles = entries
                            .filter(Files::isRegularFile)
                            .filter(path -> isExportArtifact(path.getFileName().toString()))
                            .toList();
                }
                if (!previousFiles.isEmpty()) {
                    if (!overwrite) {
                        throw new ExportException(String.format(
                                "%s 已有 %d 个导出文件。换个名称，或用 /eys export <名称> <角点1> <角点2> <是否含实体> true 覆盖",
                                baseId, previousFiles.size()));
                    }
                    for (Path previousFile : previousFiles) {
                        String fileName = previousFile.getFileName().toString();
                        Files.delete(previousFile);
                        if (fileName.endsWith(".nbt")) {
                            String stem = fileName.substring(0, fileName.length() - ".nbt".length());
                            templateManager.remove(baseId.withSuffix("/" + stem));
                        }
                    }
                }
            }
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new ExportException("无法准备输出目录 " + outputDirectory + "：" + exception.getMessage(), exception);
        }
    }

    private static boolean isExportArtifact(String fileName) {
        return fileName.equals(MANIFEST_FILE_NAME) || PIECE_FILE_NAME_PATTERN.matcher(fileName).matches();
    }

    /**
     * 推进一步。
     *
     * @return 任务已结束（完成 / 失败）时返回 true，管理器随即丢弃本任务
     */
    boolean tick(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            heldTickets.clear();
            finish(server, Status.FAILED, "目标维度 " + dimension.location() + " 已不存在");
            return true;
        }
        try {
            ExportPiece piece = pieces.get(nextPieceIndex);
            if (!piece.isSameColumn(heldColumnIndexX, heldColumnIndexZ)) {
                releaseTickets(level);
                acquireColumn(level, piece);
            }
            if (!isColumnReady(level)) {
                columnWaitTicks++;
                if (columnWaitTicks > COLUMN_LOAD_TIMEOUT_TICKS) {
                    releaseTickets(level);
                    finish(server, Status.FAILED, String.format(
                            "第 %d 列区块%s加载超时（%d 秒）",
                            nextPieceIndex + 1,
                            includeEntities ? "或实体" : "",
                            COLUMN_LOAD_TIMEOUT_TICKS / 20));
                    return true;
                }
                return false;
            }

            savePiece(server, level, nextPieceIndex);
            nextPieceIndex++;

            if (nextPieceIndex >= pieces.size()) {
                releaseTickets(level);
                finish(server, Status.COMPLETE, null);
                return true;
            }
            reportProgress(server);
            return false;
        } catch (IOException | RuntimeException exception) {
            ExportYourStructureMod.LOGGER.error("导出 {} 第 {} 片时出错", baseId, nextPieceIndex, exception);
            releaseTickets(level);
            finish(server, Status.FAILED, "第 " + (nextPieceIndex + 1) + " 片出错：" + exception);
            return true;
        }
    }

    /** 取消任务（玩家 /eys cancel 或服务器关闭）。释放票据并把清单标成 cancelled。 */
    void cancel(MinecraftServer server, String reason) {
        ServerLevel level = server.getLevel(dimension);
        if (level != null) {
            releaseTickets(level);
        } else {
            heldTickets.clear();
        }
        finish(server, Status.CANCELLED, reason);
    }

    private void acquireColumn(ServerLevel level, ExportPiece piece) {
        int minBlockX = region.origin().getX() + piece.offset().getX();
        int minBlockZ = region.origin().getZ() + piece.offset().getZ();
        int minChunkX = SectionPos.blockToSectionCoord(minBlockX);
        int maxChunkX = SectionPos.blockToSectionCoord(minBlockX + piece.size().getX() - 1);
        int minChunkZ = SectionPos.blockToSectionCoord(minBlockZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(minBlockZ + piece.size().getZ() - 1);

        ServerChunkCache chunkSource = level.getChunkSource();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                chunkSource.addRegionTicket(CHUNK_TICKET, chunkPos, CHUNK_TICKET_DISTANCE, chunkPos);
                heldTickets.add(chunkPos);
            }
        }
        heldColumnIndexX = piece.indexX();
        heldColumnIndexZ = piece.indexZ();
        columnWaitTicks = 0;
    }

    private void releaseTickets(ServerLevel level) {
        ServerChunkCache chunkSource = level.getChunkSource();
        for (ChunkPos chunkPos : heldTickets) {
            chunkSource.removeRegionTicket(CHUNK_TICKET, chunkPos, CHUNK_TICKET_DISTANCE, chunkPos);
        }
        heldTickets.clear();
        heldColumnIndexX = -1;
        heldColumnIndexZ = -1;
    }

    /** 当前列区块都已到 FULL；需要实体时还要求实体分区已从磁盘读入，否则会漏存实体。 */
    private boolean isColumnReady(ServerLevel level) {
        ServerChunkCache chunkSource = level.getChunkSource();
        for (ChunkPos chunkPos : heldTickets) {
            if (chunkSource.getChunkNow(chunkPos.x, chunkPos.z) == null) {
                return false;
            }
            if (includeEntities && !level.areEntitiesLoaded(chunkPos.toLong())) {
                return false;
            }
        }
        return true;
    }

    private void savePiece(MinecraftServer server, ServerLevel level, int pieceIndex) throws IOException {
        ExportPiece piece = pieces.get(pieceIndex);
        BlockPos pieceOrigin = region.origin().offset(piece.offset());

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, pieceOrigin, piece.size(), includeEntities, Blocks.STRUCTURE_VOID);
        CompoundTag structureTag = template.save(new CompoundTag());
        int keptEntities = includeEntities ? keepEntitiesCenteredInPiece(structureTag, piece.size()) : 0;

        Path temporaryFile = piece.file().resolveSibling(piece.file().getFileName() + ".tmp");
        NbtIo.writeCompressed(structureTag, temporaryFile);
        Files.move(temporaryFile, piece.file(), StandardCopyOption.REPLACE_EXISTING);
        server.getStructureManager().remove(piece.templateId());

        pieceSaved[pieceIndex] = true;
        pieceEntityCounts[pieceIndex] = keptEntities;
        savedEntityTotal += keptEntities;
    }

    /**
     * 原版按实体碰撞箱与片相交来收集实体，跨片边界的实体会被相邻两片各存一份。
     * 这里只保留中心点落在本片半开区间 [0, size) 内的实体，保证每个实体只属于一片。
     * 同时丢掉 nbt 里没有 id 的条目：那是骑乘中的乘客，原版已把它存进坐骑的 Passengers 里。
     *
     * @return 保留下来的实体数
     */
    private static int keepEntitiesCenteredInPiece(CompoundTag structureTag, Vec3i pieceSize) {
        ListTag entityEntries = structureTag.getList("entities", Tag.TAG_COMPOUND);
        ListTag keptEntries = new ListTag();
        for (int entryIndex = 0; entryIndex < entityEntries.size(); entryIndex++) {
            CompoundTag entry = entityEntries.getCompound(entryIndex);
            if (!entry.getCompound("nbt").contains("id")) {
                continue;
            }
            ListTag relativePosition = entry.getList("pos", Tag.TAG_DOUBLE);
            if (isInsideHalfOpen(relativePosition.getDouble(0), pieceSize.getX())
                    && isInsideHalfOpen(relativePosition.getDouble(1), pieceSize.getY())
                    && isInsideHalfOpen(relativePosition.getDouble(2), pieceSize.getZ())) {
                keptEntries.add(entry);
            }
        }
        structureTag.put("entities", keptEntries);
        return keptEntries.size();
    }

    private static boolean isInsideHalfOpen(double value, int length) {
        return value >= 0.0 && value < length;
    }

    private void reportProgress(MinecraftServer server) {
        int percent = (int) ((long) nextPieceIndex * 100 / pieces.size());
        int progressStep = percent / PROGRESS_REPORT_STEP_PERCENT;
        if (progressStep > lastReportedProgressStep) {
            lastReportedProgressStep = progressStep;
            notifyRequester(server, String.format("%s：%d%%（%d / %d 片）", baseId, percent, nextPieceIndex, pieces.size()));
        }
    }

    private void finish(MinecraftServer server, Status finalStatus, @Nullable String reason) {
        status = finalStatus;
        String manifestMessage = reason == null ? "" : reason;
        try {
            ExportManifest.write(this, manifestMessage);
        } catch (IOException exception) {
            ExportYourStructureMod.LOGGER.error("写清单失败：{}", outputDirectory, exception);
            if (status == Status.COMPLETE) {
                status = Status.FAILED;
                notifyRequester(server, String.format(
                        "%s：%d 片已写出，但清单 manifest.json 写入失败，导出不完整：%s",
                        baseId, savedPieceCount(), exception.getMessage()));
                return;
            }
        }

        String summary = switch (status) {
            case COMPLETE -> String.format(
                    "%s 导出完成：%d 片，实体 %d 个，耗时 %d 秒。目录：%s",
                    baseId, savedPieceCount(), savedEntityTotal, elapsedSeconds(), outputDirectory);
            case CANCELLED -> String.format(
                    "%s 已取消（%s）：已写出 %d / %d 片，清单标为 cancelled，不可当作完整结构使用",
                    baseId, manifestMessage, savedPieceCount(), pieces.size());
            case FAILED -> String.format(
                    "%s 导出失败：%s。已写出 %d / %d 片，清单标为 failed",
                    baseId, manifestMessage, savedPieceCount(), pieces.size());
            case RUNNING -> throw new IllegalStateException("finish 不应以 RUNNING 结束");
        };
        notifyRequester(server, summary);
    }

    /** 发给发起玩家（若在线），并始终写日志，控制台发起的任务只看日志。 */
    private void notifyRequester(MinecraftServer server, String message) {
        ExportYourStructureMod.LOGGER.info("[EYS] {}", message);
        if (requesterId == null) {
            return;
        }
        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester != null) {
            requester.sendSystemMessage(Component.literal("[EYS] " + message));
        }
    }

    /** 给 /eys status 用的一行进度说明。 */
    public String describeProgress() {
        String phase = columnWaitTicks > 0 && !heldTickets.isEmpty()
                ? String.format("等待区块%s加载（%d tick）", includeEntities ? "/实体" : "", columnWaitTicks)
                : "保存中";
        return String.format(
                "%s：%d / %d 片，%s，实体 %d 个，已用 %d 秒",
                baseId, nextPieceIndex, pieces.size(), phase, savedEntityTotal, elapsedSeconds());
    }

    private long elapsedSeconds() {
        return Instant.now().getEpochSecond() - startedAt.getEpochSecond();
    }

    private int savedPieceCount() {
        int count = 0;
        for (boolean saved : pieceSaved) {
            if (saved) {
                count++;
            }
        }
        return count;
    }

    // ---- 供 ExportManifest 读取 ----

    ResourceLocation baseId() {
        return baseId;
    }

    ResourceKey<Level> dimension() {
        return dimension;
    }

    ExportRegion region() {
        return region;
    }

    boolean includeEntities() {
        return includeEntities;
    }

    Status status() {
        return status;
    }

    Instant startedAt() {
        return startedAt;
    }

    Path outputDirectory() {
        return outputDirectory;
    }

    List<ExportPiece> pieces() {
        return Collections.unmodifiableList(pieces);
    }

    boolean isPieceSaved(int pieceIndex) {
        return pieceSaved[pieceIndex];
    }

    int pieceEntityCount(int pieceIndex) {
        return pieceEntityCounts[pieceIndex];
    }

    int savedEntityTotal() {
        return savedEntityTotal;
    }
}
