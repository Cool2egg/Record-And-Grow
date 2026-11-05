"""Skills：目录扫描 + 热加载 + 触发词匹配。"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)


@dataclass
class Skill:
    name: str
    description: str = ""
    triggers: list[str] = field(default_factory=list)
    body: str = ""
    source: str = ""


_FRONTMATTER = re.compile(r"^---\s*\n(.*?)\n---\s*\n?(.*)$", re.S)


def parse_skill(path: Path) -> Skill | None:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        log.warning("skill_read_failed", path=str(path), error=str(exc))
        return None
    match = _FRONTMATTER.match(text)
    if match:
        meta_raw, body = match.group(1), match.group(2)
    else:
        meta_raw, body = "", text

    meta: dict[str, str] = {}
    for line in meta_raw.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        meta[key.strip().lower()] = value.strip()

    triggers_raw = meta.get("triggers", "")
    if triggers_raw.startswith("[") and triggers_raw.endswith("]"):
        triggers_raw = triggers_raw[1:-1]
    triggers = [item.strip().strip("'\"") for item in triggers_raw.split(",") if item.strip()]
    name = meta.get("name") or path.stem
    return Skill(
        name=name,
        description=meta.get("description", ""),
        triggers=triggers,
        body=body.strip(),
        source=str(path),
    )


class SkillRegistry:
    def __init__(self, directory: str | None = None) -> None:
        self.directory = Path(directory or settings.skills.dir)
        self._skills: list[Skill] = []
        self.load()

    def load(self) -> int:
        skills: list[Skill] = []
        if settings.skills.enabled and self.directory.exists():
            for path in sorted(self.directory.glob("*.md")):
                skill = parse_skill(path)
                if skill is not None and skill.body:
                    skills.append(skill)
        self._skills = skills
        return len(skills)

    def reload(self) -> int:
        return self.load()

    def all(self) -> list[Skill]:
        return list(self._skills)

    def match(self, question: str) -> list[Skill]:
        if not question or not self._skills:
            return []
        matched: list[Skill] = []
        for skill in self._skills:
            if any(trigger and trigger in question for trigger in skill.triggers):
                matched.append(skill)
            if len(matched) >= max(1, settings.skills.max_skills):
                break
        return matched


_registry: SkillRegistry | None = None


def get_skill_registry() -> SkillRegistry:
    global _registry
    if _registry is None:
        _registry = SkillRegistry()
    return _registry
