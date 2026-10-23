package com.recordandgrow.controller;

import com.recordandgrow.infra.response.ApiResponse;
import com.recordandgrow.skill.Skill;
import com.recordandgrow.skill.SkillRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 技能接口：列表与热加载。技能文件改完不必重启服务。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @GetMapping
    public ApiResponse<List<Skill>> list() {
        return ApiResponse.success(skillRegistry.all());
    }

    @PostMapping("/reload")
    public ApiResponse<ReloadResponse> reload() {
        return ApiResponse.success(new ReloadResponse(skillRegistry.reload()));
    }

    public record ReloadResponse(int loaded) {
    }
}
