// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.selftest

/**
 * Human-readable meaning of the Driver POST checks, for the self-test screen.
 * Titles are Chinese; "impact" says what the game loses when the row is not
 * PASS; "fallback" explains how MobileGL works around a missing capability.
 */
data class CheckExplain(
    val title: String,
    val impact: String,
    val fallback: String? = null,
)

object CheckExplanations {
    private data class Rule(
        val match: String,
        val explain: CheckExplain,
    )

    private val rules = listOf(
        Rule("OpenGL ES version", CheckExplain(
            "OpenGL ES 版本",
            "决定 MobileGL 能向游戏宣告的 GL 版本上限；低于 3.2 时部分现代特性会被关闭。",
            "MobileGL 会把 GL 版本降到驱动实际支持的水平，而不是直接崩溃。",
        )),
        Rule("Vertex shader storage blocks", CheckExplain(
            "顶点着色器可写存储块",
            "顶点阶段写 SSBO/原子；光影、地形模组（如 Sodium/Iris 的部分路径）可能依赖。",
            "缺失时 MobileGL 不广告对应 GL 扩展，相关模组会走低配路径。",
        )),
        Rule("Shader storage buffer bindings", CheckExplain(
            "存储缓冲绑定数量",
            "一次能绑定多少 SSBO；数量不足会影响复杂光影/大型模组。",
            "MobileGL 会按驱动上限降低可用的绑定数。",
        )),
        Rule("GL_EXT_buffer_storage", CheckExplain(
            "持久缓冲映射（buffer storage）",
            "允许缓冲区长期映射、减少上传开销；部分现代渲染路径依赖。",
            "缺失时退回普通 buffer data/map 路径，性能略降但功能可用。",
        )),
        Rule("GL_EXT_base_instance", CheckExplain(
            "非零 baseInstance 绘制",
            "实例化绘制的起始实例号；部分批处理渲染依赖。",
            "缺失时 MobileGL 用 CPU 侧偏移/展开替代。",
        )),
        Rule("GL_EXT_texture_norm16", CheckExplain(
            "16 位归一化纹理",
            "高精度归一化纹理格式；部分光影/材质包会用到。",
            "缺失时退回 8 位或浮点格式，可能有画质差异。",
        )),
        Rule("Indirect gl_InstanceID", CheckExplain(
            "间接绘制的实例 ID 语义",
            "间接绘制时 gl_InstanceID 的起始值；错误会导致实例错位。",
            "MobileGL 在着色器里做重基（rebase）修正。",
        )),
        Rule("Timer queries", CheckExplain(
            "GPU 计时器查询",
            "性能面板、部分模组的帧时间统计；不影响画面正确性。",
            "缺失/关闭时 MobileGL 禁用 timer query，性能面板显示不可用。",
        )),
        Rule("ES3 context", CheckExplain(
            "ES3 上下文创建",
            "OpenGL ES 3.x 上下文是 MobileGL 的 GLES 后端基础。",
            "创建失败会退回更低的上下文或直接不可用。",
        )),
        Rule("GLES capability query", CheckExplain(
            "GLES 能力查询",
            "读取驱动能力（扩展、限制）；查询失败会导致自检与后端降级判断失真。",
            "MobileGL 会用保守默认值继续初始化。",
        )),
        Rule("Vulkan instance", CheckExplain(
            "Vulkan 实例创建",
            "Magma/DirectVulkan 后端的基础；失败则 Vulkan 后端完全不可用。",
            "自检会明确报错，游戏内应改用 DirectGLES 或修复驱动。",
        )),
        Rule("Surface extensions", CheckExplain(
            "窗口表面扩展",
            "VK_KHR_surface / VK_KHR_android_surface；缺失则无法上屏。",
            "无法用替代方案，必须由驱动提供。",
        )),
        Rule("Vulkan core entry points", CheckExplain(
            "Vulkan 核心入口",
            "vkCreateInstance 等基础函数；缺失说明 loader/驱动不完整。",
            "无法替代。",
        )),
        Rule("Graphics device", CheckExplain(
            "Vulkan 物理设备",
            "能否枚举到 GPU；0 个设备通常表示驱动/内核接口不匹配。",
            "无法替代；需检查驱动或内核。",
        )),
        Rule("VK_KHR_swapchain", CheckExplain(
            "交换链",
            "上屏必需；缺失则 Vulkan 后端无法显示画面。",
            "无法替代。",
        )),
        Rule("multiDrawIndirect", CheckExplain(
            "多重间接绘制",
            "一次提交多条间接绘制，减少 CPU 开销；现代渲染器常用。",
            "缺失时退回逐条绘制或 CPU 展开，帧率可能下降。",
        )),
        Rule("drawIndirectFirstInstance", CheckExplain(
            "间接绘制 firstInstance",
            "间接命令中的起始实例号；Sodium 等模组会用。",
            "缺失时 MobileGL 在 CPU/着色器侧修正。",
        )),
        Rule("vertexPipelineStoresAndAtomics", CheckExplain(
            "顶点阶段存储与原子操作",
            "顶点着色器写 SSBO / 原子操作；部分光影与地形模组依赖。",
            "缺失时对应 GL 扩展不广告，模组走低配路径（你之前看到的 WARN 就是它）。",
        )),
        Rule("shaderDrawParameters", CheckExplain(
            "着色器绘制参数",
            "gl_DrawID / gl_BaseVertex / gl_BaseInstance；批处理渲染依赖。",
            "缺失时 MobileGL 用 uniform 或拆分绘制替代。",
        )),
        Rule("Compute shader subgroup", CheckExplain(
            "计算着色器 subgroup",
            "subgroup 运算；计算密集型模组/光影可能用到。",
            "缺失时 MobileGL 禁用 subgroup 或改用 shared memory 模拟。",
        )),
        Rule("VK_KHR_draw_indirect_count", CheckExplain(
            "间接绘制计数",
            "GPU 侧决定绘制数量；部分现代渲染路径使用。",
            "缺失时退回固定数量的间接绘制或 CPU 决策。",
        )),
        Rule("Index type uint8", CheckExplain(
            "8 位索引缓冲",
            "节省带宽的小索引格式；部分网格压缩路径使用。",
            "缺失时转换为 16 位索引，功能不变、显存略增。",
        )),
        Rule("Fill mode", CheckExplain(
            "非实心填充模式（线框）",
            "glPolygonMode 的线框/点模式；部分调试工具与特定模组使用。",
            "缺失时 MobileGL 无法提供真正的线框渲染，只能保持实心。",
        )),
    )

    fun of(name: String): CheckExplain {
        rules.firstOrNull { name.contains(it.match, ignoreCase = true) }?.let { return it.explain }
        return CheckExplain(
            title = name,
            impact = "由驱动直接决定；详见下方原始详情。",
            fallback = null,
        )
    }

    fun statusZh(status: String): String = when (status.uppercase()) {
        "PASS" -> "通过"
        "WARN" -> "替代"
        "FAIL" -> "失败"
        "INFO" -> "信息"
        else -> status
    }
}
