package com.example.interview.application;

import com.example.interview.infrastructure.mysql.PositionEntity;
import com.example.interview.infrastructure.mysql.PositionMapper;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private final PositionMapper positions;
    private final ResumeMapper resumes;
    private final ObjectMapper objectMapper;

    public PositionController(PositionMapper positions,
                              ResumeMapper resumes,
                              ObjectMapper objectMapper) {
        this.positions = positions;
        this.resumes = resumes;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<PositionView> list() {
        return positions.selectList(null)
                .stream()
                .map(this::view)
                .toList();
    }

    @GetMapping("/{id}")
    public PositionView detail(@PathVariable("id") long id) {
        PositionEntity position = positions.selectById(id);
        if (position == null) {
            throw new IllegalArgumentException("position not found");
        }
        return view(position);
    }

    private PositionView view(PositionEntity position) {
        return new PositionView(
                position.getId(),
                position.getName(),
                position.getDescription(),
                position.getSkills()
        );
    }

    @PostMapping("/{id}/match")
    public MatchView match(@PathVariable("id") Long id,
                           @RequestParam("resumeId") Long resumeId) {
        PositionEntity position = positions.selectById(id);
        ResumeEntity resume = resumes.selectById(resumeId);

        if (position == null || resume == null) {
            throw new IllegalArgumentException(
                    "position or resume not found"
            );
        }

        List<String> required = parseSkills(position.getSkills());
        Set<String> candidateSkills = new HashSet<>(
                parseSkills(resume.getExtractedSkills()).stream()
                        .map(this::normalize)
                        .toList()
        );

        List<String> matched = required.stream()
                .filter(skill -> candidateSkills.contains(normalize(skill)))
                .toList();

        double score = required.isEmpty()
                ? 0
                : Math.round(
                matched.size() * 10000.0 / required.size()
        ) / 100.0;

        return new MatchView(
                resumeId,
                id,
                score,
                "匹配技能：" + String.join("、", matched)
        );
    }

    // 岗位技能和 AI 提取技能都按 JSON 数组解析，避免匹配整段简历正文。
    private List<String> parseSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(skills);
            if (!root.isArray()) {
                throw new IllegalArgumentException("skills must be a JSON array");
            }
            return StreamSupport.stream(root.spliterator(), false)
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid skills JSON", e);
        }
    }

    private String normalize(String skill) {
        return skill.trim().toLowerCase(Locale.ROOT);
    }
}
