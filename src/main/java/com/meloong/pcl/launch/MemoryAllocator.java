package com.meloong.pcl.launch;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * 内存分配，移植自 <c>PageInstanceSetup.GetRam</c> 与 <c>PageSetupLaunch.GetRam</c> 的自动分配算法。
 *
 * <p>四个阶段分别按 100% / 70% / 40% / 15% 的比例逐步追加内存，
 * 目标值随 Mod 数量线性增长，最后不低于最低要求。
 */
@Component
public class MemoryAllocator {

    /** 按 PCL 的自动算法计算应分配的内存（MB）。 */
    public int allocateMb(int modCount, boolean modable, boolean hasOptiFine) {
        double availableGb = availableMemoryGb();
        double minimum;
        double target1;
        double target2;
        double target3;
        if (modable) {
            minimum = 0.5 + modCount / 150.0;
            target1 = 1.5 + modCount / 90.0;
            target2 = 2.7 + modCount / 50.0;
            target3 = 4.5 + modCount / 25.0;
        } else if (hasOptiFine) {
            minimum = 0.5;
            target1 = 1.5;
            target2 = 3;
            target3 = 5;
        } else {
            minimum = 0.5;
            target1 = 1.5;
            target2 = 2.5;
            target3 = 4;
        }

        double give = 0;
        double available = availableGb;
        // 阶段一：0 ~ T1，100%
        give += Math.min(available, target1);
        available -= target1;
        if (available >= 0.1) {
            // 阶段二：T1 ~ T2，70%
            double delta = target2 - target1;
            give += Math.min(available * 0.7, delta);
            available -= delta / 0.7;
        }
        if (available >= 0.1) {
            // 阶段三：T2 ~ T3，40%
            double delta = target3 - target2;
            give += Math.min(available * 0.4, delta);
            available -= delta / 0.4;
        }
        if (available >= 0.1) {
            // 阶段四：T3 ~ T3 * 2，15%
            give += Math.min(available * 0.15, target3);
        }
        give = Math.round(Math.max(give, minimum) * 10) / 10.0;
        // 至少 512 MB，向下取整到 128 MB 便于阅读
        return Math.max(512, (int) Math.floor(give * 1024 / 128) * 128);
    }

    /** 当前可用物理内存（GB）。 */
    public double availableMemoryGb() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                long free = sun.getFreeMemorySize();
                if (free > 0) {
                    return free / 1024.0 / 1024 / 1024;
                }
                return sun.getTotalMemorySize() / 1024.0 / 1024 / 1024 * 0.7;
            }
        } catch (Throwable ignored) {
            // 非 HotSpot 实现时退化为固定值
        }
        return 4;
    }
}
