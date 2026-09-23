package com.hao.universalassistantbackend.tool;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ToolRegistry {

    private final Map<String, ToolDescriptor> tools;

    public ToolRegistry() {
        Map<String, ToolDescriptor> registered = new LinkedHashMap<>();
        register(registered, new ToolDescriptor("get_weather", "查询当前、未来或历史天气", List.of("network"), false, 15, 1));
        register(registered, new ToolDescriptor("web_search", "检索需要实时性的互联网信息", List.of("network"), true, 20, 1));
        register(registered, new ToolDescriptor("knowledge_search", "检索用户导入的本地知识库", List.of("knowledge:read"), false, 10, 0));
        register(registered, new ToolDescriptor("file_read", "读取用户选择的本地文件", List.of("filesystem:read"), true, 15, 0));
        register(registered, new ToolDescriptor("file_write", "写入或修改本地文件", List.of("filesystem:write"), true, 30, 0));
        register(registered, new ToolDescriptor("file_convert", "转换用户选择的文件", List.of("filesystem:read", "filesystem:write"), true, 60, 0));
        register(registered, new ToolDescriptor("read_skill", "按需加载一个 Skill 的完整操作说明", List.of("skill:read"), false, 5, 0));
        tools = Map.copyOf(registered);
    }

    public List<ToolDescriptor> list() {
        return tools.values().stream().toList();
    }

    public Optional<ToolDescriptor> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    private void register(Map<String, ToolDescriptor> target, ToolDescriptor descriptor) {
        target.put(descriptor.name(), descriptor);
    }
}
