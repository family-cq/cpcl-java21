package com.meloong.pcl.web;

import com.meloong.pcl.tool.ToolboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/toolbox")
public class ToolboxController {

    private final ToolboxService toolboxService;

    public ToolboxController(ToolboxService toolboxService) {
        this.toolboxService = toolboxService;
    }

    /** 今日人品（同一天同一名字结果固定）。 */
    @GetMapping("/luck")
    public Map<String, Object> luck(@RequestParam(required = false) String name) {
        return toolboxService.luck(name);
    }

    /** 内存优化：把各进程工作集交还系统。 */
    @PostMapping("/memory")
    public Map<String, Object> memory() {
        return toolboxService.optimizeMemory();
    }

    /** 扫描日志与崩溃报告（只读）。 */
    @GetMapping("/garbage")
    public Map<String, Object> garbage() {
        return toolboxService.scanGarbage();
    }

    /** 清理垃圾。 */
    @PostMapping("/garbage/clean")
    public Map<String, Object> clean() {
        return toolboxService.cleanGarbage();
    }
}
