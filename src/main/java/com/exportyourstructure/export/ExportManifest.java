package com.exportyourstructure.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

/**
 * 写 {@code manifest.json}：记录区域原点、尺寸与每片偏移，供还原 / 拼装工具使用。
 * <p>
 * 导出开始时写一次（status = in_progress），结束时再写一次最终状态。
 * 只有 status = complete 的清单代表完整导出；进程中途被杀时会留下 in_progress。
 */
final class ExportManifest {

    /** 清单格式版本。字段含义变化时 +1，方便外部工具判断。 */
    private static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ExportManifest() {
    }

    static void write(ExportJob job, String message) throws IOException {
        ExportRegion region = job.region();
        JsonObject root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.addProperty("generator", "export_your_structure");
        root.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
        root.addProperty("data_version", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.addProperty("id", job.baseId().toString());
        root.addProperty("dimension", job.dimension().location().toString());
        root.add("origin", toJsonArray(region.origin()));
        root.add("size", toJsonArray(region.size()));
        root.addProperty("piece_size", ExportLimits.PIECE_SIZE_BLOCKS);
        root.add("piece_counts", toJsonArray(new Vec3i(region.pieceCountX(), region.pieceCountY(), region.pieceCountZ())));
        root.addProperty("include_entities", job.includeEntities());
        root.addProperty("status", job.status().manifestValue());
        root.addProperty("message", message);
        root.addProperty("started_at", job.startedAt().toString());
        root.addProperty("updated_at", Instant.now().toString());
        root.addProperty("entity_count", job.savedEntityTotal());

        JsonArray pieceArray = new JsonArray();
        List<ExportPiece> pieces = job.pieces();
        for (int pieceIndex = 0; pieceIndex < pieces.size(); pieceIndex++) {
            ExportPiece piece = pieces.get(pieceIndex);
            JsonObject pieceObject = new JsonObject();
            pieceObject.addProperty("id", piece.templateId().toString());
            pieceObject.addProperty("file", piece.file().getFileName().toString());
            pieceObject.add("index", toJsonArray(new Vec3i(piece.indexX(), piece.indexY(), piece.indexZ())));
            pieceObject.add("offset", toJsonArray(piece.offset()));
            pieceObject.add("size", toJsonArray(piece.size()));
            pieceObject.addProperty("saved", job.isPieceSaved(pieceIndex));
            pieceObject.addProperty("entity_count", job.pieceEntityCount(pieceIndex));
            pieceArray.add(pieceObject);
        }
        root.add("pieces", pieceArray);

        Path manifestFile = job.outputDirectory().resolve(ExportJob.MANIFEST_FILE_NAME);
        Path temporaryFile = manifestFile.resolveSibling(ExportJob.MANIFEST_FILE_NAME + ".tmp");
        Files.writeString(temporaryFile, GSON.toJson(root), StandardCharsets.UTF_8);
        Files.move(temporaryFile, manifestFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonArray toJsonArray(Vec3i vector) {
        JsonArray array = new JsonArray();
        array.add(vector.getX());
        array.add(vector.getY());
        array.add(vector.getZ());
        return array;
    }
}
