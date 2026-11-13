package com.exportyourstructure.export;

import com.exportyourstructure.ExportYourStructureMod;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 持有当前导出任务并在服务器 tick 末尾推进它。
 * <p>
 * 同一时间只允许一个任务：导出本身占用主线程，并行只会让两边都更慢，
 * 也避免两个任务往同一目录写。服务器关闭时取消任务以释放区块票据。
 */
@EventBusSubscriber(modid = ExportYourStructureMod.MOD_ID)
public final class ExportJobManager {

    @Nullable
    private static ExportJob activeJob;

    private ExportJobManager() {
    }

    public static Optional<ExportJob> activeJob() {
        return Optional.ofNullable(activeJob);
    }

    /** 调用方需先确认 {@link #activeJob()} 为空。 */
    public static void start(ExportJob job) {
        if (activeJob != null) {
            throw new IllegalStateException("已有导出任务在运行");
        }
        activeJob = job;
    }

    /** @return 是否确实取消了一个任务 */
    public static boolean cancel(MinecraftServer server, String reason) {
        ExportJob job = activeJob;
        if (job == null) {
            return false;
        }
        activeJob = null;
        job.cancel(server, reason);
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ExportJob job = activeJob;
        if (job != null && job.tick(event.getServer())) {
            activeJob = null;
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        cancel(event.getServer(), "服务器关闭");
    }
}
