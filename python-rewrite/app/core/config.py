"""配置层：pydantic-settings，环境变量优先，.env 兜底。

环境变量两种写法都支持：
- 平铺：``DEEPSEEK_API_KEY`` 等（与 Java 版部署习惯对齐）；
- 嵌套：``RECORDANDGROW__AI__CHAT__API_KEY``（pydantic-settings 原生）。
"""
from __future__ import annotations

from functools import lru_cache

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class EnvBase(BaseSettings):
    """嵌套配置的基类。

    嵌套类由 ``default_factory`` 独立实例化，不会继承父类的 ``env_file``；
    不显式声明的话，只有顶层 ``Settings`` 读得到 ``.env``，密钥在本地会被忽略。
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )


class AiChatSettings(EnvBase):
    base_url: str = Field(default="https://api.deepseek.com", validation_alias=AliasChoices("DEEPSEEK_BASE_URL", "RECORDANDGROW__AI__CHAT__BASE_URL"))
    api_key: str = Field(default="", validation_alias=AliasChoices("DEEPSEEK_API_KEY", "RECORDANDGROW__AI__CHAT__API_KEY"))
    model: str = Field(default="deepseek-flash", validation_alias=AliasChoices("DEEPSEEK_MODEL", "RECORDANDGROW__AI__CHAT__MODEL"))
    fallback_model: str = Field(default="deepseek-v4-pro", validation_alias=AliasChoices("DEEPSEEK_FALLBACK_MODEL", "RECORDANDGROW__AI__CHAT__FALLBACK_MODEL"))
    fallback_enabled: bool = True
    thinking: str = "disabled"
    connect_timeout_s: float = 5.0
    idle_timeout_s: float = 30.0
    first_token_timeout_s: float = 60.0
    total_timeout_s: float = 180.0
    max_attempts: int = 2
    default_max_tokens: int = 2048


class AiEmbeddingSettings(EnvBase):
    base_url: str = Field(default="https://dashscope.aliyuncs.com/compatible-mode/v1", validation_alias=AliasChoices("DASHSCOPE_BASE_URL", "RECORDANDGROW__AI__EMBEDDING__BASE_URL"))
    api_key: str = Field(default="", validation_alias=AliasChoices("DASHSCOPE_API_KEY", "RECORDANDGROW__AI__EMBEDDING__API_KEY"))
    model: str = Field(default="text-embedding-v4", validation_alias=AliasChoices("EMBEDDING_MODEL", "RECORDANDGROW__AI__EMBEDDING__MODEL"))
    dimensions: int = 1024
    batch_size: int = 10
    connect_timeout_s: float = 5.0
    timeout_s: float = 30.0
    max_attempts: int = 2


class AiSettings(EnvBase):
    chat: AiChatSettings = Field(default_factory=AiChatSettings)
    embedding: AiEmbeddingSettings = Field(default_factory=AiEmbeddingSettings)


class DocumentsSettings(EnvBase):
    enabled: bool = True
    top_k: int = 8
    scan_limit: int = 24
    similarity_threshold: float = 0.25
    max_per_document: int = 2
    max_block_chars: int = 300
    max_chars: int = 2000
    query_timeout_s: float = 8.0
    query_max_attempts: int = 1


class RerankSettings(EnvBase):
    enabled: bool = Field(default=False, validation_alias=AliasChoices("RERANK_ENABLED", "RECORDANDGROW__CHAT__RERANK__ENABLED"))
    base_url: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    api_key: str = Field(default="", validation_alias=AliasChoices("DASHSCOPE_API_KEY", "RECORDANDGROW__CHAT__RERANK__API_KEY"))
    model: str = "gte-rerank-v2"
    connect_timeout_s: float = 2.0
    timeout_s: float = 1.5
    min_score: float = 0.2


class FusionSettings(EnvBase):
    enabled: bool = Field(default=False, validation_alias=AliasChoices("FUSION_ENABLED", "RECORDANDGROW__CHAT__FUSION__ENABLED"))
    rrf_k: int = 20
    candidate_limit: int = 20


class KeywordSettings(EnvBase):
    enabled: bool = Field(default=False, validation_alias=AliasChoices("KEYWORD_ENABLED", "RECORDANDGROW__CHAT__KEYWORD__ENABLED"))
    top_k: int = 20
    tokenizer: str = "jieba"


class RoutingSettings(EnvBase):
    enabled: bool = Field(default=False, validation_alias=AliasChoices("ROUTING_ENABLED", "RECORDANDGROW__CHAT__ROUTING__ENABLED"))
    timeout_s: float = 1.5
    max_question_chars: int = 500


class ChatSettings(EnvBase):
    timezone: str = "Asia/Shanghai"
    default_window_days: int = 30
    max_window_days: int = 365
    max_entries: int = 40
    max_entry_chars: int = 500
    min_entry_chars: int = 300
    max_context_chars: int = 8000
    max_records_chars: int = 6000
    max_question_chars: int = 2000
    answer_max_tokens: int = 1024
    max_answer_tokens: int = 4096
    temperature: float = 0.3
    documents: DocumentsSettings = Field(default_factory=DocumentsSettings)
    rerank: RerankSettings = Field(default_factory=RerankSettings)
    fusion: FusionSettings = Field(default_factory=FusionSettings)
    keyword: KeywordSettings = Field(default_factory=KeywordSettings)
    routing: RoutingSettings = Field(default_factory=RoutingSettings)


class McpSettings(EnvBase):
    enabled: bool = Field(default=True, validation_alias=AliasChoices("MCP_ENABLED", "RECORDANDGROW__MCP__ENABLED"))
    endpoint: str = "/mcp"
    server_name: str = "recordandgrow"
    server_version: str = "0.9.9"
    # 非空时 /mcp 要求 Authorization: Bearer <token>；默认空表示不校验（同源内网用）
    auth_token: str = Field(default="", validation_alias=AliasChoices("MCP_AUTH_TOKEN", "RECORDANDGROW__MCP__AUTH_TOKEN"))


class SkillsSettings(EnvBase):
    enabled: bool = Field(default=True, validation_alias=AliasChoices("SKILLS_ENABLED", "RECORDANDGROW__SKILLS__ENABLED"))
    dir: str = Field(default="./skills", validation_alias=AliasChoices("SKILLS_DIR", "RECORDANDGROW__SKILLS__DIR"))
    max_skills: int = 2


class AgentSettings(EnvBase):
    enabled: bool = Field(default=True, validation_alias=AliasChoices("AGENT_ENABLED", "RECORDANDGROW__AGENT__ENABLED"))
    research_max_iters: int = 4
    research_timeout_s: float = 45.0
    answer_timeout_s: float = 120.0
    max_evidence_chars: int = 7000
    max_evidence_items: int = 60
    memory_tools: bool = True
    graph_tools: bool = True
    propose_memory: bool = True
    max_research_notes_chars: int = 600


class GraphSettings(EnvBase):
    enabled: bool = Field(default=True, validation_alias=AliasChoices("GRAPH_ENABLED", "RECORDANDGROW__GRAPH__ENABLED"))
    search_limit: int = 5
    max_triples_per_document: int = 40
    extraction_max_chars: int = 6000
    extraction_timeout_s: float = 30.0
    reindex_batch: int = 10


class MemorySettings(EnvBase):
    enabled: bool = Field(default=True, validation_alias=AliasChoices("MEMORY_ENABLED", "RECORDANDGROW__MEMORY__ENABLED"))
    history_max_messages: int = 12
    history_max_chars: int = 4000
    summary_threshold: int = 20
    summary_keep: int = 6
    summary_max_chars: int = 800
    extraction_enabled: bool = Field(default=True, validation_alias=AliasChoices("MEMORY_EXTRACTION_ENABLED", "RECORDANDGROW__MEMORY__EXTRACTION_ENABLED"))
    extraction_max_candidates: int = 2
    pending_max: int = 50
    search_limit: int = 5
    async_timeout_s: float = 20.0


class ChunkSettings(EnvBase):
    max_tokens: int = 400
    overlap_tokens: int = 60
    min_tokens: int = 20
    max_chunks: int = 2000
    tokenizer: str = "cl100k"


class ReconcileSettings(EnvBase):
    enabled: bool = True
    interval_s: float = 300.0
    stale_after_s: float = 600.0
    max_retries: int = 3


class DocumentSettings(EnvBase):
    storage_dir: str = "./data/documents"
    max_file_size: int = 10 * 1024 * 1024
    allowed_extensions: str = "pdf,md,markdown,txt"
    max_chars: int = 2_000_000
    parse_timeout_s: float = 60.0
    chunk: ChunkSettings = Field(default_factory=ChunkSettings)
    reconcile: ReconcileSettings = Field(default_factory=ReconcileSettings)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    name: str = "record-and-grow"
    version: str = "0.9.9"
    database_url: str = Field(
        default="postgresql+asyncpg://recordandgrow:recordandgrow@localhost:5432/recordandgrow",
        validation_alias=AliasChoices("DATABASE_URL", "RECORDANDGROW__DATABASE_URL"),
    )
    ai: AiSettings = Field(default_factory=AiSettings)
    chat: ChatSettings = Field(default_factory=ChatSettings)
    mcp: McpSettings = Field(default_factory=McpSettings)
    skills: SkillsSettings = Field(default_factory=SkillsSettings)
    agent: AgentSettings = Field(default_factory=AgentSettings)
    graph: GraphSettings = Field(default_factory=GraphSettings)
    memory: MemorySettings = Field(default_factory=MemorySettings)
    document: DocumentSettings = Field(default_factory=DocumentSettings)

    @property
    def allowed_extensions_set(self) -> set[str]:
        return {e.strip().lower().lstrip(".") for e in self.document.allowed_extensions.split(",") if e.strip()}


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
