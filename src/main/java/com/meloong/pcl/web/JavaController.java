package com.meloong.pcl.web;

import com.meloong.pcl.launch.JavaLocator;
import com.meloong.pcl.launch.JavaRuntime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/java")
public class JavaController {

    private final JavaLocator javaLocator;

    public JavaController(JavaLocator javaLocator) {
        this.javaLocator = javaLocator;
    }

    @GetMapping
    public JavaList list(@RequestParam(required = false) Integer requiredMajor) {
        List<JavaRuntime> found = javaLocator.candidates();
        JavaRuntime best = javaLocator.findBest(requiredMajor == null ? 0 : requiredMajor);
        return new JavaList(found.stream().map(JavaRuntime::info).toList(),
                best == null ? null : best.info().path());
    }

    @PostMapping("/refresh")
    public JavaList refresh(@RequestParam(required = false) Integer requiredMajor) {
        javaLocator.refresh();
        return list(requiredMajor);
    }

    /** 手动添加自定义 Java 路径（bin 目录 / JDK 根目录 / java.exe 均可）。 */
    @PostMapping("/custom")
    public Map<String, Object> addCustom(@RequestBody Map<String, String> body,
                                         @RequestParam(required = false) Integer requiredMajor) {
        String path = body == null ? "" : body.getOrDefault("path", "");
        if (path.isBlank()) {
            throw new IllegalArgumentException("请填写 Java 路径");
        }
        JavaRuntime runtime = javaLocator.register(path);
        if (!runtime.available()) {
            throw new IllegalArgumentException("该路径下的 Java 不可用（需要 64 位 Java，"
                    + "且能正常执行 java -version）：" + path);
        }
        JavaList list = list(requiredMajor);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javas", list.javas());
        result.put("recommended", list.recommended());
        result.put("added", runtime.info().path());
        return result;
    }

    public record JavaList(List<JavaRuntime.Info> javas, String recommended) {
    }
}
