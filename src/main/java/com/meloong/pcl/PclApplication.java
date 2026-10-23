package com.meloong.pcl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * PCL（Plain Craft Launcher）核心逻辑的 Java 21 / Spring Boot 移植入口。
 *
 * <p>原项目为 VB.NET + WPF 桌面程序，本工程移植的是与界面无关的核心模块：
 * 版本解析、参数构建、Java 检测、并发下载、实例与 Mod 管理。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PclApplication {

    public static void main(String[] args) {
        SpringApplication.run(PclApplication.class, args);
    }
}
