package com.exportyourstructure.command;

import com.exportyourstructure.ExportYourStructureMod;
import com.exportyourstructure.export.ExportException;
import com.exportyourstructure.export.ExportJob;
import com.exportyourstructure.export.ExportJobManager;
import com.exportyourstructure.export.ExportLimits;
import com.exportyourstructure.export.ExportRegion;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code /eys} 命令（需要权限等级 2，即 OP / 单人开作弊）。
 * <pre>
 * /eys help
 * /eys info &lt;角点1&gt; &lt;角点2&gt;
 * /eys export &lt;名称&gt; &lt;角点1&gt; &lt;角点2&gt; [是否含实体=true] [覆盖=false]
 * /eys status
 * /eys cancel
 * </pre>
 * 消息用字面文本而不是翻译键：模组只装在服务端，客户端没有语言文件。
 */
@EventBusSubscriber(modid = ExportYourStructureMod.MOD_ID)
public final class EysCommands {

    private static final String PREFIX = "[EYS] ";

    private EysCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("eys")
                        .requires(source -> source.hasPermission(2))
                        .executes(EysCommands::help)
                        .then(Commands.literal("help").executes(EysCommands::help))
                        .then(Commands.literal("info")
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(EysCommands::info))))
                        .then(Commands.literal("export")
                                .then(Commands.argument("name", ResourceLocationArgument.id())
                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                        .executes(context -> export(context, true, false))
                                                        .then(Commands.argument("include_entities", BoolArgumentType.bool())
                                                                .executes(context -> export(
                                                                        context,
                                                                        BoolArgumentType.getBool(context, "include_entities"),
                                                                        false))
                                                                .then(Commands.argument("overwrite", BoolArgumentType.bool())
                                                                        .executes(context -> export(
                                                                                context,
                                                                                BoolArgumentType.getBool(context, "include_entities"),
                                                                                BoolArgumentType.getBool(context, "overwrite")))))))))
                        .then(Commands.literal("status").executes(EysCommands::status))
                        .then(Commands.literal("cancel").executes(EysCommands::cancel))
        );
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        String helpText = String.join("\n",
                PREFIX + "Export Your Structure：把任意长方体区域分片导出为原版结构 .nbt",
                "/eys info <角点1> <角点2>  预览尺寸与分片数",
                "/eys export <名称> <角点1> <角点2> [含实体=true] [覆盖=false]",
                "    例：/eys export mypack:castle ~ ~ ~ ~200 ~80 ~150",
                "/eys status  查看进度    /eys cancel  取消导出",
                String.format("每片最大 %d³；单轴上限 %d 格，总体积上限 %d 方块，最多 %d 片",
                        ExportLimits.PIECE_SIZE_BLOCKS,
                        ExportLimits.MAX_AXIS_LENGTH_BLOCKS,
                        ExportLimits.MAX_VOLUME_BLOCKS,
                        ExportLimits.MAX_PIECE_COUNT),
                "输出：<存档>/generated/<命名空间>/structures/<路径>/xN_yN_zN.nbt + manifest.json");
        context.getSource().sendSuccess(() -> Component.literal(helpText), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ExportRegion region = ExportRegion.fromCorners(
                BlockPosArgument.getBlockPos(context, "from"),
                BlockPosArgument.getBlockPos(context, "to"));
        source.sendSuccess(() -> Component.literal(PREFIX + region.describe()), false);
        Optional<String> problem = region.validate(source.getLevel());
        if (problem.isPresent()) {
            source.sendFailure(Component.literal(PREFIX + "无法导出：" + problem.get()));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int export(CommandContext<CommandSourceStack> context, boolean includeEntities, boolean overwrite) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ResourceLocation baseId = ResourceLocationArgument.getId(context, "name");
        ExportRegion region = ExportRegion.fromCorners(
                BlockPosArgument.getBlockPos(context, "from"),
                BlockPosArgument.getBlockPos(context, "to"));

        Optional<ExportJob> runningJob = ExportJobManager.activeJob();
        if (runningJob.isPresent()) {
            source.sendFailure(Component.literal(
                    PREFIX + "已有导出在进行：" + runningJob.get().describeProgress() + "。先 /eys cancel 或等待完成"));
            return 0;
        }
        Optional<String> problem = region.validate(level);
        if (problem.isPresent()) {
            source.sendFailure(Component.literal(PREFIX + "无法导出：" + problem.get()));
            return 0;
        }

        UUID requesterId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        ExportJob job;
        try {
            job = ExportJob.prepare(source.getServer(), level, baseId, region, includeEntities, overwrite, requesterId);
        } catch (ExportException exception) {
            source.sendFailure(Component.literal(PREFIX + exception.getMessage()));
            return 0;
        }
        ExportJobManager.start(job);
        source.sendSuccess(() -> Component.literal(String.format(
                "%s开始导出 %s（%s实体）：%s。期间可能卡顿，/eys status 查看进度",
                PREFIX, baseId, includeEntities ? "含" : "不含", region.describe())), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<ExportJob> runningJob = ExportJobManager.activeJob();
        if (runningJob.isEmpty()) {
            source.sendSuccess(() -> Component.literal(PREFIX + "当前没有导出任务"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(PREFIX + runningJob.get().describeProgress()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ExportJobManager.cancel(source.getServer(), "由 " + source.getTextName() + " 取消")) {
            source.sendFailure(Component.literal(PREFIX + "当前没有导出任务"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(PREFIX + "已取消导出"), true);
        return Command.SINGLE_SUCCESS;
    }
}
