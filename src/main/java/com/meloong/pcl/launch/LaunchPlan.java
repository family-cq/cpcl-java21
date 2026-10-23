package com.meloong.pcl.launch;

import com.meloong.pcl.version.LibraryEntry;

import java.nio.file.Path;
import java.util.List;

/**
 * 启动计划：最终命令行与相关路径。
 *
 * @param command   进程参数列表（未加引号，可直接交给 ProcessBuilder）
 * @param display   便于展示/复制的命令行字符串（含引号转义）
 * @param classpath 类路径
 * @param nativesDir natives 目录
 * @param gameDir   游戏工作目录
 * @param javaPath  java 可执行文件
 * @param memoryMb  实际分配内存
 */
public record LaunchPlan(
        List<String> command,
        String display,
        String classpath,
        Path nativesDir,
        Path gameDir,
        String javaPath,
        int memoryMb,
        List<LibraryEntry> libraries) {

    /** 把参数列表转成可复制到命令行的字符串。 */
    public static String toDisplay(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)) {
                sb.append('"').append(arg.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }
}
