package com.meloong.pcl.web;

import com.meloong.pcl.launch.GameLauncher;
import com.meloong.pcl.launch.LaunchPlan;
import com.meloong.pcl.launch.LaunchService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/instances")
public class LaunchController {

    private final LaunchService launchService;

    public LaunchController(LaunchService launchService) {
        this.launchService = launchService;
    }

    /** 只构建命令，不启动（对应 PCL 的启动前预览）。 */
    @PostMapping("/{name}/command")
    public CommandResponse command(@PathVariable String name,
                                   @RequestBody(required = false) LaunchService.LaunchRequest request)
            throws IOException {
        LaunchService.LaunchRequest effective = request == null
                ? new LaunchService.LaunchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null)
                : request;
        LaunchPlan plan = launchService.plan(name, effective);
        return new CommandResponse(name, plan.display(), plan.command(), plan.classpath(),
                plan.memoryMb(), plan.javaPath(), plan.gameDir().toString(), plan.nativesDir().toString());
    }

    /** 启动游戏。 */
    @PostMapping("/{name}/launch")
    public GameLauncher.LaunchResult launch(@PathVariable String name,
                                            @RequestBody(required = false) LaunchService.LaunchRequest request)
            throws IOException {
        LaunchService.LaunchRequest effective = request == null
                ? new LaunchService.LaunchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null)
                : request;
        return launchService.launch(name, effective);
    }

    public record CommandResponse(String instance, String display, List<String> command, String classpath,
                                  int memoryMb, String javaPath, String gameDir, String nativesDir) {
    }
}
