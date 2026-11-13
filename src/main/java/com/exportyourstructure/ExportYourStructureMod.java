package com.exportyourstructure;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Export Your Structure 入口。
 * <p>
 * 纯服务端工具：只注册命令和服务器 tick 监听，不注册方块、物品、网络包，
 * 因此客户端不需要安装。命令见 {@link com.exportyourstructure.command.EysCommands}。
 */
@Mod(ExportYourStructureMod.MOD_ID)
public final class ExportYourStructureMod {

    public static final String MOD_ID = "export_your_structure";

    public static final Logger LOGGER = LogUtils.getLogger();

    public ExportYourStructureMod() {
    }
}
